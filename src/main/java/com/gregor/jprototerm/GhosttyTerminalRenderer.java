package com.gregor.jprototerm;

import dev.jlibghostty.KittyImage;
import dev.jlibghostty.KittyImageCompression;
import dev.jlibghostty.KittyImageFormat;
import dev.jlibghostty.KittyPlacement;
import dev.jlibghostty.KittyPlacementLayer;
import dev.jlibghostty.KittyPlaceholder;
import dev.jlibghostty.KittyRenderInfo;
import dev.jlibghostty.RenderCell;
import dev.jlibghostty.RenderColor;
import dev.jlibghostty.RenderCursorStyle;
import dev.jlibghostty.RenderRow;
import dev.jlibghostty.RenderStateSnapshot;
import javafx.geometry.Rectangle2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;

import java.io.ByteArrayInputStream;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The real terminal renderer: paints a pane's background, cell rows, cursor, border, padding
 * and (when enabled) kitty graphics. One instance per pane, since it caches that pane's
 * decoded kitty images.
 */
final class GhosttyTerminalRenderer extends TerminalRenderer {
    // GhosttyRenderStateDirty values (stable C ABI; see ghostty/vt/render.h).
    private static final int DIRTY_PARTIAL = 1;
    private static final int DIRTY_FULL = 2;

    private static final Color DEFAULT_FOREGROUND = Color.rgb(225, 229, 235);
    private static final Color SELECTED_BACKGROUND = Color.rgb(52, 92, 140);
    // The default cell background (used for cells with no explicit bg, and as the foreground
    // for reverse-video cells whose background is the terminal default).
    private static final Color PANE_BACKGROUND = Color.rgb(9, 10, 12);
    private static final Color ACTIVE_BORDER = Color.rgb(87, 166, 255);
    private static final Color INACTIVE_BORDER = Color.rgb(52, 57, 65);
    private static final Color CURSOR_FILL = Color.rgb(225, 229, 235, 0.28);

    // A full-screen redraw asks for one Color per cell; most cells share a handful of colors,
    // so cache them by packed RGB instead of allocating a Color each time. Bounded so a
    // truecolor gradient can't grow it without limit.
    private static final Map<Integer, Color> COLOR_CACHE = new HashMap<>();

    private final TerminalMetrics metrics;
    // Decoded kitty images for this renderer's pane (kitty graphics state is per-terminal).
    private final Map<KittyImageKey, Image> kittyImageCache = new HashMap<>();
    private final SoftwareBackbuffer software = new SoftwareBackbuffer();

    GhosttyTerminalRenderer(TerminalMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    void paintFull(GraphicsContext gc, RenderTarget target, boolean active) {
        double px = Math.round(target.x());
        double py = Math.round(target.y());
        double width = target.width();
        double height = target.height();
        gc.save();
        clip(gc, px, py, width, height, target.clip());
        boolean withKitty = target.kittyEnabled() && hasKittyGraphics(target);
        RenderStateSnapshot snapshot = target.snapshotFull();
        if (withKitty) {
            drawContent(gc, target, snapshot, px, py, width, height, active, true);
            software.invalidate();
        } else {
            software.paintFull(gc, snapshot, px, py, width, height, active);
        }
        gc.restore();
    }

    @Override
    void paintIncremental(GraphicsContext gc, RenderTarget target, boolean active) {
        double px = Math.round(target.x());
        double py = Math.round(target.y());
        double width = target.width();
        double height = target.height();
        gc.save();
        clip(gc, px, py, width, height, target.clip());
        if (target.kittyEnabled() && hasKittyGraphics(target)) {
            // Kitty placements can move without a per-row dirty flag, so always redraw whole.
            drawContent(gc, target, target.snapshotFull(), px, py, width, height, active, true);
            software.invalidate();
        } else {
            RenderStateSnapshot snapshot = target.snapshot();
            int dirty = snapshot == null ? DIRTY_FULL : snapshot.dirty();
            if (dirty == DIRTY_FULL) {
                software.paintFullOrShifted(gc, target.snapshotFull(), px, py, width, height, active);
            } else if (dirty == DIRTY_PARTIAL) {
                software.paintDirty(gc, target, snapshot, px, py, width, height, active);
            }
            // dirty == FALSE: nothing visible changed.
        }
        gc.restore();
    }

    // Full content render: background, border, all rows, cursor, and (when enabled) kitty
    // graphics. Used by the kitty direct path and by full redraws.
    private void drawContent(
            GraphicsContext gc,
            RenderTarget target,
            RenderStateSnapshot snapshot,
            double x,
            double y,
            double width,
            double height,
            boolean active,
            boolean withKitty
    ) {
        double cellWidth = metrics.cellWidth();
        double lineHeight = metrics.lineHeight();
        gc.setFontSmoothingType(FontSmoothingType.LCD);
        gc.setFill(PANE_BACKGROUND);
        gc.fillRect(x, y, width, height);
        gc.setFont(metrics.font());

        double left = x + TerminalMetrics.PADDING;
        double top = y + TerminalMetrics.PADDING;
        double baseline = top + metrics.baselineOffset();

        Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds = withKitty
                ? kittyPlaceholderBounds(snapshot)
                : Map.of();

        if (withKitty) {
            drawKittyGraphics(gc, target, KittyPlacementLayer.BELOW_TEXT, placeholderBounds, left, top, cellWidth, lineHeight);
        }

        if (snapshot != null) {
            double contentBottom = top + snapshot.rows() * lineHeight;
            fillVerticalPadding(gc, snapshot, x, y, width, height, top, contentBottom);
            for (RenderRow row : snapshot.renderRows()) {
                double y0 = Math.floor(top + (row.row() * lineHeight));
                double y1 = Math.ceil(top + ((row.row() + 1) * lineHeight));
                paintSidePadding(gc, row, x, width, left, cellWidth, y0, y1 - y0);
                drawRow(gc, row, left, top, baseline, cellWidth, lineHeight);
            }
            drawCursor(gc, snapshot, left, top, cellWidth, lineHeight);
        }

        if (withKitty) {
            drawKittyGraphics(gc, target, KittyPlacementLayer.ABOVE_TEXT, placeholderBounds, left, top, cellWidth, lineHeight);
        }

        drawBorder(gc, x, y, width, height, active);
    }

    // Incremental render: repaint only the rows ghostty flagged dirty, then restore the
    // cursor and border. The local band tracks the repainted span only so the border redraw
    // can be limited to it.
    private void drawDirtyRows(
            GraphicsContext gc,
            RenderStateSnapshot snapshot,
            double px,
            double py,
            double pw,
            double ph,
            boolean active
    ) {
        double cellWidth = metrics.cellWidth();
        double lineHeight = metrics.lineHeight();
        gc.setFontSmoothingType(FontSmoothingType.LCD);
        gc.setFont(metrics.font());
        double left = px + TerminalMetrics.PADDING;
        double top = py + TerminalMetrics.PADDING;
        double baseline = top + metrics.baselineOffset();

        double contentBottom = top + snapshot.rows() * lineHeight;
        int lastRow = snapshot.rows() - 1;
        List<RenderRow> rows = snapshot.renderRows();
        boolean allRowsDirty = allRowsDirty(snapshot, rows);
        if (allRowsDirty) {
            gc.setFill(PANE_BACKGROUND);
            gc.fillRect(px, py, pw, ph);
        }

        boolean cursorRowDirty = false;
        double bandMin = Double.POSITIVE_INFINITY;
        double bandMax = Double.NEGATIVE_INFINITY;
        for (RenderRow row : rows) {
            if (!row.dirty()) {
                continue;
            }
            // Snap the row band to integer pixels and paint opaque: a fractional-height fill
            // would leave sub-pixel seams between rows.
            double y0 = Math.floor(top + (row.row() * lineHeight));
            double y1 = Math.ceil(top + ((row.row() + 1) * lineHeight));
            if (!allRowsDirty) {
                gc.setFill(PANE_BACKGROUND);
                gc.fillRect(px, y0, pw, y1 - y0);
            }
            paintSidePadding(gc, row, px, pw, left, cellWidth, y0, y1 - y0);
            drawRow(gc, row, left, top, baseline, cellWidth, lineHeight);
            bandMin = Math.min(bandMin, y0);
            bandMax = Math.max(bandMax, y1);
            // Edge rows also own the top/bottom padding strip; repaint it and extend the
            // band so panes stacked above get restored over it too.
            if (row.row() == 0) {
                gc.setFill(rowEdgeBackground(row, true));
                gc.fillRect(px, py, pw, top - py);
                bandMin = Math.min(bandMin, py);
            }
            if (row.row() == lastRow) {
                gc.setFill(rowEdgeBackground(row, true));
                gc.fillRect(px, contentBottom, pw, py + ph - contentBottom);
                bandMax = Math.max(bandMax, py + ph);
            }
            if (snapshot.cursorViewportHasValue() && row.row() == snapshot.cursorViewportY()) {
                cursorRowDirty = true;
            }
        }
        if (bandMin > bandMax) {
            return;
        }

        // The cursor overlays its cell; redraw it only when its row was repainted, so we
        // neither leave a stale cursor nor stack the translucent overlay on itself.
        if (cursorRowDirty) {
            drawCursor(gc, snapshot, left, top, cellWidth, lineHeight);
        }
        // Repainting rows clears the side borders within the band; restore just those
        // segments, clipped to the band so we don't redraw the whole outline.
        gc.save();
        clipRect(gc, px, bandMin, pw, bandMax - bandMin);
        drawBorder(gc, px, py, pw, ph, active);
        gc.restore();
    }

    private static boolean allRowsDirty(RenderStateSnapshot snapshot, List<RenderRow> rows) {
        if (rows.size() != snapshot.rows()) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            RenderRow row = rows.get(i);
            if (!row.dirty() || row.row() != i) {
                return false;
            }
        }
        return true;
    }

    private void drawBorder(GraphicsContext gc, double x, double y, double width, double height, boolean active) {
        gc.setStroke(active ? ACTIVE_BORDER : INACTIVE_BORDER);
        gc.setLineWidth(active ? 2.0 : 1.0);
        gc.strokeRect(x + 0.5, y + 0.5, width - 1.0, height - 1.0);
    }

    // Effective background colour of a cell as it is drawn (reverse video swaps fg/bg, an
    // unset colour falls back to the defaults).
    private static Color cellBackgroundColor(RenderCell cell) {
        if (cell.inverse()) {
            var fg = cell.foreground();
            return fg.isPresent() ? toFxColor(fg.get()) : DEFAULT_FOREGROUND;
        }
        var bg = cell.background();
        return bg.isPresent() ? toFxColor(bg.get()) : PANE_BACKGROUND;
    }

    private static Color rowEdgeBackground(RenderRow row, boolean firstCell) {
        List<RenderCell> cells = row.cells();
        if (cells.isEmpty()) {
            return PANE_BACKGROUND;
        }
        return cellBackgroundColor(firstCell ? cells.get(0) : cells.get(cells.size() - 1));
    }

    // Extend the row's edge-cell backgrounds into the left/right padding (the margin and the
    // right-edge rounding sliver), so the unused space matches the rendered content.
    private void paintSidePadding(GraphicsContext gc, RenderRow row, double paneX, double paneWidth,
            double contentLeft, double cellWidth, double yTop, double bandHeight) {
        int columns = row.cells().size();
        if (columns == 0) {
            return;
        }
        double contentRight = contentLeft + (columns * cellWidth);
        gc.setFill(rowEdgeBackground(row, true));
        gc.fillRect(paneX, yTop, contentLeft - paneX, bandHeight);
        gc.setFill(rowEdgeBackground(row, false));
        gc.fillRect(contentRight, yTop, paneX + paneWidth - contentRight, bandHeight);
    }

    // Fill the top/bottom padding strips with the top/bottom row's edge colour.
    private void fillVerticalPadding(GraphicsContext gc, RenderStateSnapshot snapshot,
            double paneX, double paneY, double paneWidth, double paneHeight, double contentTop, double contentBottom) {
        List<RenderRow> rows = snapshot.renderRows();
        if (rows.isEmpty()) {
            return;
        }
        gc.setFill(rowEdgeBackground(rows.get(0), true));
        gc.fillRect(paneX, paneY, paneWidth, contentTop - paneY);
        gc.setFill(rowEdgeBackground(rows.get(rows.size() - 1), true));
        gc.fillRect(paneX, contentBottom, paneWidth, paneY + paneHeight - contentBottom);
    }

    private void drawRow(
            GraphicsContext gc,
            RenderRow row,
            double left,
            double top,
            double baseline,
            double cellWidth,
            double lineHeight
    ) {
        drawRowBackgrounds(gc, row, left, top, cellWidth, lineHeight);
        drawRowText(gc, row, left, baseline, cellWidth, lineHeight);
    }

    private static void drawRowBackgrounds(
            GraphicsContext gc,
            RenderRow row,
            double left,
            double top,
            double cellWidth,
            double lineHeight
    ) {
        Color runBackground = null;
        int runStartColumn = 0;
        int previousColumn = -1;
        for (RenderCell cell : row.cells()) {
            if (cell.kittyPlaceholder().isPresent()) {
                flushBackgroundRun(gc, runBackground, left, top, cellWidth, lineHeight, row.row(), runStartColumn, previousColumn);
                runBackground = null;
                previousColumn = -1;
                continue;
            }

            Color bg = cell.selected() ? SELECTED_BACKGROUND : cellBackgroundOverride(cell);
            if (bg == null) {
                flushBackgroundRun(gc, runBackground, left, top, cellWidth, lineHeight, row.row(), runStartColumn, previousColumn);
                runBackground = null;
                previousColumn = -1;
                continue;
            }

            if (runBackground == null || bg != runBackground || cell.column() != previousColumn + 1) {
                flushBackgroundRun(gc, runBackground, left, top, cellWidth, lineHeight, row.row(), runStartColumn, previousColumn);
                runBackground = bg;
                runStartColumn = cell.column();
            }
            previousColumn = cell.column();
        }
        flushBackgroundRun(gc, runBackground, left, top, cellWidth, lineHeight, row.row(), runStartColumn, previousColumn);
    }

    private static void flushBackgroundRun(
            GraphicsContext gc,
            Color background,
            double left,
            double top,
            double cellWidth,
            double lineHeight,
            int row,
            int startColumn,
            int endColumn
    ) {
        if (background == null || endColumn < startColumn) {
            return;
        }
        gc.setFill(background);
        gc.fillRect(
                left + (startColumn * cellWidth),
                top + (row * lineHeight),
                (endColumn - startColumn + 1) * cellWidth,
                lineHeight);
    }

    private void drawRowText(
            GraphicsContext gc,
            RenderRow row,
            double left,
            double baseline,
            double cellWidth,
            double lineHeight
    ) {
        for (RenderCell cell : row.cells()) {
            if (cell.kittyPlaceholder().isPresent() || cell.codepoints().length == 0) {
                continue;
            }

            gc.setFill(cellForegroundColor(cell));
            gc.fillText(cell.text(), left + (cell.column() * cellWidth), baseline + (row.row() * lineHeight));
        }
    }

    // Background override for a cell: null means the pane default background already covers it.
    private static Color cellBackgroundOverride(RenderCell cell) {
        if (cell.inverse()) {
            var fg = cell.foreground();
            return fg.isPresent() ? toFxColor(fg.get()) : DEFAULT_FOREGROUND;
        }
        var bgOpt = cell.background();
        Color bg = bgOpt.isPresent() ? toFxColor(bgOpt.get()) : null;
        return bg;
    }

    private static Color cellForegroundColor(RenderCell cell) {
        var fgOpt = cell.foreground();
        var bgOpt = cell.background();
        Color fg = fgOpt.isPresent() ? toFxColor(fgOpt.get()) : DEFAULT_FOREGROUND;
        Color bg = bgOpt.isPresent() ? toFxColor(bgOpt.get()) : null;

        if (cell.inverse()) {
            return (bg != null) ? bg : PANE_BACKGROUND;
        }
        return fg;
    }

    private static Color toFxColor(RenderColor color) {
        int key = (color.red() << 16) | (color.green() << 8) | color.blue();
        Color cached = COLOR_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (COLOR_CACHE.size() >= 4096) {
            COLOR_CACHE.clear();
        }
        Color created = Color.rgb(color.red(), color.green(), color.blue());
        COLOR_CACHE.put(key, created);
        return created;
    }

    private static void drawCursor(GraphicsContext gc, RenderStateSnapshot snapshot, double left, double top, double cellWidth, double lineHeight) {
        if (!snapshot.cursorVisible() || !snapshot.cursorViewportHasValue()) {
            return;
        }

        double x = left + (snapshot.cursorViewportX() * cellWidth);
        double y = top + (snapshot.cursorViewportY() * lineHeight);
        gc.setStroke(DEFAULT_FOREGROUND);
        gc.setFill(CURSOR_FILL);
        gc.setLineWidth(1.5);

        RenderCursorStyle style = snapshot.cursorStyle();
        if (style == RenderCursorStyle.BAR) {
            gc.strokeLine(x + 0.5, y + 2.0, x + 0.5, y + lineHeight - 2.0);
        } else if (style == RenderCursorStyle.UNDERLINE) {
            gc.strokeLine(x + 1.0, y + lineHeight - 2.0, x + cellWidth - 1.0, y + lineHeight - 2.0);
        } else if (style == RenderCursorStyle.BLOCK) {
            gc.fillRect(x + 0.5, y + 1.0, Math.max(1.0, cellWidth - 1.0), Math.max(1.0, lineHeight - 2.0));
        } else {
            gc.strokeRect(x + 0.5, y + 1.0, Math.max(1.0, cellWidth - 1.0), Math.max(1.0, lineHeight - 2.0));
        }
    }

    // ---- Kitty graphics --------------------------------------------------------------

    private static boolean hasKittyGraphics(RenderTarget target) {
        return target.kittyGraphics()
                .map(graphics -> !graphics.isEmpty())
                .orElse(false);
    }

    private void drawKittyGraphics(
            GraphicsContext gc,
            RenderTarget target,
            KittyPlacementLayer layer,
            Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds,
            double originX,
            double originY,
            double cellWidth,
            double lineHeight
    ) {
        target.kittyGraphics().ifPresent(graphics -> {
            for (KittyPlacement placement : graphics.placements(layer)) {
                Image image = imageFor(placement);
                if (image == null) {
                    continue;
                }

                if (placement.virtual()) {
                    drawVirtualKittyPlacement(gc, placement, image, placeholderBounds, originX, originY, cellWidth, lineHeight);
                } else {
                    drawPinnedKittyPlacement(gc, placement, image, originX, originY, cellWidth, lineHeight);
                }
            }
        });
    }

    private static void drawPinnedKittyPlacement(
            GraphicsContext gc,
            KittyPlacement placement,
            Image image,
            double originX,
            double originY,
            double cellWidth,
            double lineHeight
    ) {
        KittyRenderInfo renderInfo = placement.renderInfo().orElse(null);
        if (renderInfo == null || !renderInfo.viewportVisible()) {
            return;
        }

        double sourceX = renderInfo.sourceX();
        double sourceY = renderInfo.sourceY();
        double sourceWidth = renderInfo.sourceWidth();
        double sourceHeight = renderInfo.sourceHeight();
        if (sourceWidth <= 0.0 || sourceHeight <= 0.0) {
            return;
        }

        double x = originX + (renderInfo.viewportColumn() * cellWidth) + placement.xOffset();
        double y = originY + (renderInfo.viewportRow() * lineHeight) + placement.yOffset();
        double width = renderInfo.pixelWidth() > 0 ? renderInfo.pixelWidth() : renderInfo.gridColumns() * cellWidth;
        double height = renderInfo.pixelHeight() > 0 ? renderInfo.pixelHeight() : renderInfo.gridRows() * lineHeight;
        if (width <= 0.0 || height <= 0.0) {
            return;
        }

        gc.drawImage(image, sourceX, sourceY, sourceWidth, sourceHeight, x, y, width, height);
    }

    private static void drawVirtualKittyPlacement(
            GraphicsContext gc,
            KittyPlacement placement,
            Image image,
            Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds,
            double originX,
            double originY,
            double cellWidth,
            double lineHeight
    ) {
        KittyPlaceholderBounds bounds = placeholderBounds.get(new KittyPlaceholderKey(placement.imageId(), placement.placementId()));
        if (bounds == null) {
            bounds = placeholderBounds.get(new KittyPlaceholderKey(placement.imageId(), 0));
        }
        if (bounds == null && placement.placementId() == 0) {
            bounds = placeholderBounds.entrySet().stream()
                    .filter(entry -> entry.getKey().imageId() == placement.imageId())
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (bounds == null || bounds.isEmpty()) {
            return;
        }

        SourceRect source = sourceRect(placement, image);
        if (source.width() <= 0.0 || source.height() <= 0.0) {
            return;
        }

        long gridColumns = gridColumns(placement, bounds);
        long gridRows = gridRows(placement, bounds);
        double sourceCellWidth = source.width() / Math.max(1L, gridColumns);
        double sourceCellHeight = source.height() / Math.max(1L, gridRows);

        double sourceX = source.x() + (bounds.minSourceColumn * sourceCellWidth);
        double sourceY = source.y() + (bounds.minSourceRow * sourceCellHeight);
        double sourceWidth = bounds.sourceColumns() * sourceCellWidth;
        double sourceHeight = bounds.sourceRows() * sourceCellHeight;
        double x = originX + (bounds.minColumn * cellWidth);
        double y = originY + (bounds.minRow * lineHeight);
        double availableWidth = bounds.columns() * cellWidth;
        double availableHeight = bounds.rows() * lineHeight;

        if (sourceWidth <= 0.0 || sourceHeight <= 0.0 || availableWidth <= 0.0 || availableHeight <= 0.0) {
            return;
        }

        double scale = Math.min(availableWidth / sourceWidth, availableHeight / sourceHeight);
        double width = sourceWidth * scale;
        double height = sourceHeight * scale;
        gc.drawImage(image, sourceX, sourceY, sourceWidth, sourceHeight, x, y, width, height);
    }

    private static long gridColumns(KittyPlacement placement, KittyPlaceholderBounds bounds) {
        if (placement.columns() > 0) {
            return placement.columns();
        }
        return Math.max(bounds.maxSourceColumn + 1, bounds.sourceColumns());
    }

    private static long gridRows(KittyPlacement placement, KittyPlaceholderBounds bounds) {
        if (placement.rows() > 0) {
            return placement.rows();
        }
        return Math.max(bounds.maxSourceRow + 1, bounds.sourceRows());
    }

    private static SourceRect sourceRect(KittyPlacement placement, Image image) {
        double sourceX = placement.sourceX();
        double sourceY = placement.sourceY();
        double sourceWidth = placement.sourceWidth() > 0 ? placement.sourceWidth() : image.getWidth() - sourceX;
        double sourceHeight = placement.sourceHeight() > 0 ? placement.sourceHeight() : image.getHeight() - sourceY;
        return new SourceRect(sourceX, sourceY, Math.min(sourceWidth, image.getWidth() - sourceX), Math.min(sourceHeight, image.getHeight() - sourceY));
    }

    private Image imageFor(KittyPlacement placement) {
        return placement.image().map(image -> {
            // Build the cache key from cheap metadata only — the pixel buffer is never copied out
            // of native memory on a cache hit (the common per-frame case).
            KittyImageKey key = KittyImageKey.of(image);
            Image cached = kittyImageCache.get(key);
            if (cached != null) {
                return cached;
            }

            kittyImageCache.keySet().removeIf(existing -> existing.id() == image.id());
            Image decoded = decodeImage(image);
            if (decoded != null) {
                kittyImageCache.put(key, decoded);
            }
            return decoded;
        }).orElse(null);
    }

    private Image decodeImage(KittyImage source) {
        if (source.compression() != KittyImageCompression.NONE) {
            return null;
        }

        // Only now — on a cache miss — do we pull the raw bytes across the native boundary.
        byte[] data = source.data();
        KittyImageFormat format = source.format();
        if (format == KittyImageFormat.PNG) {
            return new Image(new ByteArrayInputStream(data));
        }

        int width = Math.toIntExact(source.width());
        int height = Math.toIntExact(source.height());
        WritableImage image = new WritableImage(width, height);

        if (format == KittyImageFormat.RGBA) {
            image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getByteBgraInstance(), rgbaToBgra(data), 0, width * 4);
        } else if (format == KittyImageFormat.RGB) {
            image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getByteRgbInstance(), data, 0, width * 3);
        }
        return image;
    }

    private static byte[] rgbaToBgra(byte[] rgba) {
        byte[] bgra = new byte[rgba.length];
        for (int i = 0; i + 3 < rgba.length; i += 4) {
            bgra[i] = rgba[i + 2];
            bgra[i + 1] = rgba[i + 1];
            bgra[i + 2] = rgba[i];
            bgra[i + 3] = rgba[i + 3];
        }
        return bgra;
    }

    private static Map<KittyPlaceholderKey, KittyPlaceholderBounds> kittyPlaceholderBounds(RenderStateSnapshot snapshot) {
        if (snapshot == null) {
            return Map.of();
        }

        Map<KittyPlaceholderKey, KittyPlaceholderBounds> result = new HashMap<>();
        for (RenderRow row : snapshot.renderRows()) {
            for (RenderCell cell : row.cells()) {
                cell.kittyPlaceholder().ifPresent(placeholder -> {
                    KittyPlaceholderKey key = new KittyPlaceholderKey(placeholder.imageId(), placeholder.placementId());
                    result.computeIfAbsent(key, ignored -> new KittyPlaceholderBounds()).include(row.row(), cell.column(), placeholder);
                });
            }
        }
        return result;
    }

    private final class SoftwareBackbuffer {
        private int width;
        private int height;
        private int[] pixels = new int[0];
        private PixelBuffer<IntBuffer> pixelBuffer;
        private WritableImage image;
        private long[] rowHashes = new long[0];
        private CursorState lastCursor = CursorState.none();
        private GlyphCache glyphs;
        // Half-open [min, max) vertical span of buffer rows written since the last present, so
        // present() can upload only that band to the GPU instead of the whole pane texture.
        private int dirtyMinY = Integer.MAX_VALUE;
        private int dirtyMaxY = Integer.MIN_VALUE;

        private void invalidate() {
            rowHashes = new long[0];
            lastCursor = CursorState.none();
        }

        // Record that buffer rows [y0, y1) changed; clamped to the buffer in dirtyRegion().
        private void markDirtyRows(int y0, int y1) {
            if (y0 < dirtyMinY) {
                dirtyMinY = y0;
            }
            if (y1 > dirtyMaxY) {
                dirtyMaxY = y1;
            }
        }

        private void resetDirty() {
            dirtyMinY = Integer.MAX_VALUE;
            dirtyMaxY = Integer.MIN_VALUE;
        }

        // The region to hand PixelBuffer.updateBuffer: a full-width band covering the rows
        // written this frame (clamped to the buffer), or EMPTY when nothing changed.
        private Rectangle2D dirtyRegion() {
            int y0 = Math.max(0, dirtyMinY);
            int y1 = Math.min(height, dirtyMaxY);
            if (y0 >= y1) {
                return Rectangle2D.EMPTY;
            }
            return new Rectangle2D(0, y0, width, y1 - y0);
        }

        private void paintFull(GraphicsContext gc, RenderStateSnapshot snapshot,
                double px, double py, double paneWidth, double paneHeight, boolean active) {
            ensure(paneWidth, paneHeight);
            fillRect(0, 0, width, height, argbPre(PANE_BACKGROUND));
            if (snapshot != null) {
                paintSnapshot(snapshot);
                drawCursor(snapshot);
                rememberSnapshot(snapshot);
            } else {
                invalidate();
            }
            drawBorder(active);
            present(gc, px, py);
        }

        private void paintFullOrShifted(GraphicsContext gc, RenderStateSnapshot snapshot,
                double px, double py, double paneWidth, double paneHeight, boolean active) {
            ensure(paneWidth, paneHeight);
            if (snapshot == null || !canDiff(snapshot)) {
                paintFull(gc, snapshot, px, py, paneWidth, paneHeight, active);
                return;
            }

            long[] currentHashes = hashes(snapshot);
            int scroll = inferScroll(currentHashes);
            if (scroll != 0) {
                scrollContentPixels(scroll);
                scrollHashes(scroll);
            }

            CursorState cursor = CursorState.from(snapshot);
            int oldCursorRow = shiftedCursorRow(lastCursor, scroll);
            int newCursorRow = cursor.viewportRow();
            boolean cursorChanged = !cursor.equals(lastCursor);
            for (RenderRow row : snapshot.renderRows()) {
                int rowIndex = row.row();
                boolean repaint = currentHashes[rowIndex] != rowHashes[rowIndex]
                        || rowIndex == newCursorRow
                        || (cursorChanged && rowIndex == oldCursorRow);
                if (repaint) {
                    paintRow(row);
                    rowHashes[rowIndex] = currentHashes[rowIndex];
                }
            }
            lastCursor = cursor;
            drawCursor(snapshot);
            drawBorder(active);
            present(gc, px, py);
        }

        private void paintDirty(GraphicsContext gc, RenderTarget target, RenderStateSnapshot snapshot,
                double px, double py, double paneWidth, double paneHeight, boolean active) {
            ensure(paneWidth, paneHeight);
            if (snapshot == null) {
                return;
            }
            if (rowHashes.length != snapshot.rows()) {
                paintFull(gc, target.snapshotFull(), px, py, paneWidth, paneHeight, active);
                return;
            }

            CursorState cursor = CursorState.from(snapshot);
            int oldCursorRow = lastCursor.viewportRow();
            int newCursorRow = cursor.viewportRow();
            boolean cursorChanged = !cursor.equals(lastCursor);
            boolean[] repainted = new boolean[snapshot.rows()];
            boolean needsCursorDraw = cursorChanged;

            for (RenderRow row : snapshot.renderRows()) {
                if (!row.dirty()) {
                    continue;
                }
                paintRow(row);
                rowHashes[row.row()] = rowHash(row);
                repainted[row.row()] = true;
                if (row.row() == newCursorRow) {
                    needsCursorDraw = true;
                }
            }

            if (cursorChanged) {
                if (!repaintCursorRow(snapshot, oldCursorRow, repainted)) {
                    paintFullOrShifted(gc, target.snapshotFull(), px, py, paneWidth, paneHeight, active);
                    return;
                }
            }
            if (repaintedRowHasCursor(newCursorRow, repainted)
                    && !repaintCursorRow(snapshot, newCursorRow, repainted)) {
                paintFullOrShifted(gc, target.snapshotFull(), px, py, paneWidth, paneHeight, active);
                return;
            }
            lastCursor = cursor;
            if (needsCursorDraw) {
                drawCursor(snapshot);
            }
            drawBorder(active);
            present(gc, px, py);
        }

        private boolean repaintCursorRow(RenderStateSnapshot snapshot, int rowIndex, boolean[] repainted) {
            if (rowIndex < 0 || rowIndex >= repainted.length || repainted[rowIndex]) {
                return true;
            }
            RenderRow row = rowByIndex(snapshot, rowIndex);
            if (row == null || !row.dirty()) {
                return false;
            }
            paintRow(row);
            rowHashes[rowIndex] = rowHash(row);
            repainted[rowIndex] = true;
            return true;
        }

        private boolean repaintedRowHasCursor(int rowIndex, boolean[] repainted) {
            return rowIndex >= 0 && rowIndex < repainted.length && repainted[rowIndex];
        }

        private RenderRow rowByIndex(RenderStateSnapshot snapshot, int rowIndex) {
            for (RenderRow row : snapshot.renderRows()) {
                if (row.row() == rowIndex) {
                    return row;
                }
            }
            return null;
        }

        private void ensure(double paneWidth, double paneHeight) {
            int nextWidth = Math.max(1, (int) Math.round(paneWidth));
            int nextHeight = Math.max(1, (int) Math.round(paneHeight));
            if (nextWidth == width && nextHeight == height && image != null) {
                ensureGlyphs();
                return;
            }

            width = nextWidth;
            height = nextHeight;
            pixels = new int[width * height];
            pixelBuffer = new PixelBuffer<>(width, height, IntBuffer.wrap(pixels), PixelFormat.getIntArgbPreInstance());
            image = new WritableImage(pixelBuffer);
            invalidate();
            ensureGlyphs();
        }

        private void ensureGlyphs() {
            int cellWidth = cellWidth();
            int lineHeight = lineHeight();
            double baseline = metrics.baselineOffset();
            if (glyphs == null || glyphs.font != metrics.font()
                    || glyphs.cellWidth != cellWidth || glyphs.lineHeight != lineHeight || glyphs.baseline != baseline) {
                glyphs = new GlyphCache(metrics.font(), cellWidth, lineHeight, baseline);
            }
        }

        private void present(GraphicsContext gc, double px, double py) {
            // Only re-upload the rows that actually changed; the unchanged remainder of the pane
            // texture is already correct on the GPU from the previous frame.
            Rectangle2D dirty = dirtyRegion();
            resetDirty();
            pixelBuffer.updateBuffer(ignored -> dirty);
            gc.drawImage(image, px, py);
        }

        private boolean canDiff(RenderStateSnapshot snapshot) {
            return rowHashes.length == snapshot.rows() && snapshot.renderRows().size() == snapshot.rows();
        }

        private void rememberSnapshot(RenderStateSnapshot snapshot) {
            if (rowHashes.length != snapshot.rows()) {
                rowHashes = new long[snapshot.rows()];
            }
            for (RenderRow row : snapshot.renderRows()) {
                rowHashes[row.row()] = rowHash(row);
            }
            lastCursor = CursorState.from(snapshot);
        }

        private long[] hashes(RenderStateSnapshot snapshot) {
            long[] hashes = new long[snapshot.rows()];
            for (RenderRow row : snapshot.renderRows()) {
                hashes[row.row()] = rowHash(row);
            }
            return hashes;
        }

        private int inferScroll(long[] currentHashes) {
            int rows = currentHashes.length;
            int bestDelta = 0;
            int bestScore = 0;
            int maxDelta = Math.min(8, Math.max(0, rows - 1));
            for (int delta = -maxDelta; delta <= maxDelta; delta++) {
                if (delta == 0) {
                    continue;
                }
                int score = 0;
                int overlap = 0;
                for (int row = 0; row < rows; row++) {
                    int previous = row - delta;
                    if (previous < 0 || previous >= rows) {
                        continue;
                    }
                    overlap++;
                    if (currentHashes[row] == rowHashes[previous]) {
                        score++;
                    }
                }
                if (score > bestScore || (score == bestScore && Math.abs(delta) < Math.abs(bestDelta))) {
                    bestScore = score;
                    bestDelta = delta;
                }
            }
            int threshold = Math.max(3, (int) Math.ceil(rows * 0.55));
            return bestScore >= threshold ? bestDelta : 0;
        }

        private int shiftedCursorRow(CursorState cursor, int scroll) {
            int row = cursor.viewportRow();
            if (row < 0) {
                return -1;
            }
            row += scroll;
            return row >= 0 && row < rowHashes.length ? row : -1;
        }

        private void scrollHashes(int rows) {
            long[] shifted = new long[rowHashes.length];
            for (int row = 0; row < shifted.length; row++) {
                int previous = row - rows;
                if (previous >= 0 && previous < rowHashes.length) {
                    shifted[row] = rowHashes[previous];
                }
            }
            rowHashes = shifted;
        }

        private void scrollContentPixels(int rows) {
            int dy = rows * lineHeight();
            int top = contentTop();
            int contentHeight = rowHashes.length * lineHeight();
            // The whole content region shifts; the arraycopy below moves pixels that the
            // per-strip fillRect calls don't touch, so mark the full content band for upload.
            markDirtyRows(top, top + contentHeight);
            if (dy == 0 || Math.abs(dy) >= contentHeight) {
                fillRect(0, top, width, contentHeight, argbPre(PANE_BACKGROUND));
                return;
            }

            if (dy < 0) {
                int srcY = top - dy;
                int dstY = top;
                int copyHeight = contentHeight + dy;
                for (int y = 0; y < copyHeight; y++) {
                    System.arraycopy(pixels, (srcY + y) * width, pixels, (dstY + y) * width, width);
                }
                fillRect(0, top + copyHeight, width, -dy, argbPre(PANE_BACKGROUND));
            } else {
                int srcY = top;
                int dstY = top + dy;
                int copyHeight = contentHeight - dy;
                for (int y = copyHeight - 1; y >= 0; y--) {
                    System.arraycopy(pixels, (srcY + y) * width, pixels, (dstY + y) * width, width);
                }
                fillRect(0, top, width, dy, argbPre(PANE_BACKGROUND));
            }
        }

        private void paintSnapshot(RenderStateSnapshot snapshot) {
            List<RenderRow> rows = snapshot.renderRows();
            if (rows.isEmpty()) {
                return;
            }
            int top = contentTop();
            int contentBottom = top + snapshot.rows() * lineHeight();
            fillRect(0, 0, width, top, argbPre(rowEdgeBackground(rows.get(0), true)));
            fillRect(0, contentBottom, width, height - contentBottom, argbPre(rowEdgeBackground(rows.get(rows.size() - 1), true)));
            for (RenderRow row : rows) {
                paintRow(row);
            }
        }

        private void paintRow(RenderRow row) {
            int rowTop = contentTop() + (row.row() * lineHeight());
            int rowHeight = lineHeight();
            fillRect(0, rowTop, width, rowHeight, argbPre(PANE_BACKGROUND));
            if (row.row() == 0) {
                fillRect(0, 0, width, contentTop(), argbPre(rowEdgeBackground(row, true)));
            }
            if (row.row() == rowHashes.length - 1) {
                int bottom = contentTop() + (rowHashes.length * lineHeight());
                fillRect(0, bottom, width, height - bottom, argbPre(rowEdgeBackground(row, true)));
            }
            paintRowSidePadding(row, rowTop, rowHeight);
            paintRowBackgrounds(row, rowTop, rowHeight);
            paintRowText(row, rowTop);
        }

        private void paintRowSidePadding(RenderRow row, int rowTop, int rowHeight) {
            List<RenderCell> cells = row.cells();
            if (cells.isEmpty()) {
                return;
            }
            int left = contentLeft();
            int contentRight = left + (cells.size() * cellWidth());
            fillRect(0, rowTop, left, rowHeight, argbPre(rowEdgeBackground(row, true)));
            fillRect(contentRight, rowTop, width - contentRight, rowHeight, argbPre(rowEdgeBackground(row, false)));
        }

        private void paintRowBackgrounds(RenderRow row, int rowTop, int rowHeight) {
            int cellWidth = cellWidth();
            Color runBackground = null;
            int runStartColumn = 0;
            int previousColumn = -1;
            for (RenderCell cell : row.cells()) {
                if (cell.kittyPlaceholder().isPresent()) {
                    flushBackground(runBackground, runStartColumn, previousColumn, rowTop, rowHeight);
                    runBackground = null;
                    previousColumn = -1;
                    continue;
                }

                Color bg = cell.selected() ? SELECTED_BACKGROUND : cellBackgroundOverride(cell);
                if (bg == null) {
                    flushBackground(runBackground, runStartColumn, previousColumn, rowTop, rowHeight);
                    runBackground = null;
                    previousColumn = -1;
                    continue;
                }
                if (runBackground == null || bg != runBackground || cell.column() != previousColumn + 1) {
                    flushBackground(runBackground, runStartColumn, previousColumn, rowTop, rowHeight);
                    runBackground = bg;
                    runStartColumn = cell.column();
                }
                previousColumn = cell.column();
            }
            flushBackground(runBackground, runStartColumn, previousColumn, rowTop, rowHeight);
        }

        private void flushBackground(Color background, int startColumn, int endColumn, int rowTop, int rowHeight) {
            if (background == null || endColumn < startColumn) {
                return;
            }
            fillRect(contentLeft() + (startColumn * cellWidth()), rowTop,
                    (endColumn - startColumn + 1) * cellWidth(), rowHeight, argbPre(background));
        }

        private void paintRowText(RenderRow row, int rowTop) {
            int cellWidth = cellWidth();
            int x0 = contentLeft();
            for (RenderCell cell : row.cells()) {
                if (cell.kittyPlaceholder().isPresent() || cell.codepoints().length == 0) {
                    continue;
                }
                Glyph glyph = glyphs.glyph(cell.text());
                int color = rgb(cellForegroundColor(cell));
                blitGlyph(glyph, x0 + (cell.column() * cellWidth), rowTop, color);
            }
        }

        private void blitGlyph(Glyph glyph, int x, int y, int rgb) {
            int red = (rgb >> 16) & 0xff;
            int green = (rgb >> 8) & 0xff;
            int blue = rgb & 0xff;
            // Clamp the glyph rectangle to the buffer once, so the inner loops carry no
            // per-pixel bounds check (this is the hottest pixel loop on a text repaint).
            int gyStart = Math.max(0, -y);
            int gyEnd = Math.min(glyph.height, height - y);
            int gxStart = Math.max(0, -x);
            int gxEnd = Math.min(glyph.width, width - x);
            if (gyStart >= gyEnd || gxStart >= gxEnd) {
                return;
            }
            for (int gy = gyStart; gy < gyEnd; gy++) {
                int rowOffset = (y + gy) * width;
                int glyphOffset = gy * glyph.width;
                for (int gx = gxStart; gx < gxEnd; gx++) {
                    int alpha = glyph.alpha[glyphOffset + gx] & 0xff;
                    if (alpha == 0) {
                        continue;
                    }
                    int index = rowOffset + x + gx;
                    pixels[index] = blendOpaque(pixels[index], red, green, blue, alpha);
                }
            }
            markDirtyRows(y + gyStart, y + gyEnd);
        }

        private int blendOpaque(int dst, int red, int green, int blue, int alpha) {
            int inv = 255 - alpha;
            int dstRed = (dst >> 16) & 0xff;
            int dstGreen = (dst >> 8) & 0xff;
            int dstBlue = dst & 0xff;
            int outRed = ((red * alpha) + (dstRed * inv) + 127) / 255;
            int outGreen = ((green * alpha) + (dstGreen * inv) + 127) / 255;
            int outBlue = ((blue * alpha) + (dstBlue * inv) + 127) / 255;
            return 0xff000000 | (outRed << 16) | (outGreen << 8) | outBlue;
        }

        private void drawCursor(RenderStateSnapshot snapshot) {
            if (!snapshot.cursorVisible() || !snapshot.cursorViewportHasValue()) {
                return;
            }
            int x = contentLeft() + ((int) snapshot.cursorViewportX() * cellWidth());
            int y = contentTop() + ((int) snapshot.cursorViewportY() * lineHeight());
            int cw = cellWidth();
            int lh = lineHeight();
            RenderCursorStyle style = snapshot.cursorStyle();
            if (style == RenderCursorStyle.BAR) {
                fillRect(x, y + 2, 1, Math.max(1, lh - 4), argbPre(DEFAULT_FOREGROUND));
            } else if (style == RenderCursorStyle.UNDERLINE) {
                fillRect(x + 1, y + lh - 2, Math.max(1, cw - 2), 1, argbPre(DEFAULT_FOREGROUND));
            } else if (style == RenderCursorStyle.BLOCK) {
                fillRectAlpha(x, y + 1, Math.max(1, cw - 1), Math.max(1, lh - 2), CURSOR_FILL);
            } else {
                strokeRect(x, y + 1, Math.max(1, cw - 1), Math.max(1, lh - 2), argbPre(DEFAULT_FOREGROUND), 1);
            }
        }

        private void drawBorder(boolean active) {
            strokeRect(0, 0, width, height, argbPre(active ? ACTIVE_BORDER : INACTIVE_BORDER), active ? 2 : 1);
        }

        private void strokeRect(int x, int y, int w, int h, int color, int lineWidth) {
            // The border is redrawn every frame to restore the side edges over the rows we
            // repaint, but its pixels never change between incremental frames. Write it without
            // marking the dirty band: the segments inside a repainted row's band are already
            // covered by that band (and so re-uploaded), and the segments outside it are
            // identical to what is already on the GPU, so they need no upload.
            for (int i = 0; i < lineWidth; i++) {
                fillRectRaw(x + i, y + i, w - (2 * i), 1, color);
                fillRectRaw(x + i, y + h - 1 - i, w - (2 * i), 1, color);
                fillRectRaw(x + i, y + i, 1, h - (2 * i), color);
                fillRectRaw(x + w - 1 - i, y + i, 1, h - (2 * i), color);
            }
        }

        private void fillRectAlpha(int x, int y, int w, int h, Color color) {
            int alpha = (int) Math.round(color.getOpacity() * 255.0);
            int rgb = rgb(color);
            int red = (rgb >> 16) & 0xff;
            int green = (rgb >> 8) & 0xff;
            int blue = rgb & 0xff;
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(width, x + w);
            int y1 = Math.min(height, y + h);
            for (int py = y0; py < y1; py++) {
                int offset = py * width;
                for (int px = x0; px < x1; px++) {
                    int index = offset + px;
                    pixels[index] = blendOpaque(pixels[index], red, green, blue, alpha);
                }
            }
            markDirtyRows(y0, y1);
        }

        private void fillRect(int x, int y, int w, int h, int color) {
            int y0 = Math.max(0, y);
            int y1 = Math.min(height, y + h);
            if (fillRectRaw(x, y, w, h, color)) {
                markDirtyRows(y0, y1);
            }
        }

        // Raw fill with no dirty-band tracking; returns whether any pixels were written.
        private boolean fillRectRaw(int x, int y, int w, int h, int color) {
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(width, x + w);
            int y1 = Math.min(height, y + h);
            if (x0 >= x1 || y0 >= y1) {
                return false;
            }
            for (int py = y0; py < y1; py++) {
                Arrays.fill(pixels, (py * width) + x0, (py * width) + x1, color);
            }
            return true;
        }

        private int contentLeft() {
            return (int) Math.round(TerminalMetrics.PADDING);
        }

        private int contentTop() {
            return (int) Math.round(TerminalMetrics.PADDING);
        }

        private int cellWidth() {
            return Math.max(1, (int) Math.round(metrics.cellWidth()));
        }

        private int lineHeight() {
            return Math.max(1, (int) Math.round(metrics.lineHeight()));
        }
    }

    private final class GlyphCache {
        private final javafx.scene.text.Font font;
        private final int cellWidth;
        private final int lineHeight;
        private final double baseline;
        private final Map<String, Glyph> glyphs = new HashMap<>();

        private GlyphCache(javafx.scene.text.Font font, int cellWidth, int lineHeight, double baseline) {
            this.font = font;
            this.cellWidth = cellWidth;
            this.lineHeight = lineHeight;
            this.baseline = baseline;
        }

        private Glyph glyph(String text) {
            return glyphs.computeIfAbsent(text, this::renderGlyph);
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

    private record Glyph(int width, int height, byte[] alpha) {
    }

    private record CursorState(boolean visible, boolean hasViewport, int column, int row, RenderCursorStyle style) {
        private static CursorState none() {
            return new CursorState(false, false, -1, -1, null);
        }

        private static CursorState from(RenderStateSnapshot snapshot) {
            if (snapshot == null || !snapshot.cursorVisible() || !snapshot.cursorViewportHasValue()) {
                return none();
            }
            return new CursorState(true, true, (int) snapshot.cursorViewportX(), (int) snapshot.cursorViewportY(), snapshot.cursorStyle());
        }

        private int viewportRow() {
            return visible && hasViewport ? row : -1;
        }
    }

    private static long rowHash(RenderRow row) {
        long hash = 0xcbf29ce484222325L;
        for (RenderCell cell : row.cells()) {
            hash = mix(hash, cell.column());
            hash = mix(hash, cell.inverse() ? 1 : 0);
            hash = mix(hash, cell.selected() ? 1 : 0);
            hash = mixColor(hash, cell.foreground().orElse(null));
            hash = mixColor(hash, cell.background().orElse(null));
            for (int codepoint : cell.codepoints()) {
                hash = mix(hash, codepoint);
            }
            if (cell.kittyPlaceholder().isPresent()) {
                KittyPlaceholder placeholder = cell.kittyPlaceholder().get();
                hash = mix(hash, placeholder.imageId());
                hash = mix(hash, placeholder.placementId());
                hash = mix(hash, placeholder.sourceRow());
                hash = mix(hash, placeholder.sourceColumn());
            }
        }
        return hash;
    }

    private static long mixColor(long hash, RenderColor color) {
        return color == null ? mix(hash, -1) : mix(hash, (color.red() << 16) | (color.green() << 8) | color.blue());
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static int rgb(Color color) {
        int red = (int) Math.round(color.getRed() * 255.0);
        int green = (int) Math.round(color.getGreen() * 255.0);
        int blue = (int) Math.round(color.getBlue() * 255.0);
        return (red << 16) | (green << 8) | blue;
    }

    private static int argbPre(Color color) {
        int alpha = (int) Math.round(color.getOpacity() * 255.0);
        int red = (int) Math.round(color.getRed() * alpha);
        int green = (int) Math.round(color.getGreen() * alpha);
        int blue = (int) Math.round(color.getBlue() * alpha);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    // A kitty image is immutable for a given (id, number); re-transmitting under the same id
    // changes the number (and the snapshot below evicts stale entries by id anyway). So the
    // identity + dimensions + payload length are enough to key the decoded-image cache, and
    // we avoid fingerprinting the whole payload — which previously ran once per frame per
    // placement (O(image size)) just to look the image up.
    private record KittyImageKey(long id, long number, long width, long height, KittyImageFormat format, long dataLength) {
        private static KittyImageKey of(KittyImage image) {
            return new KittyImageKey(
                    image.id(),
                    image.number(),
                    image.width(),
                    image.height(),
                    image.format(),
                    image.dataLength()
            );
        }
    }

    private record KittyPlaceholderKey(long imageId, long placementId) {
    }

    private record SourceRect(double x, double y, double width, double height) {
    }

    private static final class KittyPlaceholderBounds {
        private int minRow = Integer.MAX_VALUE;
        private int maxRow = Integer.MIN_VALUE;
        private int minColumn = Integer.MAX_VALUE;
        private int maxColumn = Integer.MIN_VALUE;
        private long minSourceRow = Long.MAX_VALUE;
        private long maxSourceRow = Long.MIN_VALUE;
        private long minSourceColumn = Long.MAX_VALUE;
        private long maxSourceColumn = Long.MIN_VALUE;

        private void include(int row, int column, KittyPlaceholder placeholder) {
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minColumn = Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column);
            minSourceRow = Math.min(minSourceRow, placeholder.sourceRow());
            maxSourceRow = Math.max(maxSourceRow, placeholder.sourceRow());
            minSourceColumn = Math.min(minSourceColumn, placeholder.sourceColumn());
            maxSourceColumn = Math.max(maxSourceColumn, placeholder.sourceColumn());
        }

        private boolean isEmpty() {
            return minRow == Integer.MAX_VALUE;
        }

        private int rows() {
            return maxRow - minRow + 1;
        }

        private int columns() {
            return maxColumn - minColumn + 1;
        }

        private long sourceRows() {
            return maxSourceRow - minSourceRow + 1;
        }

        private long sourceColumns() {
            return maxSourceColumn - minSourceColumn + 1;
        }
    }
}
