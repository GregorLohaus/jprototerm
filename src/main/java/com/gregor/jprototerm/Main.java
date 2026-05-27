package com.gregor.jprototerm;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public final class Main extends Application {
    private TerminalWorkspace workspace;

    @Override
    public void start(Stage stage) {
        AppConfig config = AppConfig.load();

        workspace = new TerminalWorkspace(config);
        TerminalCanvasView terminalView = new TerminalCanvasView(workspace, config);

        StackPane root = new StackPane(terminalView.canvas());
        terminalView.canvas().widthProperty().bind(root.widthProperty());
        terminalView.canvas().heightProperty().bind(root.heightProperty());

        Scene scene = new Scene(root, config.windowWidth(), config.windowHeight());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> handlePressed(config, event));
        scene.addEventFilter(KeyEvent.KEY_TYPED, event -> handleTyped(event));

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                terminalView.render();
            }
        }.start();

        stage.setTitle("jprototerm");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            workspace.close();
        });
        stage.show();
    }

    private void handlePressed(AppConfig config, KeyEvent event) {
        if (config.keybindings().get("navigate_left").matches(event)) {
            workspace.navigate(Direction.LEFT);
            event.consume();
        } else if (config.keybindings().get("navigate_down").matches(event)) {
            workspace.navigate(Direction.DOWN);
            event.consume();
        } else if (config.keybindings().get("navigate_up").matches(event)) {
            workspace.navigate(Direction.UP);
            event.consume();
        } else if (config.keybindings().get("navigate_right").matches(event)) {
            workspace.navigate(Direction.RIGHT);
            event.consume();
        } else if (config.keybindings().get("toggle_floating").matches(event)) {
            workspace.toggleFloating();
            event.consume();
        } else {
            String encoded = KeyEncoder.encode(event);
            if (encoded != null) {
                workspace.activePane().send(encoded);
                event.consume();
            }
        }
    }

    private void handleTyped(KeyEvent event) {
        if (event.isAltDown() || event.isControlDown() || event.isMetaDown()) {
            return;
        }

        String text = event.getCharacter();
        if (text != null && !text.isEmpty() && text.charAt(0) >= 0x20 && text.charAt(0) != 0x7f) {
            workspace.activePane().send(text);
            event.consume();
        }
    }

    public static void main(String[] args) {
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.verbose", "true");
        launch(Main.class, args);
    }
}
