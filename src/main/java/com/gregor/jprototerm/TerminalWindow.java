package com.gregor.jprototerm;

import javafx.animation.AnimationTimer;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.Clipboard;
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

/**
 * One top-level terminal window: its own {@link Stage}, {@link Compositor}, config/metrics, render
 * loop and input handling. Many of these live in a single JVM under a {@link WindowManager}; closing
 * one tears down only that window (its shells, its Stage) and leaves the rest — and, in daemon mode,
 * the JVM — running. Built on the FX thread.
 */
final class TerminalWindow {
    private final WindowManager manager;
    private final TerminalMetrics metrics;
    private final Compositor compositor;
    private final Stage stage;
    private final AnimationTimer renderLoop;
    private AppConfig config;
    private boolean closed;

    TerminalWindow(WindowManager manager, String workingDirectory) {
        this.manager = manager;
        // Each window loads config independently, so edits (and per-window font changes) apply to
        // newly opened windows without disturbing existing ones.
        config = AppConfig.load();
        StartupTiming.mark("config loaded");
        metrics = new TerminalMetrics(config.fontFamily(), config.fontSize());
        StartupTiming.mark("fonts loaded");
        compositor = new Compositor(config, metrics, workingDirectory);
        StartupTiming.mark("compositor ready");

        // The last pane closing closes this window (not the JVM); see teardown().
        compositor.setOnEmpty(this::teardown);

        StackPane root = new StackPane(compositor.canvas(), compositor.imageOverlay());
        compositor.canvas().widthProperty().bind(root.widthProperty());
        compositor.canvas().heightProperty().bind(root.heightProperty());

        Scene scene = new Scene(root, config.windowWidth(), config.windowHeight());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handlePressed);
        scene.addEventFilter(KeyEvent.KEY_TYPED, this::handleTyped);

        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                compositor.render();
                StartupTiming.firstFrame();
            }
        };
        renderLoop.start();

        stage = new Stage();
        stage.setTitle("jprototerm");
        stage.setScene(scene);
        // The X11 WM close button: tear this window down explicitly. With implicit exit disabled
        // (see WindowManager) nothing else would reap the shells or drop the window otherwise.
        stage.setOnCloseRequest(event -> teardown());
        centreOnActiveScreen(stage, config.windowWidth(), config.windowHeight());
        stage.show();
        StartupTiming.mark("stage shown");
        // Ask the window manager to raise and focus the new window so the user can type right
        // away; the canvas requestFocus() below only routes events within the scene.
        stage.toFront();
        stage.requestFocus();
        compositor.canvas().requestFocus();
    }

    /**
     * Fully tears this window down (FX thread, idempotent): stops rendering, closes the compositor —
     * which reaps the pane shells via the configured {@code close_signal} — disposes the Stage, and
     * notifies the manager so it can drop the window (and, in standalone mode, exit the JVM). Both
     * the WM close button and the last-pane-closed hook route through here.
     */
    void teardown() {
        if (closed) {
            return;
        }
        closed = true;
        renderLoop.stop();
        compositor.close();
        stage.close();
        manager.onWindowClosed(this);
    }

    /** Signals and reaps this window's shell processes without touching render state (off-FX safe). */
    void terminateSessions() {
        compositor.terminateSessions();
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
        } else if (config.keybindings().get("promote_floating").matches(event)) {
            compositor.promoteActiveFloating();
            event.consume();
        } else if (config.keybindings().get("close_pane").matches(event)) {
            // Closing the last pane closes this window, via the compositor's onEmpty hook.
            compositor.closeActivePane();
            event.consume();
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
        } else if (config.keybindings().get("paste").matches(event)) {
            pasteFromClipboard();
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

    private void pasteFromClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            compositor.activePane().paste(clipboard.getString());
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

            // Run the editor as the floating pane's process (via /bin/sh -c) rather than typing the
            // command into an interactive shell. The command runs deterministically from the start
            // — no shell startup/rc race — and the pane auto-closes when the editor exits.
            compositor.openFloatingPane(scrollbackEditorCommand(file));
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

    // Centre the stage within the screen the mouse pointer is on (the best proxy for the
    // "active" monitor on X11, which exposes no focused-monitor concept to JavaFX).
    private static void centreOnActiveScreen(Stage stage, double width, double height) {
        Rectangle2D bounds = activeScreen().getVisualBounds();
        stage.setX(bounds.getMinX() + ((bounds.getWidth() - width) / 2.0));
        stage.setY(bounds.getMinY() + ((bounds.getHeight() - height) / 2.0));
    }

    private static Screen activeScreen() {
        int[] at = X11Pointer.query();
        if (at != null) {
            // libX11 and JavaFX share a coordinate space on the X11 virtual screen.
            List<Screen> screens = Screen.getScreensForRectangle(at[0], at[1], 1.0, 1.0);
            if (!screens.isEmpty()) {
                return screens.get(0);
            }
        }
        return Screen.getPrimary();
    }
}
