package com.gregor.jprototerm;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * Rasterized glyph alpha masks for one window's font, shared by every pane's renderer. The atlas is
 * a pure function of the window's {@link TerminalMetrics} (font family/size, snapped cell geometry,
 * baseline), and all panes in a window observe the same metrics, so a single shared cache lets N
 * panes reuse one copy of each glyph instead of each rasterizing and retaining its own. It also
 * means a pane whose backbuffer was released (see {@link GhosttyTerminalRenderer}) does not have to
 * re-rasterize glyphs when it is shown again.
 *
 * <p>Rasterizing goes through JavaFX ({@link Canvas#snapshot}), so {@link #glyph} must be called on
 * the FX thread — which is where all rendering happens. The cache self-invalidates when the metrics
 * change (e.g. a font switch): the next lookup notices and clears.
 */
final class GlyphCache {
    record Glyph(int width, int height, byte[] alpha) {
    }

    private final TerminalMetrics metrics;
    private final Map<String, Glyph> glyphs = new HashMap<>();
    // The metrics snapshot the cached glyphs were rasterized for; a mismatch clears the cache.
    private Font font;
    private int cellWidth;
    private int lineHeight;
    private double baseline;

    GlyphCache(TerminalMetrics metrics) {
        this.metrics = metrics;
    }

    Glyph glyph(String text) {
        ensureCurrent();
        return glyphs.computeIfAbsent(text, this::renderGlyph);
    }

    // Drop the rasterized masks if the font/cell geometry changed since they were built. Cheap to
    // call per lookup: a no-op unless the window's metrics actually changed under us.
    private void ensureCurrent() {
        Font currentFont = metrics.font();
        int currentCellWidth = Math.max(1, (int) Math.round(metrics.cellWidth()));
        int currentLineHeight = Math.max(1, (int) Math.round(metrics.lineHeight()));
        double currentBaseline = metrics.baselineOffset();
        if (currentFont != font || currentCellWidth != cellWidth
                || currentLineHeight != lineHeight || currentBaseline != baseline) {
            font = currentFont;
            cellWidth = currentCellWidth;
            lineHeight = currentLineHeight;
            baseline = currentBaseline;
            glyphs.clear();
        }
    }

    private Glyph renderGlyph(String value) {
        Text measured = new Text(value);
        measured.setFont(font);
        int glyphWidth = Math.max(cellWidth, (int) Math.ceil(measured.getLayoutBounds().getWidth()) + 2);
        Canvas canvas = new Canvas(glyphWidth, lineHeight);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFontSmoothingType(FontSmoothingType.GRAY);
        gc.setFont(font);
        gc.setFill(Color.WHITE);
        gc.fillText(value, 0.0, baseline);

        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        WritableImage snapshot = canvas.snapshot(parameters, null);
        PixelReader reader = snapshot.getPixelReader();
        byte[] alpha = new byte[glyphWidth * lineHeight];
        for (int y = 0; y < lineHeight; y++) {
            int offset = y * glyphWidth;
            for (int x = 0; x < glyphWidth; x++) {
                alpha[offset + x] = (byte) ((reader.getArgb(x, y) >>> 24) & 0xff);
            }
        }
        return new Glyph(glyphWidth, lineHeight, alpha);
    }
}
