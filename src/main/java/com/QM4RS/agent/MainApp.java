package com.QM4RS.agent;

import atlantafx.base.theme.PrimerDark;
import com.QM4RS.agent.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        MainWindow window = new MainWindow();
        window.show(primaryStage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
