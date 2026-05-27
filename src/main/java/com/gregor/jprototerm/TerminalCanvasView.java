package com.gregor.jprototerm;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;

public final class TerminalCanvasView {
    private final Canvas canvas = new Canvas();
    private final TerminalWorkspace workspace;
    private final AppConfig config;

    public TerminalCanvasView(TerminalWorkspace workspace, AppConfig config) {
        this.workspace = workspace;
        this.config = config;
        canvas.setFocusTraversable(true);
    }

    public Canvas canvas() {
        return canvas;
    }

    public void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        workspace.layout(width, height);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.rgb(16, 16, 18));
        gc.fillRect(0, 0, width, height);
        gc.setFontSmoothingType(FontSmoothingType.GRAY);

        for (TerminalPane pane : workspace.panes()) {
            drawPane(gc, pane);
        }
    }

    private void drawPane(GraphicsContext gc, TerminalPane pane) {
        gc.save();
        gc.beginPath();
        gc.rect(pane.x(), pane.y(), pane.width(), pane.height());
        gc.clip();

        if (pane.floating()) {
            gc.setGlobalAlpha(0.96);
        }
        gc.setFill(Color.rgb(9, 10, 12));
        gc.fillRect(pane.x(), pane.y(), pane.width(), pane.height());
        gc.setGlobalAlpha(1.0);

        gc.setStroke(workspace.isActive(pane) ? Color.rgb(87, 166, 255) : Color.rgb(52, 57, 65));
        gc.setLineWidth(workspace.isActive(pane) ? 2.0 : 1.0);
        gc.strokeRect(pane.x() + 0.5, pane.y() + 0.5, pane.width() - 1.0, pane.height() - 1.0);

        Font font = Font.font(config.fontFamily(), config.fontSize());
        gc.setFont(font);
        gc.setFill(Color.rgb(225, 229, 235));

        double lineHeight = Math.ceil(config.fontSize() * 1.35);
        double left = pane.x() + 12.0;
        double baseline = pane.y() + 18.0;
        int maxLines = Math.max(1, (int) ((pane.height() - 24.0) / lineHeight));

        String[] lines = pane.snapshotText().split("\\R", -1);
        int start = Math.max(0, lines.length - maxLines);
        for (int i = start; i < lines.length; i++) {
            gc.fillText(lines[i], left, baseline + ((i - start) * lineHeight));
        }

        pane.graphicsRegistry().draw(gc, pane.x() + 12.0, pane.y() + 12.0, config.fontSize() * 0.62, lineHeight);
        gc.restore();
    }
}
