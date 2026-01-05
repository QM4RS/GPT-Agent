package com.QM4RS.agent.ui;

import com.QM4RS.agent.core.AppConfig;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.util.Optional;

public class SettingsDialog {

    public Optional<AppConfig> show(AppConfig current) {
        Dialog<AppConfig> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("OpenAI Configuration (stored locally)");

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText("sk-...");
        apiKeyField.setText(current == null ? "" : current.getApiKey());

        TextField modelField = new TextField();
        modelField.setPromptText("e.g. gpt-4.1");
        modelField.setText(current == null ? "gpt-4.1" : current.getModel());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        grid.add(new Label("API Key:"), 0, 0);
        grid.add(apiKeyField, 1, 0);

        grid.add(new Label("Model:"), 0, 1);
        grid.add(modelField, 1, 1);

        dialog.getDialogPane().setContent(grid);

        // Validation: disable Save if API key empty
        dialog.getDialogPane().lookupButton(saveBtn).disableProperty().bind(apiKeyField.textProperty().isEmpty());

        dialog.setResultConverter(btn -> {
            if (btn == saveBtn) {
                AppConfig cfg = new AppConfig();
                cfg.setApiKey(apiKeyField.getText());
                cfg.setModel(modelField.getText());
                return cfg;
            }
            return null;
        });

        return dialog.showAndWait();
    }
}
