package com.gregor.jprototerm;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
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
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main extends Application {
    private Compositor compositor;
    private TerminalMetrics metrics;
    private AppConfig config;

    @Override
    public void start(Stage stage) {
        config = AppConfig.load();

        metrics = new TerminalMetrics(config.fontFamily(), config.fontSize());
        compositor = new Compositor(config, metrics);

        StackPane root = new StackPane(compositor.canvas(), compositor.imageOverlay());
        compositor.canvas().widthProperty().bind(root.widthProperty());
        compositor.canvas().heightProperty().bind(root.heightProperty());

        Scene scene = new Scene(root, config.windowWidth(), config.windowHeight());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handlePressed);
        scene.addEventFilter(KeyEvent.KEY_TYPED, event -> handleTyped(event));

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                compositor.render();
            }
        }.start();

        stage.setTitle("jprototerm");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            compositor.close();
        });
        // JavaFX centres a new stage on the primary screen; on X11 there's no "focused monitor"
        // to honour, so place it on the screen under the mouse pointer instead.
        centreOnActiveScreen(stage, config.windowWidth(), config.windowHeight());
        stage.show();
        compositor.canvas().requestFocus();
    }

    // Centre the stage within the screen the mouse pointer is on (the best proxy for the
    // "active" monitor on X11, which exposes no focused-monitor concept to JavaFX).
    private static void centreOnActiveScreen(Stage stage, double width, double height) {
        Rectangle2D bounds = activeScreen().getVisualBounds();
        stage.setX(bounds.getMinX() + ((bounds.getWidth() - width) / 2.0));
        stage.setY(bounds.getMinY() + ((bounds.getHeight() - height) / 2.0));
    }

    private static Screen activeScreen() {
        try {
            // AWT is the only way to read the pointer location before any window is shown;
            // its coordinate space matches JavaFX's on the X11 virtual screen.
            java.awt.PointerInfo pointer = java.awt.MouseInfo.getPointerInfo();
            if (pointer != null) {
                java.awt.Point at = pointer.getLocation();
                List<Screen> screens = Screen.getScreensForRectangle(at.x, at.y, 1.0, 1.0);
                if (!screens.isEmpty()) {
                    return screens.get(0);
                }
            }
        } catch (Throwable ignored) {
            // Headless or AWT unavailable — fall back to the primary screen.
        }
        return Screen.getPrimary();
    }

    private void handlePressed(KeyEvent event) {
        if (config.keybindings().get("navigate_left").matches(event)) {
            compositor.navigate(Direction.LEFT);
            event.consume();
        } else if (config.keybindings().get("navigate_down").matches(event)) {
            compositor.navigate(Direction.DOWN);
            event.consume();
        } else if (config.keybindings().get("navigate_up").matches(event)) {
            compositor.navigate(Direction.UP);
            event.consume();
        } else if (config.keybindings().get("navigate_right").matches(event)) {
            compositor.navigate(Direction.RIGHT);
            event.consume();
        } else if (config.keybindings().get("toggle_floating").matches(event)) {
            compositor.toggleFloating();
            event.consume();
        } else if (config.keybindings().get("new_pane").matches(event)) {
            compositor.createPane();
            event.consume();
        } else if (config.keybindings().get("next_floating").matches(event)) {
            compositor.nextFloatingPane();
            event.consume();
        } else if (config.keybindings().get("close_pane").matches(event)) {
            compositor.closeActivePane();
            event.consume();
            if (compositor.isEmpty()) {
                // Closing the last pane quits the app.
                compositor.close();
                Platform.exit();
            }
        } else if (config.keybindings().get("new_tab").matches(event)) {
            compositor.newTab();
            event.consume();
        } else if (config.keybindings().get("previous_tab").matches(event)) {
            compositor.previousTab();
            event.consume();
        } else if (config.keybindings().get("next_tab").matches(event)) {
            compositor.nextTab();
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
                compositor.activePane().send(encoded);
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
            compositor.activePane().send(text);
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
                    compositor.setFont(config.fontFamily(), config.fontSize());
                    compositor.canvas().requestFocus();
                });
    }

    private void openScrollbackInEditor() {
        try {
            // Capture the active pane's scrollback before opening the floating pane, since that
            // makes the new pane active.
            Path file = Files.createTempFile("jprototerm-scrollback-", ".txt");
            Files.writeString(file, compositor.activePane().scrollbackText());
            file.toFile().deleteOnExit();

            TerminalPane pane = compositor.openFloatingPane();
            if (pane != null) {
                pane.send(scrollbackEditorCommand(file) + "\r");
            }
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
