package com.gregor.jprototerm;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Cell geometry shared by the {@link Compositor} and every {@link TerminalPane}.
 *
 * <p>The nominal cell width/height come from measuring the font, but a grid can't use
 * fractional cells, so the measured size is snapped to whole (logical) pixels here — that
 * snapping is why the value isn't purely a property of the font. The compositor owns the
 * single instance (it holds the canvas, which is the pixel context), hands it to panes so
 * they can turn their rect into a column/row count themselves, and re-measures it on a font
 * change so every pane observes the new geometry through the shared reference.
 */
public final class TerminalMetrics {
    /** Inset, in pixels, between a pane's edge and its content on every side. */
    public static final double PADDING = 12.0;

    private String fontFamily;
    private double fontSize;
    private Font font;
    private double cellWidth;
    private double lineHeight;
    private double baselineOffset;
    // One rasterized-glyph atlas per window, shared by every pane's renderer (the masks are a pure
    // function of the font geometry below). It self-invalidates when these metrics change.
    private final GlyphCache glyphCache = new GlyphCache(this);

    public TerminalMetrics(String fontFamily, double fontSize) {
        setFont(fontFamily, fontSize);
    }

    public void setFont(String fontFamily, double fontSize) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.font = Font.font(fontFamily, fontSize);
        measure(font);
    }

    public String fontFamily() {
        return fontFamily;
    }

    public double fontSize() {
        return fontSize;
    }

    public Font font() {
        return font;
    }

    public double cellWidth() {
        return cellWidth;
    }

    public double lineHeight() {
        return lineHeight;
    }

    public double baselineOffset() {
        return baselineOffset;
    }

    /** The window's shared glyph atlas (see {@link GlyphCache}). */
    public GlyphCache glyphCache() {
        return glyphCache;
    }

    /** Columns that fit in a pane of the given pixel width (after subtracting the padding). */
    public int columnsFor(double widthPx) {
        return Math.max(1, (int) ((widthPx - 2 * PADDING) / cellWidth));
    }

    /** Rows that fit in a pane of the given pixel height (after subtracting the padding). */
    public int rowsFor(double heightPx) {
        return Math.max(1, (int) ((heightPx - 2 * PADDING) / lineHeight));
    }

    private void measure(Font font) {
        Text text = new Text("┃MgÅjy");
        text.setFont(font);
        // Snap the cell size to whole pixels so cells tile on integer boundaries. Fractional
        // cell metrics put every cell edge on a sub-pixel position, leaving anti-aliased
        // seams that show up as a faint grid behind the themed cell backgrounds. Rounding
        // leaves a few pixels of unused space at the right/bottom edge, which is fine.
        this.lineHeight = Math.max(1.0, Math.round(text.getLayoutBounds().getHeight()));
        this.baselineOffset = -text.getLayoutBounds().getMinY();

        Text cell = new Text("M");
        cell.setFont(font);
        this.cellWidth = Math.max(1.0, Math.round(cell.getLayoutBounds().getWidth()));
    }
}
