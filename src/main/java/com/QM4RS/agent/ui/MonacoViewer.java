package com.QM4RS.agent.ui;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.net.URL;
import java.util.Objects;

/**
 * Monaco editor wrapper for JavaFX WebView.
 * Fixes responsiveness by calling editor.layout() on size changes (and split pane divider drags).
 */
public class MonacoViewer extends Region {

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();

    private volatile boolean loaded = false;

    // simple debounce for layout calls (avoid spamming JS on drag-resize)
    private long lastLayoutRequestNanos = 0L;
    private static final long LAYOUT_DEBOUNCE_NS = 35_000_000L; // ~35ms

    public MonacoViewer() {
        getChildren().add(webView);

        // Make WebView follow this Region's size (CRITICAL for responsive)
        webView.setContextMenuEnabled(false);

        // Load monaco html from resources
        URL url = MonacoViewer.class.getResource("/monaco/monaco.html");
        if (url == null) {
            // keep empty; prevents NPE and lets app run with a blank view
            System.err.println("[MonacoViewer] Could not find /monaco/monaco.html in resources.");
        } else {
            engine.load(url.toExternalForm());
        }

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                loaded = true;
                // Initial layout after load
                requestEditorLayout();
            }
        });

        // When this Region resizes -> force Monaco layout
        widthProperty().addListener((o, a, b) -> requestEditorLayout());
        heightProperty().addListener((o, a, b) -> requestEditorLayout());
    }

    // ----- Public API used by MainWindow -----

    public void setTheme(String theme) {
        if (theme == null) theme = "vs-dark";
        final String t = theme;
        runWhenReady(() -> jsVoid("try{ if(window.setTheme) setTheme(" + jsString(t) + "); }catch(e){}"));
    }

    public void setContent(String text, String language) {
        final String content = (text == null) ? "" : text;
        final String lang = (language == null || language.isBlank()) ? "plaintext" : language;

        runWhenReady(() -> {
            // Prefer functions exposed by monaco.html; fallback to direct editor usage.
            jsVoid("""
                    try{
                      if(window.setContent){
                        setContent(%s, %s);
                      }else if(window.editor){
                        window.editor.setValue(%s);
                        if(window.monaco && window.monaco.editor){
                          // language may need model; try best-effort
                          var m = window.editor.getModel();
                          if(m && window.monaco.editor.setModelLanguage){
                            window.monaco.editor.setModelLanguage(m, %s);
                          }
                        }
                      }
                    }catch(e){}
                    """.formatted(
                    jsString(content), jsString(lang),
                    jsString(content), jsString(lang)
            ));

            requestEditorLayout();
        });
    }

    public String getContentNow() {
        if (!loaded) return "";
        try {
            Object r = engine.executeScript("try{ (window.getContent?getContent(): (window.editor?window.editor.getValue():'')) }catch(e){''}");
            return Objects.toString(r, "");
        } catch (Exception e) {
            return "";
        }
    }

    public String getSelectedTextNow() {
        if (!loaded) return "";
        try {
            Object r = engine.executeScript("try{ (window.getSelectedText?getSelectedText(): '') }catch(e){''}");
            return Objects.toString(r, "");
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isFocusInside() {
        // WebView is the interactive part; focus may be inside its child nodes
        return webView.isFocused() || isDescendantFocused(webView);
    }

    // ----- Layout / resizing -----

    @Override
    protected void layoutChildren() {
        // Ensure WebView always fills the Region
        final double w = getWidth();
        final double h = getHeight();
        webView.resizeRelocate(0, 0, Math.max(0, w), Math.max(0, h));

        // Force monaco to recompute after container layout changes
        requestEditorLayout();
    }

    private void requestEditorLayout() {
        if (!loaded) return;

        long now = System.nanoTime();
        if (now - lastLayoutRequestNanos < LAYOUT_DEBOUNCE_NS) return;
        lastLayoutRequestNanos = now;

        Platform.runLater(() -> {
            if (!loaded) return;
            // Try multiple ways depending on monaco.html implementation
            jsVoid("""
                    try{
                      if(window.__monaco_layout){ window.__monaco_layout(); }
                      else if(window.editor && window.editor.layout){ window.editor.layout(); }
                      else if(window.monaco && window.monaco.editor && window.monaco.editor.getEditors){
                        var eds = window.monaco.editor.getEditors();
                        if(eds && eds.length && eds[0].layout) eds[0].layout();
                      }
                    }catch(e){}
                    """);
        });
    }

    // ----- Helpers -----

    private void runWhenReady(Runnable r) {
        if (loaded) {
            Platform.runLater(r);
            return;
        }
        // If not loaded yet, schedule after load; simplest: poll once after a short delay
        Platform.runLater(() -> {
            if (loaded) r.run();
        });
    }

    private void jsVoid(String js) {
        try {
            engine.executeScript(js);
        } catch (Exception ignored) {
        }
    }

    private static boolean isDescendantFocused(Node node) {
        if (node == null) return false;
        if (node.isFocused()) return true;
        if (node instanceof javafx.scene.Parent p) {
            for (Node ch : p.getChildrenUnmodifiable()) {
                if (isDescendantFocused(ch)) return true;
            }
        }
        return false;
    }

    private static String jsString(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) sb.append("\\u").append(String.format("%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('\'');
        return sb.toString();
    }
}
