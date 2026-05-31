package com.gregor.jprototerm;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;

/**
 * JavaFX node for one terminal pane. It is intentionally a thin adapter: the terminal model
 * still provides snapshots and the existing renderer still draws the cell grid, but drawing is
 * now local to this node's own canvas instead of a shared compositor canvas.
 */
final class TerminalPaneNode extends Region {
    private final TerminalPane pane;
    private final Canvas canvas = new Canvas();
    private long drawnContentVersion = Long.MIN_VALUE;
    private double drawnWidth = -1.0;
    private double drawnHeight = -1.0;

    TerminalPaneNode(TerminalPane pane) {
        this.pane = pane;
        setPickOnBounds(true);
        getChildren().add(canvas);
    }

    void discard() {
        drawnContentVersion = Long.MIN_VALUE;
        drawnWidth = -1.0;
        drawnHeight = -1.0;
    }

    void renderFull(boolean active) {
        prepareCanvas();
        paint(active, true);
    }

    void renderIncremental(boolean active) {
        if (drawnContentVersion == Long.MIN_VALUE || prepareCanvas()) {
            paint(active, true);
            return;
        }
        if (drawnContentVersion == pane.contentVersion()) {
            return;
        }
        paint(active, false);
    }

    private boolean prepareCanvas() {
        boolean changed = canvas.getWidth() != pane.width() || canvas.getHeight() != pane.height();
        if (changed) {
            canvas.setWidth(Math.max(0.0, pane.width()));
            canvas.setHeight(Math.max(0.0, pane.height()));
            drawnWidth = pane.width();
            drawnHeight = pane.height();
            drawnContentVersion = Long.MIN_VALUE;
        }
        return changed || drawnWidth != pane.width() || drawnHeight != pane.height();
    }

    private void paint(boolean active, boolean full) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.save();
        gc.translate(-pane.x(), -pane.y());
        if (full) {
            pane.paintFull(gc, active);
        } else {
            pane.paintIncremental(gc, active);
        }
        gc.restore();
        drawnContentVersion = pane.contentVersion();
        drawnWidth = pane.width();
        drawnHeight = pane.height();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0.0, 0.0);
    }
}
