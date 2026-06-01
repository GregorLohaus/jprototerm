package com.gregor.jprototerm;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Shape;

/**
 * Draws a {@link RenderTarget} onto a JavaFX canvas. The {@link Compositor} owns positioning
 * and z-order; a renderer only fills the target's rect, clipped to the target's {@link
 * RenderTarget#clip() clip region} so a repaint can never bleed over a pane on top.
 * Implementations can change the look entirely — {@link GhosttyTerminalRenderer} is the real
 * terminal renderer; a debug renderer could outline pane bounds instead.
 *
 * <p>A renderer may hold per-target state (e.g. a decoded-image cache), so an instance belongs
 * to a single {@link TerminalPane}.
 */
abstract class TerminalRenderer {
    /** Paint the whole target into its rect, clipped to its clip region. */
    abstract void paintFull(GraphicsContext gc, RenderTarget target, boolean active);

    /** Repaint only what changed since the last frame, clipped to the target's clip region. */
    abstract void paintIncremental(GraphicsContext gc, RenderTarget target, boolean active);

    /**
     * The kitty image placements produced by the most recent paint, for the compositor to render
     * as overlay nodes above the canvas. Empty unless the last paint found visible images.
     */
    java.util.List<KittyImageNode> kittyImages() {
        return java.util.List.of();
    }

    protected static void clipRect(GraphicsContext gc, double x, double y, double width, double height) {
        gc.beginPath();
        gc.rect(x, y, width, height);
        gc.clip();
    }

    /**
     * Clip to {@code region} if given (the pane's rect minus the panes covering it, computed by
     * {@code Shape.subtract} at layout), otherwise to the plain rect. The region is a rectilinear
     * path, so it replays onto the canvas as move/line/close segments.
     */
    protected static void clip(GraphicsContext gc, double x, double y, double width, double height, Shape region) {
        if (region == null) {
            clipRect(gc, x, y, width, height);
            return;
        }
        var elements = ((Path) region).getElements();
        gc.beginPath();
        if (elements.isEmpty()) {
            gc.rect(x, y, 0.0, 0.0); // fully covered: clip to nothing
        }
        for (PathElement element : elements) {
            if (element instanceof MoveTo moveTo) {
                gc.moveTo(moveTo.getX(), moveTo.getY());
            } else if (element instanceof LineTo lineTo) {
                gc.lineTo(lineTo.getX(), lineTo.getY());
            } else if (element instanceof ClosePath) {
                gc.closePath();
            }
        }
        gc.clip();
    }
}
