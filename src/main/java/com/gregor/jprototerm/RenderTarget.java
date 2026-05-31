package com.gregor.jprototerm;

import dev.jlibghostty.KittyGraphics;
import dev.jlibghostty.RenderStateSnapshot;
import javafx.scene.shape.Shape;

import java.util.Optional;

/**
 * The read-only view of a pane that a {@link TerminalRenderer} draws: its on-screen rect, its
 * current render snapshot, and its kitty-graphics state. Decoupling the renderer from
 * {@link TerminalPane} through this interface lets the renderer be swapped (e.g. a debug
 * renderer that just outlines bounds and clip bands) and unit-tested against a synthetic
 * target without a real terminal.
 */
interface RenderTarget {
    double x();

    double y();

    double width();

    double height();

    /** Whether kitty graphics should be drawn for this target at all. */
    boolean kittyEnabled();

    Optional<KittyGraphics> kittyGraphics();

    /**
     * Incremental snapshot: only rows that changed since the last frame are populated. May be
     * {@code null} before the first snapshot exists.
     */
    RenderStateSnapshot snapshot();

    /** Full snapshot with every row populated, regardless of dirty state. */
    RenderStateSnapshot snapshotFull();

    /**
     * The region this target may draw into, or {@code null} to clip to its plain rect. Set at
     * layout time (a tiled pane gets its rect minus the floating panes that cover it), so the
     * renderer can clip its own output and never paint over a pane on top.
     */
    Shape clip();
}
