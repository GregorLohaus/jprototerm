package com.gregor.jprototerm;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main extends Application {
    private TerminalWorkspace workspace;
    private TerminalCanvasView terminalView;
    private AppConfig config;

    @Override
    public void start(Stage stage) {
        config = AppConfig.load();

        workspace = new TerminalWorkspace(config);
        terminalView = new TerminalCanvasView(workspace, config);

        StackPane root = new StackPane(terminalView.canvas());
        terminalView.canvas().widthProperty().bind(root.widthProperty());
        terminalView.canvas().heightProperty().bind(root.heightProperty());

        Scene scene = new Scene(root, config.windowWidth(), config.windowHeight());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handlePressed);
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
        terminalView.canvas().requestFocus();
    }

    private void handlePressed(KeyEvent event) {
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
        } else if (config.keybindings().get("new_floating").matches(event)) {
            workspace.createFloatingPane();
            event.consume();
        } else if (config.keybindings().get("next_floating").matches(event)) {
            workspace.nextFloatingPane();
            event.consume();
        } else if (config.keybindings().get("close_pane").matches(event)) {
            workspace.closeActivePane();
            event.consume();
        } else if (config.keybindings().get("open_font_selector").matches(event)) {
            openFontSelector();
            event.consume();
        } else if (config.keybindings().get("open_scrollback").matches(event)) {
            openScrollbackInEditor();
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

    private void openFontSelector() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Font");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<String> family = new ComboBox<>();
        family.getItems().setAll(Font.getFamilies());
        family.setEditable(true);
        family.setMaxWidth(Double.MAX_VALUE);
        family.setValue(config.fontFamily());

        Spinner<Double> size = new Spinner<>();
        size.setEditable(true);
        size.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(6.0, 48.0, config.fontSize(), 0.5));

        GridPane content = new GridPane();
        content.setHgap(10.0);
        content.setVgap(10.0);
        content.add(new Label("Family"), 0, 0);
        content.add(family, 1, 0);
        content.add(new Label("Size"), 0, 1);
        content.add(size, 1, 1);
        dialog.getDialogPane().setContent(content);

        dialog.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .ifPresent(ignored -> {
                    String selectedFamily = family.getEditor().getText();
                    if (selectedFamily == null || selectedFamily.isBlank()) {
                        selectedFamily = family.getValue();
                    }
                    if (selectedFamily == null || selectedFamily.isBlank()) {
                        return;
                    }

                    double selectedSize = size.getValue();
                    config = config.withFont(selectedFamily.trim(), selectedSize);
                    config.save();
                    terminalView.setFont(config.fontFamily(), config.fontSize());
                    terminalView.canvas().requestFocus();
                });
    }

    private void openScrollbackInEditor() {
        try {
            Path file = Files.createTempFile("jprototerm-scrollback-", ".txt");
            Files.writeString(file, workspace.activePane().scrollbackText());
            file.toFile().deleteOnExit();

            workspace.activePane().send(scrollbackEditorCommand(file) + "\r");
        } catch (IOException ex) {
            System.err.println("Could not open scrollback in editor: " + ex.getMessage());
        }
    }

    private String scrollbackEditorCommand(Path file) {
        String quotedFile = shellQuote(file.toString());
        String command = config.scrollbackEditorCommand();
        if (command == null || command.isBlank()) {
            command = "vi {file}";
        }
        if (command.contains("{file}")) {
            return command.replace("{file}", quotedFile);
        }
        return command + " " + quotedFile;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static void main(String[] args) {
        System.setProperty("prism.order", System.getProperty("prism.order", "es2,sw"));
        launch(Main.class, args);
    }
}
