package com.QM4RS.agent.ui;

import com.QM4RS.agent.core.*;
import com.QM4RS.agent.core.ChatStore.ChatRevision;
import com.QM4RS.agent.core.ChatStore.ChatSession;
import com.QM4RS.agent.core.OpenAIService.OpenAIResult;
import com.QM4RS.agent.core.SelectionModel;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.function.Predicate;

public class MainWindow {

    private final TextField projectPathField = new TextField();

    private final Label statusLabel = new Label("Ready.");
    private final Label tokenLabel = new Label("Tokens: in=? out=? total=?");
    private final Label selectedCountLabel = new Label("Selected: 0");
    private final Label contextStatsLabel = new Label("Context: files=0 bytes=0 chars=0 estTokens=0");

    private final TreeView<Path> treeView = new TreeView<>();
    private final ObservableList<String> selectedFilesList = FXCollections.observableArrayList();
    private final ListView<String> selectedFilesListView = new ListView<>(selectedFilesList);

    private final TextArea promptArea = new TextArea();
    private final TextArea contextPreviewArea = new TextArea();

    // Output (Monaco)
    private final MonacoViewer monacoViewer = new MonacoViewer();
    private final ObservableList<OutputBlock> blockItems = FXCollections.observableArrayList();
    private final ListView<OutputBlock> blockListView = new ListView<>(blockItems);

    // Debug UI (Advanced)
    private final Label debugMetaLabel = new Label("Debug: (no requests yet)");
    private final TextArea debugRequestArea = new TextArea();
    private final TextArea debugResponseArea = new TextArea();

    // Tree filter UI
    private final TextField treeFilterField = new TextField();
    private final ToggleButton regexToggle = new ToggleButton("Regex");

    private String lastFilterText = "";
    private boolean lastRegexMode = false;

    private final SelectionModel selectionModel = new SelectionModel();
    private final IgnoreRules ignoreRules = new IgnoreRules();
    private final ProjectScanner scanner = new ProjectScanner(ignoreRules);

    private final ContextPackBuilder contextPackBuilder =
            new ContextPackBuilder(
                    new ProjectTreePrinter(ignoreRules),
                    new FileTextReader(512L * 1024)
            );

    private final ConfigStore configStore = new ConfigStore();
    private AppConfig config = configStore.load();

    private final OpenAIService openAIService = new OpenAIService();
    private final OutputParser outputParser = new OutputParser();

    private Path currentProjectRoot;

    private final BooleanProperty apiKeyMissing = new SimpleBooleanProperty(true);
    private final IntegerProperty selectedCount = new SimpleIntegerProperty(0);
    private final BooleanProperty isRunning = new SimpleBooleanProperty(false);

    private Task<OpenAIResult> runningTask;

    // Keep original tree for filter rebuild
    private CheckBoxTreeItem<Path> originalRootItem;

    // Bulk update guard
    private boolean bulkUpdating = false;

    // Progressive disclosure
    private final BooleanProperty detailsMode = new SimpleBooleanProperty(false);

    // Tree empty overlay
    private final BooleanProperty treeEmptyVisible = new SimpleBooleanProperty(true);
    private final Label treeEmptyTitleLabel = new Label();
    private final Label treeEmptyBodyLabel = new Label();
    private final VBox treeEmptyOverlay = new VBox(6, treeEmptyTitleLabel, treeEmptyBodyLabel);

    // Debug/state snapshots
    private String lastBuiltContext = "";
    private String lastRequestText = "";
    private String lastResponseText = "";
    private String lastModelUsed = "";
    private String lastErrorText = "";

    // Status pseudo classes
    private static final PseudoClass PC_OK = PseudoClass.getPseudoClass("ok");
    private static final PseudoClass PC_WARN = PseudoClass.getPseudoClass("warn");
    private static final PseudoClass PC_ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass PC_RUNNING = PseudoClass.getPseudoClass("running");

    private enum StatusKind { INFO, OK, WARN, ERROR, RUNNING }

    // ================== Chats ==================
    private final ChatStore chatStore = new ChatStore();
    private final ObservableList<ChatSession> chatItems = FXCollections.observableArrayList();
    private final ListView<ChatSession> chatListView = new ListView<>(chatItems);
    private final Button newChatBtn = new Button("+ New Chat");
    private final CheckBox includeHistoryCheck = new CheckBox("History");

    private final ObjectProperty<ChatSession> currentChatProperty = new SimpleObjectProperty<>(null);

    // revision navigation (for code viewer)
    private final Button revBackBtn = new Button("◀");
    private final Button revForwardBtn = new Button("▶");
    private final Label revLabel = new Label("Revision: -/-");

    private int currentBlockIndex = 0;

    private static final DateTimeFormatter TS_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TS_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    private boolean switchingChat = false;

    private static String fmt(LocalDateTime t) {
        if (t == null) return "";
        return TS_LOCAL.format(t);
    }

    private static String fmt(OffsetDateTime t) {
        if (t == null) return "";
        return TS_OFFSET.format(t);
    }

    public void show(Stage stage) {
        stage.setTitle("GPT-Agent");

        // Load chats from disk
        chatStore.load();
        refreshChatListFromStore();

        // ========= Top Bar =========
        HBox topBar = buildTopBar(stage);

        // ========= Step hint =========
        Label flowHint = new Label();
        flowHint.getStyleClass().addAll("hint", "flow-hint");
        flowHint.textProperty().bind(Bindings.createStringBinding(() -> {
            boolean hasChat = currentChatProperty.get() != null;
            boolean hasProject = projectPathField.getText() != null && !projectPathField.getText().isBlank();
            boolean hasFiles = selectedCount.get() > 0;
            boolean hasPrompt = promptArea.getText() != null && !promptArea.getText().isBlank();
            boolean hasKey = !apiKeyMissing.get();

            if (!hasChat) return "Step 0: Create/select a chat.";
            if (!hasProject) return "Step 1: Choose a project folder.";
            if (!hasFiles) return "Step 2: Select files from the tree (left).";
            if (!hasPrompt) return "Step 3: Write what you want to change (Prompt).";
            if (!hasKey) return "Step 0: Add API Key in Settings to enable sending.";
            return "Step 4: Ready — press Send to GPT.";
        }, projectPathField.textProperty(), selectedCount, promptArea.textProperty(), apiKeyMissing, currentChatProperty));

        // ========= API key warning =========
        HBox apiWarn = buildApiKeyWarning();

        // ========= Tree =========
        treeView.setShowRoot(true);
        treeView.disableProperty().bind(isRunning);

        treeView.setCellFactory(tv -> new CheckBoxTreeCell<>() {
            @Override
            public void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                    return;
                }
                setText(prettyName(item));
                setContextMenu(null);
            }
        });

        configureEmptyStateBox(treeEmptyOverlay, treeEmptyTitleLabel, treeEmptyBodyLabel);
        treeEmptyOverlay.visibleProperty().bind(treeEmptyVisible);
        treeEmptyOverlay.managedProperty().bind(treeEmptyVisible);
        treeEmptyOverlay.setMouseTransparent(true);

        setTreeEmptyState(true, "No project loaded", "");

        StackPane treeStack = new StackPane(treeView, treeEmptyOverlay);
        StackPane.setAlignment(treeEmptyOverlay, Pos.CENTER);

        // ========= Filter bar =========
        treeFilterField.setPromptText("Search files... (text or regex)");
        treeFilterField.textProperty().addListener((obs, oldV, newV) -> applyTreeFilter(newV, regexToggle.isSelected()));

        regexToggle.setFocusTraversable(false);
        regexToggle.getStyleClass().add("btn-toggle");
        regexToggle.selectedProperty().addListener((obs, oldV, newV) -> applyTreeFilter(treeFilterField.getText(), newV));

        HBox filterBar = new HBox(8, treeFilterField, regexToggle);
        HBox.setHgrow(treeFilterField, Priority.ALWAYS);

        Label treeTitle = new Label("Step 2 • Select files");
        treeTitle.getStyleClass().add("section-title");

        VBox treeBox = new VBox(10,
                treeTitle,
                new Label("Tip: Use search to quickly find files (toggle Regex if needed)."),
                filterBar,
                new BorderPane(treeStack)
        );
        treeBox.getStyleClass().add("panel");
        treeBox.setPadding(new Insets(12));
        VBox.setVgrow(treeBox.getChildren().get(3), Priority.ALWAYS);

        // ========= Selected Panel =========
        selectedFilesListView.setFocusTraversable(false);
        selectedFilesListView.setPlaceholder(buildEmptyState(
                "No files selected",
                "Tick checkboxes in the tree.\nOnly selected files are included in the context pack."
        ));
        selectedFilesListView.disableProperty().bind(isRunning);

        Label selectedTitle = new Label("Selected files");
        selectedTitle.getStyleClass().add("section-title");

        VBox selectedPanel = new VBox(10,
                selectedTitle,
                selectedFilesListView,
                selectedCountLabel
        );
        selectedPanel.setPadding(new Insets(12));
        selectedPanel.setPrefWidth(380);
        VBox.setVgrow(selectedFilesListView, Priority.ALWAYS);
        selectedPanel.getStyleClass().add("panel");

        SplitPane leftSplit = new SplitPane(treeBox, selectedPanel);
        leftSplit.setOrientation(Orientation.VERTICAL);
        leftSplit.setDividerPositions(0.62);
        leftSplit.setMinSize(0, 0);

        // ========= Chats Panel =========
        VBox chatsPanel = buildChatsPanel();
        chatsPanel.setMinHeight(160);
        chatsPanel.setPrefHeight(220);

        SplitPane leftBig = new SplitPane(chatsPanel, leftSplit);
        leftBig.setOrientation(Orientation.VERTICAL);
        leftBig.setDividerPositions(0.25);
        leftBig.setMinSize(0, 0);

        // ========= Prompt =========
        promptArea.setPromptText("""
                Example:
                - Update method X in class Y
                - Replace lines 120-180 in file Z
                - Create a new class ...
                Be specific (method/class/range), and describe expected behavior.
                """.trim());
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(6);
        promptArea.disableProperty().bind(isRunning);

        promptArea.textProperty().addListener((obs, oldV, newV) -> {
            if (switchingChat) return;
            if (newV != null && newV.equals(oldV)) return;

            ChatSession cur = currentChatProperty.get();
            if (cur == null) return;

            String nv = (newV == null) ? "" : newV;
            if (nv.equals(cur.promptText == null ? "" : cur.promptText)) return;

            cur.promptText = nv;

            String t = deriveTitleFromPrompt(cur.promptText);
            if (t != null && !t.isBlank()) cur.title = t;

            boolean promote = cur.revisionCount() > 0;
            chatStore.touch(cur, promote);

            persistChatsSilently();
            refreshChatListFromStorePreserveSelection();
        });



        includeHistoryCheck.setFocusTraversable(false);
        includeHistoryCheck.selectedProperty().addListener((obs, oldV, newV) -> {
            if (switchingChat) return;
            if (newV != null && newV.equals(oldV)) return;

            ChatSession cur = currentChatProperty.get();
            if (cur == null) return;

            boolean nv = newV != null && newV;
            boolean cv = cur.includeHistory != null && cur.includeHistory;
            if (nv == cv) return;

            cur.includeHistory = nv;
            boolean promote = cur.revisionCount() > 0;
            chatStore.touch(cur, promote);

            persistChatsSilently();
        });



        Button buildContextBtn = new Button("Build Context");
        buildContextBtn.getStyleClass().add("btn-secondary");
        buildContextBtn.disableProperty().bind(projectPathField.textProperty().isEmpty().or(isRunning));
        buildContextBtn.setOnAction(e -> buildContextPack());

        Button sendBtn = new Button("Send to GPT");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setDefaultButton(true);

        sendBtn.disableProperty().bind(
                currentChatProperty.isNull()
                        .or(projectPathField.textProperty().isEmpty())
                        .or(selectedCount.isEqualTo(0))
                        .or(promptArea.textProperty().isEmpty())
                        .or(apiKeyMissing)
                        .or(isRunning)
        );

        sendBtn.setOnAction(e -> sendToGpt());

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-ghost");
        cancelBtn.disableProperty().bind(isRunning.not());
        cancelBtn.setOnAction(e -> cancelRunningTask());

        Button clearOutputBtn = new Button("Clear Output");
        clearOutputBtn.getStyleClass().add("btn-danger");
        clearOutputBtn.disableProperty().bind(isRunning);
        clearOutputBtn.setOnAction(e -> clearOutputWithConfirm());

        HBox promptButtons = new HBox(10, buildContextBtn, sendBtn, cancelBtn, clearOutputBtn);
        promptButtons.setAlignment(Pos.CENTER_LEFT);

        contextStatsLabel.getStyleClass().addAll("hint", "mono");
        contextStatsLabel.visibleProperty().bind(detailsMode);
        contextStatsLabel.managedProperty().bind(detailsMode);

        Label promptTitle = new Label("Step 3 • Prompt");
        promptTitle.getStyleClass().add("section-title");

        HBox historyRow = new HBox(10, includeHistoryCheck);
        historyRow.setAlignment(Pos.CENTER_LEFT);

        VBox promptBox = new VBox(10,
                flowHint,
                apiWarn,
                promptTitle,
                promptArea,
                historyRow,
                promptButtons,
                contextStatsLabel
        );
        promptBox.setPadding(new Insets(12));
        promptBox.getStyleClass().add("panel");

        // ========= Output Tab =========
        setupOutputList();

        Button copyCodeBtn = new Button("Copy");
        copyCodeBtn.getStyleClass().add("btn-secondary");
        copyCodeBtn.setOnAction(e -> copyFromMonacoSmart());

        setupRevisionNavButtons();

        HBox viewerToolbar = new HBox(10, revBackBtn, revForwardBtn, revLabel, spacer(), copyCodeBtn);
        viewerToolbar.setAlignment(Pos.CENTER_LEFT);
        viewerToolbar.setPadding(new Insets(10));
        viewerToolbar.getStyleClass().add("panel-subtle");

        BorderPane viewerWrap = new BorderPane(monacoViewer);
        viewerWrap.setMinSize(0, 0);

        blockListView.setPlaceholder(buildEmptyState(
                "No output yet",
                "Send to GPT to generate change blocks.\nThen click a block to preview its code."
        ));
        blockListView.disableProperty().bind(isRunning);

        SplitPane outputSplit = new SplitPane(blockListView, viewerWrap);
        outputSplit.setDividerPositions(0.28);
        outputSplit.setMinSize(0, 0);

        blockListView.setMinWidth(250);
        blockListView.setPrefWidth(320);
        blockListView.setMaxWidth(520);

        VBox outTabRoot = new VBox(viewerToolbar, outputSplit);
        VBox.setVgrow(outputSplit, Priority.ALWAYS);

        Tab outTab = new Tab("Output", outTabRoot);

        // ========= Context Tab =========
        contextPreviewArea.setEditable(false);
        contextPreviewArea.setWrapText(false);
        contextPreviewArea.setPromptText("Context pack will appear here after Build Context or Send.");
        contextPreviewArea.getStyleClass().add("mono");
        contextPreviewArea.disableProperty().bind(isRunning);

        BorderPane ctxTabRoot = new BorderPane(contextPreviewArea);
        ctxTabRoot.setPadding(new Insets(10));
        Tab ctxTab = new Tab("Context Pack", ctxTabRoot);

        // ========= Debug Tab =========
        debugRequestArea.setEditable(false);
        debugRequestArea.setWrapText(false);
        debugRequestArea.getStyleClass().add("mono");

        debugResponseArea.setEditable(false);
        debugResponseArea.setWrapText(false);
        debugResponseArea.getStyleClass().add("mono");

        debugMetaLabel.getStyleClass().add("mono");

        SplitPane debugSplit = new SplitPane(
                wrapTextAreaWithTitle("Request", debugRequestArea),
                wrapTextAreaWithTitle("Response", debugResponseArea)
        );
        debugSplit.setDividerPositions(0.5);

        VBox debugRoot = new VBox(10, debugMetaLabel, debugSplit);
        debugRoot.setPadding(new Insets(10));
        VBox.setVgrow(debugSplit, Priority.ALWAYS);

        Tab dbgTab = new Tab("Debug", debugRoot);

        TabPane tabs = new TabPane(outTab, ctxTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        detailsMode.addListener((obs, oldV, newV) -> {
            if (newV) {
                if (!tabs.getTabs().contains(dbgTab)) tabs.getTabs().add(dbgTab);
            } else {
                tabs.getTabs().remove(dbgTab);
            }
            refreshContextStats();
        });

        // ========= Right Side =========
        BorderPane right = new BorderPane();
        right.setTop(promptBox);
        right.setCenter(tabs);
        BorderPane.setMargin(promptBox, new Insets(0, 0, 10, 0));

        SplitPane mainSplit = new SplitPane(leftBig, right);
        mainSplit.setDividerPositions(0.40);
        mainSplit.setMinSize(0, 0);

        // ========= Bottom Bar =========
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(18, 18);
        spinner.visibleProperty().bind(isRunning);
        spinner.managedProperty().bind(isRunning);

        statusLabel.getStyleClass().add("status-label");
        tokenLabel.getStyleClass().addAll("hint", "mono");
        tokenLabel.visibleProperty().bind(detailsMode);
        tokenLabel.managedProperty().bind(detailsMode);

        HBox bottom = new HBox(10, spinner, statusLabel, spacer(), tokenLabel);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(10, 12, 10, 12));
        bottom.getStyleClass().add("panel");

        // ========= Root =========
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setTop(topBar);
        root.setCenter(mainSplit);
        root.setBottom(bottom);

        BorderPane.setMargin(topBar, new Insets(0, 0, 10, 0));
        BorderPane.setMargin(bottom, new Insets(10, 0, 0, 0));

        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(inlineStylesheet());
        stage.setScene(scene);

        selectedCountLabel.textProperty().bind(Bindings.concat("Selected: ", selectedCount.asString()));
        installCopyShortcut(scene);

        var bounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1320, bounds.getWidth() * 0.94));
        stage.setHeight(Math.min(880, bounds.getHeight() * 0.94));
        stage.setX(bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2.0);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - stage.getHeight()) / 2.0);

        stage.show();

        updateApiKeyFlag();
        tryConfigureClientSilently();

        refreshContextStats();
        refreshDebugUI();
        setStatus(StatusKind.INFO, "Ready.");

        if (chatItems.isEmpty()) {
            createNewChatAndSelect();
        } else {
            chatListView.getSelectionModel().select(0);
        }
    }

    // ---------------- Chats UI ----------------

    private VBox buildChatsPanel() {
        Label chatsTitle = new Label("Chats");
        chatsTitle.getStyleClass().add("section-title");

        newChatBtn.getStyleClass().add("btn-secondary");
        newChatBtn.setOnAction(e -> createNewChatAndSelect());

        HBox header = new HBox(10, chatsTitle, spacer(), newChatBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        chatListView.setPlaceholder(buildEmptyState("No chats", "Create a new chat to start."));
        chatListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ChatSession item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                    return;
                }
                String title = item.title == null ? "Chat" : item.title;
                int revs = item.revisionCount();
                String when = fmt(item.getUpdatedAtSafe());
                setText(title + "\n" + "Revisions: " + revs + (when.isBlank() ? "" : (" • " + when)));

                MenuItem del = new MenuItem("Delete");
                del.setOnAction(e -> deleteChatWithConfirm(item));
                setContextMenu(new ContextMenu(del));
            }
        });

        chatListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            switchToChat(newV);
        });

        VBox box = new VBox(10, header, chatListView);
        box.setPadding(new Insets(12));
        box.getStyleClass().add("panel");
        VBox.setVgrow(chatListView, Priority.ALWAYS);
        return box;
    }

    private void refreshChatListFromStore() {
        chatItems.setAll(chatStore.getSessionsSorted());
    }

    private void refreshChatListFromStorePreserveSelection() {
        ChatSession cur = currentChatProperty.get();
        String curId = cur == null ? null : cur.id;
        refreshChatListFromStore();
        if (curId != null) {
            for (int i = 0; i < chatItems.size(); i++) {
                if (curId.equals(chatItems.get(i).id)) {
                    chatListView.getSelectionModel().select(i);
                    break;
                }
            }
        }
    }

    private void createNewChatAndSelect() {
        ChatSession s = chatStore.createNew();
        chatStore.touch(s);
        persistChatsSilently();
        refreshChatListFromStore();
        chatListView.getSelectionModel().select(s);
    }

    private void deleteChatWithConfirm(ChatSession s) {
        if (s == null) return;

        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Delete Chat");
        a.setHeaderText("Delete this chat?");
        a.setContentText("This will remove the chat history locally and cannot be undone.");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) return;

        boolean wasCurrent = currentChatProperty.get() != null
                && currentChatProperty.get().id != null
                && currentChatProperty.get().id.equals(s.id);

        chatStore.delete(s);
        persistChatsSilently();
        refreshChatListFromStore();

        if (wasCurrent) {
            currentChatProperty.set(null);
            clearOutput();
            promptArea.setText("");
            includeHistoryCheck.setSelected(false);

            if (!chatItems.isEmpty()) chatListView.getSelectionModel().select(0);
            else createNewChatAndSelect();
        }
    }

    private void switchToChat(ChatSession s) {
        if (s == null) return;

        currentChatProperty.set(s);

        promptArea.setText(s.promptText == null ? "" : s.promptText);
        includeHistoryCheck.setSelected(s.includeHistory);

        s.clampRevisionIndex();
        ChatRevision rev = s.getCurrentRevision();
        if (rev == null || rev.responseText == null) {
            clearOutput();
        } else {
            lastModelUsed = rev.model == null ? "" : rev.model;
            lastResponseText = rev.responseText;
            renderBlocks(rev.responseText);
        }
        updateRevisionNavUI();
    }

    private String deriveTitleFromPrompt(String prompt) {
        if (prompt == null) return null;
        String p = prompt.strip();
        if (p.isBlank()) return "New Chat";
        int nl = p.indexOf('\n');
        String first = (nl < 0) ? p : p.substring(0, nl);
        first = first.strip();
        if (first.length() > 42) first = first.substring(0, 42).strip() + "…";
        return first.isBlank() ? "Chat" : first;
    }

    private void persistChatsSilently() {
        try {
            chatStore.save();
        } catch (Exception ignored) {}
    }

    // ---------------- Revision navigation ----------------

    private void setupRevisionNavButtons() {
        revBackBtn.getStyleClass().add("btn-ghost");
        revForwardBtn.getStyleClass().add("btn-ghost");
        revLabel.getStyleClass().addAll("hint", "mono");

        revBackBtn.setOnAction(e -> moveRevision(-1));
        revForwardBtn.setOnAction(e -> moveRevision(+1));
    }

    private void moveRevision(int delta) {
        ChatSession cur = currentChatProperty.get();
        if (cur == null) return;
        int n = cur.revisionCount();
        if (n <= 0) return;

        cur.clampRevisionIndex();
        int idx = cur.currentRevisionIndex;
        if (idx < 0) idx = n - 1;

        int next = idx + delta;
        if (next < 0) next = 0;
        if (next >= n) next = n - 1;

        if (next == idx) return;

        cur.currentRevisionIndex = next;
        chatStore.touch(cur);
        persistChatsSilently();

        ChatRevision rev = cur.getCurrentRevision();
        if (rev != null) {
            lastModelUsed = rev.model == null ? "" : rev.model;
            lastResponseText = rev.responseText == null ? "" : rev.responseText;
            renderBlocks(lastResponseText);

            if (currentBlockIndex >= 0 && currentBlockIndex < blockItems.size()) {
                blockListView.getSelectionModel().select(currentBlockIndex);
            } else {
                blockListView.getSelectionModel().select(0);
                currentBlockIndex = 0;
            }
        }
        updateRevisionNavUI();
        setStatus(StatusKind.INFO, "Revision " + (next + 1) + " / " + n);
    }

    private void updateRevisionNavUI() {
        ChatSession cur = currentChatProperty.get();
        if (cur == null) {
            revBackBtn.setDisable(true);
            revForwardBtn.setDisable(true);
            revLabel.setText("Revision: -/-");
            return;
        }
        int n = cur.revisionCount();
        if (n <= 0) {
            revBackBtn.setDisable(true);
            revForwardBtn.setDisable(true);
            revLabel.setText("Revision: -/-");
            return;
        }

        cur.clampRevisionIndex();
        int idx = cur.currentRevisionIndex;
        if (idx < 0) idx = n - 1;

        revLabel.setText("Revision: " + (idx + 1) + "/" + n);
        revBackBtn.setDisable(idx <= 0);
        revForwardBtn.setDisable(idx >= n - 1);
    }

    // ---------------- Existing UI helpers ----------------

    private void configureEmptyStateBox(VBox box, Label t, Label b) {
        t.getStyleClass().add("empty-title");
        b.getStyleClass().add("empty-body");
        b.setWrapText(true);
        box.setPadding(new Insets(14));
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(520);
        box.setAlignment(Pos.CENTER_LEFT);
    }

    private void setTreeEmptyState(boolean visible, String title, String body) {
        treeEmptyTitleLabel.setText(title == null ? "" : title);
        treeEmptyBodyLabel.setText(body == null ? "" : body);
        treeEmptyVisible.set(visible);
    }

    private VBox buildEmptyState(String title, String body) {
        Label t = new Label(title);
        Label b = new Label(body);
        VBox box = new VBox(6, t, b);
        configureEmptyStateBox(box, t, b);
        return box;
    }

    private HBox buildApiKeyWarning() {
        Hyperlink openSettings = new Hyperlink("Open Settings");
        openSettings.setOnAction(e -> openSettings());

        Label warn = new Label("API key is missing. Add it in Settings to enable sending.");
        warn.setWrapText(true);

        HBox box = new HBox(10, new Label("⚠"), warn, openSettings);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("banner-warn");
        box.visibleProperty().bind(apiKeyMissing);
        box.managedProperty().bind(apiKeyMissing);
        return box;
    }

    private VBox wrapTextAreaWithTitle(String title, TextArea area) {
        Label l = new Label(title + ":");
        l.getStyleClass().add("section-title");
        VBox box = new VBox(6, l, area);
        VBox.setVgrow(area, Priority.ALWAYS);
        return box;
    }

    private HBox buildTopBar(Stage stage) {
        projectPathField.setPromptText("Step 1: Choose a project folder...");
        projectPathField.setEditable(false);

        Button chooseBtn = new Button("Choose Folder");
        chooseBtn.getStyleClass().add("btn-secondary");
        chooseBtn.setOnAction(e -> chooseProjectFolder(stage));

        Button reloadBtn = new Button("Reload");
        reloadBtn.getStyleClass().add("btn-ghost");
        reloadBtn.disableProperty().bind(projectPathField.textProperty().isEmpty().or(isRunning));
        reloadBtn.setOnAction(e -> {
            if (currentProjectRoot != null) loadProjectTree(currentProjectRoot);
        });

        ToggleButton detailsBtn = new ToggleButton("Details");
        detailsBtn.getStyleClass().add("btn-toggle");
        detailsBtn.setFocusTraversable(false);
        detailsBtn.selectedProperty().bindBidirectional(detailsMode);

        Button settingsBtn = new Button("Settings");
        settingsBtn.getStyleClass().add("btn-ghost");
        settingsBtn.disableProperty().bind(isRunning);
        settingsBtn.setOnAction(e -> openSettings());

        HBox top = new HBox(10, projectPathField, chooseBtn, reloadBtn, detailsBtn, settingsBtn);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(projectPathField, Priority.ALWAYS);
        top.getStyleClass().add("panel");
        top.setPadding(new Insets(10, 12, 10, 12));
        return top;
    }

    private void installCopyShortcut(Scene scene) {
        var ctrlC = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        scene.getAccelerators().put(ctrlC, () -> {
            var focus = scene.getFocusOwner();
            if (focus instanceof TextInputControl) return;
            if (monacoViewer.isFocusInside()) copyFromMonacoSmart();
        });
    }

    private void copyFromMonacoSmart() {
        String selected = monacoViewer.getSelectedTextNow();
        String text = (selected != null && !selected.isBlank())
                ? selected
                : monacoViewer.getContentNow();
        copyTextToClipboard(text, "Copied to clipboard.");
    }

    private void setupOutputList() {
        blockListView.getStyleClass().add("block-list");

        blockListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(OutputBlock item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String action = item.action == null ? "CHANGE" : item.action;
                String file = item.file == null ? "" : item.file;
                String hint = "";
                if (item.range != null && !item.range.isBlank()) hint = " [" + item.range + "]";
                else if (item.target != null && !item.target.isBlank()) hint = " [" + item.target + "]";
                setText(action + hint + "\n" + file);
            }
        });

        blockListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            currentBlockIndex = newV.intValue();
        });

        blockListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            String text = newV.code == null ? "" : newV.code;

            String lang = resolveLanguageForBlock(newV, text);

            monacoViewer.setTheme("vs-dark");
            monacoViewer.setContent(text, lang);
        });
    }

    private String resolveLanguageForBlock(OutputBlock block, String text) {
        String headerLang = extractHeaderValue(text, "LANGUAGE:");
        if (headerLang != null && !headerLang.isBlank()) return headerLang.trim().toLowerCase(Locale.ROOT);

        String headerFile = extractHeaderValue(text, "FILE:");
        if (headerFile != null && !headerFile.isBlank()) return guessLanguage(headerFile.trim());

        return guessLanguage(block == null ? null : block.file);
    }

    private void clearOutputWithConfirm() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Clear Output");
        a.setHeaderText("Clear generated output?");
        a.setContentText("This will remove blocks and the viewer content (for current view).");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) return;
        clearOutput();
    }

    private void clearOutput() {
        blockItems.clear();
        monacoViewer.setContent("", "plaintext");
        tokenLabel.setText("Tokens: in=? out=? total=?");
        setStatus(StatusKind.INFO, "Output cleared.");
        lastResponseText = "";
        lastErrorText = "";
        refreshDebugUI();
        updateRevisionNavUI();
    }

    private void setStatus(StatusKind kind, String text) {
        statusLabel.setText(text == null ? "" : text);

        statusLabel.pseudoClassStateChanged(PC_OK, kind == StatusKind.OK);
        statusLabel.pseudoClassStateChanged(PC_WARN, kind == StatusKind.WARN);
        statusLabel.pseudoClassStateChanged(PC_ERROR, kind == StatusKind.ERROR);
        statusLabel.pseudoClassStateChanged(PC_RUNNING, kind == StatusKind.RUNNING);
    }

    private void updateApiKeyFlag() {
        apiKeyMissing.set(config.getApiKey() == null || config.getApiKey().isBlank());
    }

    private void openSettings() {
        SettingsDialog dlg = new SettingsDialog();
        dlg.show(config).ifPresent(newCfg -> {
            try {
                configStore.save(newCfg);
                config = configStore.load();
                updateApiKeyFlag();
                tryConfigureClientSilently();
                setStatus(StatusKind.OK, "Settings saved: " + configStore.getConfigPath());
            } catch (Exception ex) {
                setStatus(StatusKind.ERROR, "Settings save error: " + ex.getMessage());
            }
        });
    }

    private void tryConfigureClientSilently() {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) return;
        try {
            openAIService.configure(config.getApiKey());
        } catch (Exception ignored) {}
    }

    private void chooseProjectFolder(Stage owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Project Folder");

        File selected = chooser.showDialog(owner);
        if (selected == null) {
            setStatus(StatusKind.INFO, "No folder selected.");
            return;
        }

        Path root = selected.toPath();
        if (!Files.isDirectory(root)) {
            setStatus(StatusKind.ERROR, "Selected path is not a folder.");
            return;
        }

        currentProjectRoot = root;
        projectPathField.setText(root.toAbsolutePath().toString());
        loadProjectTree(root);
    }

    private void loadProjectTree(Path root) {
        try {
            setStatus(StatusKind.RUNNING, "Scanning project...");
            selectionModel.clear();
            selectedFilesList.clear();
            selectedCount.set(0);
            contextPreviewArea.clear();
            clearOutput();

            treeFilterField.clear();
            lastFilterText = "";
            lastRegexMode = false;
            regexToggle.setSelected(false);

            lastBuiltContext = "";
            lastRequestText = "";
            lastResponseText = "";
            lastModelUsed = "";
            lastErrorText = "";
            refreshDebugUI();

            var result = scanner.scan(root);
            originalRootItem = result.rootItem();

            if (originalRootItem == null) {
                treeView.setRoot(null);
                setTreeEmptyState(true,
                        "No files to display",
                        "Nothing found (or everything ignored by rules)."
                );
                setStatus(StatusKind.WARN, "Loaded, but no tree root.");
                return;
            }

            attachSelectionListeners(originalRootItem);

            treeView.setRoot(originalRootItem);
            treeView.getRoot().setExpanded(true);

            setTreeEmptyState(false, "", "");

            setStatus(StatusKind.OK, "Loaded. Files: " + result.allFiles().size());
            refreshSelectedFilesUI();
            refreshContextStats();
        } catch (Exception ex) {
            setStatus(StatusKind.ERROR, "Error: " + ex.getMessage());
            treeView.setRoot(null);
            originalRootItem = null;
            setTreeEmptyState(true,
                    "Failed to load",
                    "Could not scan this project.\nTry a different folder or check permissions."
            );
        }
    }

    private void applyTreeFilter(String filterText, boolean regexMode) {
        String ft = (filterText == null) ? "" : filterText.trim();

        if (ft.equals(lastFilterText) && regexMode == lastRegexMode) return;

        lastFilterText = ft;
        lastRegexMode = regexMode;

        if (originalRootItem == null) return;

        if (ft.isBlank()) {
            treeView.setRoot(originalRootItem);
            treeView.getRoot().setExpanded(true);
            setTreeEmptyState(false, "", "");
            setStatus(StatusKind.INFO, "Filter cleared.");
            return;
        }

        Predicate<Path> matcher;
        try {
            matcher = regexMode ? buildRegexMatcher(ft) : buildTextMatcher(ft);
        } catch (PatternSyntaxException ex) {
            setStatus(StatusKind.WARN, "Regex error: " + ex.getDescription());
            return;
        }

        CheckBoxTreeItem<Path> filtered = filterTree(originalRootItem, matcher);

        if (filtered == null || filtered.getChildren().isEmpty()) {
            treeView.setRoot(null);
            setTreeEmptyState(true,
                    "No matches",
                    "Try a different query.\nTip: use \".java\" or \"*.java\" for extensions, or enable Regex."
            );
            setStatus(StatusKind.INFO, (regexMode ? "Regex" : "Search") + ": no matches");
            return;
        }

        attachSelectionListeners(filtered);
        treeView.setRoot(filtered);
        treeView.getRoot().setExpanded(true);
        expandSome(filtered, 3);

        setTreeEmptyState(false, "", "");
        setStatus(StatusKind.INFO, regexMode ? ("Regex: " + ft) : ("Search: " + ft));
    }

    private Predicate<Path> buildTextMatcher(String raw) {
        final String q = raw.toLowerCase(Locale.ROOT);

        final String extQuery;
        if (q.startsWith("*.")) extQuery = q.substring(1);
        else if (q.startsWith(".")) extQuery = q;
        else extQuery = null;

        return (Path p) -> {
            if (p == null) return false;

            String rel = toRelString(p);
            String name = (p.getFileName() == null ? p.toString() : p.getFileName().toString());

            String relL = rel.toLowerCase(Locale.ROOT);
            String nameL = name.toLowerCase(Locale.ROOT);

            if (extQuery != null) return nameL.endsWith(extQuery);

            return relL.contains(q) || nameL.contains(q);
        };
    }

    private Predicate<Path> buildRegexMatcher(String regex) throws PatternSyntaxException {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return (Path path) -> {
            if (path == null) return false;
            String rel = toRelString(path);
            return p.matcher(rel).find();
        };
    }

    private String toRelString(Path p) {
        try {
            if (currentProjectRoot != null) {
                return currentProjectRoot.relativize(p).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {}
        return p.toString().replace('\\', '/');
    }

    private CheckBoxTreeItem<Path> filterTree(CheckBoxTreeItem<Path> source, Predicate<Path> matcher) {
        if (source == null) return null;

        Path value = source.getValue();
        boolean isFile = (value != null && Files.isRegularFile(value));

        if (isFile) {
            if (!matcher.test(value)) return null;

            CheckBoxTreeItem<Path> copy = new CheckBoxTreeItem<>(value);
            boolean sel = selectionModel.getSelectedFilesSorted().contains(value);
            copy.setSelected(sel);
            return copy;
        }

        CheckBoxTreeItem<Path> copyDir = new CheckBoxTreeItem<>(value);
        boolean anyChild = false;

        for (TreeItem<Path> ch : source.getChildren()) {
            @SuppressWarnings("unchecked")
            CheckBoxTreeItem<Path> c = (CheckBoxTreeItem<Path>) ch;

            CheckBoxTreeItem<Path> filteredChild = filterTree(c, matcher);
            if (filteredChild != null) {
                copyDir.getChildren().add(filteredChild);
                anyChild = true;
            }
        }

        boolean selfMatches = (value != null && matcher.test(value));
        if (!anyChild && !selfMatches) return null;

        copyDir.setExpanded(true);
        return copyDir;
    }

    private void expandSome(CheckBoxTreeItem<Path> node, int depth) {
        if (node == null || depth < 0) return;
        node.setExpanded(true);
        if (depth == 0) return;
        for (TreeItem<Path> ch : node.getChildren()) {
            @SuppressWarnings("unchecked")
            CheckBoxTreeItem<Path> c = (CheckBoxTreeItem<Path>) ch;
            expandSome(c, depth - 1);
        }
    }

    private void attachSelectionListeners(CheckBoxTreeItem<Path> rootItem) {
        if (rootItem == null) return;

        walk(rootItem, item -> item.selectedProperty().addListener((obs, oldV, newV) -> {
            if (bulkUpdating) return;

            Path p = item.getValue();
            if (p != null && Files.isRegularFile(p)) {
                selectionModel.setSelected(p, newV);
                refreshSelectedFilesUI();
                refreshContextStats();
            }
        }));
    }

    private void refreshSelectedFilesUI() {
        List<Path> selected = selectionModel.getSelectedFilesSorted();
        selectedFilesList.setAll(selected.stream()
                .map(p -> currentProjectRoot == null ? p.toString() : currentProjectRoot.relativize(p).toString())
                .toList());

        selectedCount.set(selectionModel.count());
    }

    private void buildContextPack() {
        if (currentProjectRoot == null) {
            setStatus(StatusKind.WARN, "Select a project folder first.");
            return;
        }
        try {
            setStatus(StatusKind.RUNNING, "Building context pack...");
            List<Path> selectedFiles = selectionModel.getSelectedFilesSorted();

            String prompt = promptArea.getText();
            String history = buildChatHistoryAddonIfEnabled();

            String pack = contextPackBuilder.build(currentProjectRoot, selectedFiles, prompt, history);

            lastBuiltContext = pack;
            contextPreviewArea.setText(pack);

            refreshContextStats();
            setStatus(StatusKind.OK, "Context pack ready.");
        } catch (Exception ex) {
            setStatus(StatusKind.ERROR, "Error: " + ex.getMessage());
        }
    }

    private String buildChatHistoryAddonIfEnabled() {
        ChatSession cur = currentChatProperty.get();
        if (cur == null) return null;
        if (!cur.includeHistory) return null;
        if (cur.revisions == null || cur.revisions.isEmpty()) return null;

        int n = cur.revisions.size();
        int from = Math.max(0, n - 5);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < n; i++) {
            ChatRevision r = cur.revisions.get(i);
            if (r == null) continue;

            sb.append("---- REVISION ").append(i + 1).append("/").append(n).append(" ----\n");
            sb.append("at: ").append(fmt(r.at)).append("\n");
            sb.append("model: ").append(r.model == null ? "" : r.model).append("\n");
            sb.append("prompt: ").append(r.userPrompt == null ? "" : r.userPrompt.strip()).append("\n");
            sb.append("ai_output:\n");
            sb.append(r.responseText == null ? "" : r.responseText.strip()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private void refreshContextStats() {
        List<Path> selected = selectionModel.getSelectedFilesSorted();
        int filesCount = selected.size();

        long totalBytes = 0;
        for (Path p : selected) {
            try {
                if (p != null && Files.isRegularFile(p)) totalBytes += Files.size(p);
            } catch (Exception ignored) {}
        }

        String ctx = contextPreviewArea.getText();
        if (ctx == null) ctx = "";
        int chars = ctx.length();

        long estTokens = estimateTokens(chars);

        if (detailsMode.get()) {
            contextStatsLabel.setText("Context: files=" + filesCount
                    + " bytes=" + humanBytes(totalBytes)
                    + " chars=" + chars
                    + " estTokens≈" + estTokens);
        } else {
            contextStatsLabel.setText("Context: " + filesCount + " files • " + humanBytes(totalBytes) + " • ~" + estTokens + " tokens");
        }
    }

    private long estimateTokens(int chars) {
        if (chars <= 0) return 0;
        return (chars + 3L) / 4L;
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }

    private void cancelRunningTask() {
        Task<OpenAIResult> t = runningTask;
        if (t == null) return;
        if (!t.isRunning()) return;

        t.cancel(true);
        setStatus(StatusKind.WARN, "Cancelled.");
        isRunning.set(false);

        lastErrorText = "Cancelled by user at " + fmt(LocalDateTime.now());
        refreshDebugUI();
    }

    private void sendToGpt() {
        if (isRunning.get()) return;
        if (currentChatProperty.get() == null) {
            setStatus(StatusKind.WARN, "Select or create a chat first.");
            return;
        }

        clearOutput();
        tokenLabel.setText("Tokens: in=? out=? total=?");
        setStatus(StatusKind.RUNNING, "Preparing request...");
        isRunning.set(true);

        final String model = (config.getModel() == null || config.getModel().isBlank()) ? "gpt-4.1" : config.getModel();
        final List<Path> selectedFiles = selectionModel.getSelectedFilesSorted();
        final String prompt = promptArea.getText();
        final boolean historyOn = currentChatProperty.get().includeHistory;

        lastModelUsed = model;
        lastErrorText = "";
        refreshDebugUI();

        Task<OpenAIResult> task = new Task<>() {
            @Override
            protected OpenAIResult call() throws Exception {
                if (isCancelled()) throw new InterruptedException("Cancelled.");

                openAIService.configure(config.getApiKey());

                Platform.runLater(() -> setStatus(StatusKind.RUNNING, "Building context pack..."));
                String history = historyOn ? buildChatHistoryAddonIfEnabled() : null;
                String pack = contextPackBuilder.build(currentProjectRoot, selectedFiles, prompt, history);
                Platform.runLater(() -> {
                    lastBuiltContext = pack;
                    contextPreviewArea.setText(pack);
                    refreshContextStats();
                });

                if (isCancelled()) throw new InterruptedException("Cancelled.");

                Platform.runLater(() -> setStatus(StatusKind.RUNNING, "Calling OpenAI..."));
                String request = PromptTemplates.buildRequest(pack);

                lastRequestText = request;
                Platform.runLater(() -> refreshDebugUI());

                return openAIService.generateDiff(model, request);
            }
        };

        runningTask = task;

        task.setOnSucceeded(e -> {
            OpenAIResult r = task.getValue();

            String inTok = (r == null || r.inputTokens() == null) ? "?" : r.inputTokens().toString();
            String outTok = (r == null || r.outputTokens() == null) ? "?" : r.outputTokens().toString();
            String totalTok = (r == null || r.totalTokens() == null) ? "?" : r.totalTokens().toString();
            tokenLabel.setText("Tokens: in=" + inTok + " out=" + outTok + " total=" + totalTok);

            String modelText = (r == null || r.diffText() == null) ? "" : r.diffText();
            lastResponseText = modelText;
            renderBlocks(modelText);

            saveRevisionToCurrentChat(r, model, prompt, historyOn);

            isRunning.set(false);
            runningTask = null;

            setStatus(StatusKind.OK, "Done.");
            refreshDebugUI("OK", inTok, outTok, totalTok);
            updateRevisionNavUI();
            refreshChatListFromStorePreserveSelection();
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = (ex == null ? "unknown" : ex.getMessage());
            if (msg == null || msg.isBlank()) msg = ex == null ? "unknown" : ex.getClass().getSimpleName();

            setStatus(StatusKind.ERROR, "OpenAI error: " + msg);
            isRunning.set(false);
            runningTask = null;

            lastErrorText = msg;
            refreshDebugUI();
        });

        task.setOnCancelled(e -> {
            setStatus(StatusKind.WARN, "Cancelled.");
            isRunning.set(false);
            runningTask = null;

            lastErrorText = "Cancelled by user at " + fmt(LocalDateTime.now());
            refreshDebugUI();
        });

        Thread t = new Thread(task, "openai-call");
        t.setDaemon(true);
        t.start();
    }

    private void saveRevisionToCurrentChat(OpenAIResult r, String model, String prompt, boolean historyOn) {
        ChatSession cur = currentChatProperty.get();
        if (cur == null) return;

        if (cur.revisions == null) cur.revisions = new java.util.ArrayList<>();

        ChatRevision rev = new ChatRevision();
        rev.at = OffsetDateTime.now();
        rev.model = model;
        rev.userPrompt = prompt;
        rev.historyIncluded = historyOn;
        rev.inputTokens = r == null ? null : r.inputTokens();
        rev.outputTokens = r == null ? null : r.outputTokens();
        rev.totalTokens = r == null ? null : r.totalTokens();
        rev.responseText = r == null ? "" : (r.diffText() == null ? "" : r.diffText());

        cur.revisions.add(rev);
        cur.currentRevisionIndex = cur.revisions.size() - 1;

        String t = deriveTitleFromPrompt(cur.promptText);
        if (t != null && !t.isBlank()) cur.title = t;

        chatStore.touch(cur);
        persistChatsSilently();
    }

    private void refreshDebugUI() {
        refreshDebugUI(null, null, null, null);
    }

    private void refreshDebugUI(String status, String inTok, String outTok, String totalTok) {
        StringBuilder meta = new StringBuilder();
        meta.append("Debug: ").append(fmt(LocalDateTime.now())).append("\n");
        if (lastModelUsed != null && !lastModelUsed.isBlank()) meta.append("Model: ").append(lastModelUsed).append("\n");

        if (status != null) meta.append("Status: ").append(status).append("\n");
        if (inTok != null || outTok != null || totalTok != null) {
            meta.append("Tokens: in=").append(inTok == null ? "?" : inTok)
                    .append(" out=").append(outTok == null ? "?" : outTok)
                    .append(" total=").append(totalTok == null ? "?" : totalTok)
                    .append("\n");
        }
        if (lastErrorText != null && !lastErrorText.isBlank()) meta.append("Error: ").append(lastErrorText).append("\n");

        int reqChars = (lastRequestText == null) ? 0 : lastRequestText.length();
        int resChars = (lastResponseText == null) ? 0 : lastResponseText.length();
        meta.append("Request chars: ").append(reqChars).append(" (estTokens≈").append(estimateTokens(reqChars)).append(")\n");
        meta.append("Response chars: ").append(resChars).append("\n");

        debugMetaLabel.setText(meta.toString());
        debugRequestArea.setText(lastRequestText == null ? "" : lastRequestText);
        debugResponseArea.setText(lastResponseText == null ? "" : lastResponseText);
    }

    private void renderBlocks(String modelText) {
        blockItems.clear();

        List<OutputBlock> blocks = outputParser.parse(modelText);

        if (blocks.isEmpty()) {
            OutputBlock raw = new OutputBlock();
            raw.file = "model-output.txt";
            raw.action = "RAW";
            raw.code = modelText == null ? "" : modelText;
            blockItems.add(raw);
        } else {
            blockItems.addAll(blocks);
        }

        blockListView.setItems(blockItems);
        blockListView.getSelectionModel().select(0);
        currentBlockIndex = 0;
    }

    private void copyTextToClipboard(String text, String msg) {
        if (text == null || text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(StatusKind.OK, msg);
    }

    private void walk(CheckBoxTreeItem<Path> node, java.util.function.Consumer<CheckBoxTreeItem<Path>> action) {
        action.accept(node);
        for (var child : node.getChildren()) {
            @SuppressWarnings("unchecked")
            CheckBoxTreeItem<Path> c = (CheckBoxTreeItem<Path>) child;
            walk(c, action);
        }
    }

    private String prettyName(Path p) {
        Path name = p.getFileName();
        if (name == null) return p.toString();
        return name.toString();
    }

    private String guessLanguage(String file) {
        String f = file == null ? "" : file.toLowerCase(Locale.ROOT);
        if (f.endsWith(".java")) return "java";
        if (f.endsWith(".kt") || f.endsWith(".kts")) return "kotlin";
        if (f.endsWith(".py")) return "python";
        if (f.endsWith(".js")) return "javascript";
        if (f.endsWith(".ts")) return "typescript";
        if (f.endsWith(".json")) return "json";
        if (f.endsWith(".xml")) return "xml";
        if (f.endsWith(".html")) return "html";
        if (f.endsWith(".css")) return "css";
        if (f.endsWith(".md")) return "markdown";
        return "plaintext";
    }

    private String inlineStylesheet() {
        String css = """
                .root {
                    -fx-background-color: #0f1115;
                    -fx-font-family: "Segoe UI", "Inter", "Arial";
                    -fx-font-size: 13px;
                }

                .panel {
                    -fx-background-radius: 16;
                    -fx-border-radius: 16;
                    -fx-border-color: rgba(255,255,255,0.10);
                    -fx-background-color: rgba(255,255,255,0.05);
                }

                .panel-subtle {
                    -fx-background-radius: 16;
                    -fx-border-radius: 16;
                    -fx-border-color: rgba(255,255,255,0.08);
                    -fx-background-color: rgba(255,255,255,0.03);
                }

                .section-title {
                    -fx-font-weight: 700;
                    -fx-font-size: 13px;
                }

                .hint { -fx-opacity: 0.88; }
                .flow-hint { -fx-font-weight: 600; }

                .mono {
                    -fx-font-family: "Consolas";
                    -fx-font-size: 12px;
                }

                .block-list {
                    -fx-font-family: "Consolas";
                    -fx-font-size: 12px;
                }

                .btn-primary {
                    -fx-background-radius: 12;
                    -fx-font-weight: 700;
                    -fx-background-color: #3b82f6;
                    -fx-text-fill: white;
                    -fx-padding: 8 14 8 14;
                }

                .btn-secondary {
                    -fx-background-radius: 12;
                    -fx-font-weight: 600;
                    -fx-background-color: rgba(255,255,255,0.10);
                    -fx-text-fill: white;
                    -fx-padding: 8 14 8 14;
                }

                .btn-ghost {
                    -fx-background-radius: 12;
                    -fx-font-weight: 600;
                    -fx-background-color: transparent;
                    -fx-border-color: rgba(255,255,255,0.16);
                    -fx-border-radius: 12;
                    -fx-text-fill: white;
                    -fx-padding: 6 10 6 10;
                }

                .btn-danger {
                    -fx-background-radius: 12;
                    -fx-font-weight: 700;
                    -fx-background-color: #ef4444;
                    -fx-text-fill: white;
                    -fx-padding: 8 14 8 14;
                }

                .btn-toggle {
                    -fx-background-radius: 12;
                    -fx-font-weight: 700;
                    -fx-background-color: rgba(255,255,255,0.08);
                    -fx-text-fill: white;
                    -fx-padding: 8 12 8 12;
                }
                .btn-toggle:selected {
                    -fx-background-color: rgba(59,130,246,0.45);
                }

                .banner-warn {
                    -fx-background-radius: 14;
                    -fx-padding: 10 12 10 12;
                    -fx-background-color: rgba(245,158,11,0.16);
                    -fx-border-color: rgba(245,158,11,0.35);
                    -fx-border-radius: 14;
                }

                .empty-state {
                    -fx-background-radius: 14;
                    -fx-background-color: rgba(255,255,255,0.03);
                    -fx-border-color: rgba(255,255,255,0.08);
                    -fx-border-radius: 14;
                }
                .empty-title {
                    -fx-font-weight: 800;
                    -fx-font-size: 13px;
                }
                .empty-body { -fx-opacity: 0.9; }

                .status-label:running { -fx-text-fill: #93c5fd; }
                .status-label:ok { -fx-text-fill: #86efac; }
                .status-label:warn { -fx-text-fill: #fcd34d; }
                .status-label:error { -fx-text-fill: #fca5a5; }
                """;

        return "data:text/css," + css
                .replace("\n", "%0A")
                .replace(" ", "%20")
                .replace("#", "%23");
    }

    private String extractHeaderValue(String text, String key) {
        if (text == null) return "";
        int i = text.indexOf(key);
        if (i < 0) return "";
        int start = i + key.length();
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
