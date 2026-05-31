package com.gregor.jprototerm;

import dev.jlibghostty.KittyImageCompression;
import dev.jlibghostty.KittyImageFormat;
import dev.jlibghostty.KittyImageSnapshot;
import dev.jlibghostty.KittyPlacement;
import dev.jlibghostty.KittyPlacementLayer;
import dev.jlibghostty.KittyPlaceholder;
import dev.jlibghostty.KittyRenderInfo;
import dev.jlibghostty.RenderCell;
import dev.jlibghostty.RenderColor;
import dev.jlibghostty.RenderCursorStyle;
import dev.jlibghostty.RenderRow;
import dev.jlibghostty.RenderStateSnapshot;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.FontSmoothingType;

import java.io.ByteArrayInputStream;
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
    private long[] rowHashes = new long[0];
    private CursorState lastCursor = CursorState.none();

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
        RenderStateSnapshot snapshot = target.snapshotFull();
        drawContent(gc, target, snapshot, px, py, width, height, active,
                target.kittyEnabled() && hasKittyGraphics(target));
        rememberSnapshot(snapshot);
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
            RenderStateSnapshot snapshot = target.snapshotFull();
            drawContent(gc, target, snapshot, px, py, width, height, active, true);
            rememberSnapshot(snapshot);
        } else {
            RenderStateSnapshot snapshot = target.snapshot();
            int dirty = snapshot == null ? DIRTY_FULL : snapshot.dirty();
            if (dirty == DIRTY_FULL) {
                if (!drawChangedRows(gc, snapshot, px, py, width, height, active)) {
                    RenderStateSnapshot fullSnapshot = target.snapshotFull();
                    drawContent(gc, target, fullSnapshot, px, py, width, height, active, false);
                    rememberSnapshot(fullSnapshot);
                }
            } else if (dirty == DIRTY_PARTIAL) {
                drawDirtyRows(gc, snapshot, px, py, width, height, active);
            }
            // dirty == FALSE: nothing visible changed.
        }
        gc.restore();
    }

    // Some TUIs repaint the whole viewport for small logical changes. When ghostty gives us
    // a full snapshot, compare row content with what we last painted and only redraw rows
    // whose cells changed, plus old/new cursor rows because the cursor is an overlay.
    private boolean drawChangedRows(
            GraphicsContext gc,
            RenderStateSnapshot snapshot,
            double px,
            double py,
            double pw,
            double ph,
            boolean active
    ) {
        if (snapshot == null || rowHashes.length != snapshot.rows() || snapshot.renderRows().size() != snapshot.rows()) {
            return false;
        }

        double cellWidth = metrics.cellWidth();
        double lineHeight = metrics.lineHeight();
        gc.setFontSmoothingType(FontSmoothingType.LCD);
        gc.setFont(metrics.font());
        double left = px + TerminalMetrics.PADDING;
        double top = py + TerminalMetrics.PADDING;
        double baseline = top + metrics.baselineOffset();
        double contentBottom = top + snapshot.rows() * lineHeight;
        int lastRow = snapshot.rows() - 1;

        CursorState cursor = CursorState.from(snapshot);
        long oldCursorRow = lastCursor.viewportRow();
        long newCursorRow = cursor.viewportRow();
        boolean cursorChanged = !cursor.equals(lastCursor);
        double bandMin = Double.POSITIVE_INFINITY;
        double bandMax = Double.NEGATIVE_INFINITY;

        for (RenderRow row : snapshot.renderRows()) {
            int rowIndex = row.row();
            if (rowIndex < 0 || rowIndex >= rowHashes.length) {
                return false;
            }

            long hash = rowHash(row);
            boolean repaint = hash != rowHashes[rowIndex]
                    || (cursorChanged && (rowIndex == oldCursorRow || rowIndex == newCursorRow));
            if (!repaint) {
                continue;
            }

            double y0 = Math.floor(top + (rowIndex * lineHeight));
            double y1 = Math.ceil(top + ((rowIndex + 1) * lineHeight));
            gc.setFill(PANE_BACKGROUND);
            gc.fillRect(px, y0, pw, y1 - y0);
            paintSidePadding(gc, row, px, pw, left, cellWidth, y0, y1 - y0);
            drawRow(gc, row, left, top, baseline, cellWidth, lineHeight);
            rowHashes[rowIndex] = hash;
            bandMin = Math.min(bandMin, y0);
            bandMax = Math.max(bandMax, y1);

            if (rowIndex == 0) {
                gc.setFill(rowEdgeBackground(row, true));
                gc.fillRect(px, py, pw, top - py);
                bandMin = Math.min(bandMin, py);
            }
            if (rowIndex == lastRow) {
                gc.setFill(rowEdgeBackground(row, true));
                gc.fillRect(px, contentBottom, pw, py + ph - contentBottom);
                bandMax = Math.max(bandMax, py + ph);
            }
        }

        lastCursor = cursor;
        if (bandMin > bandMax) {
            return true;
        }
        drawCursor(gc, snapshot, left, top, cellWidth, lineHeight);
        gc.save();
        clipRect(gc, px, bandMin, pw, bandMax - bandMin);
        drawBorder(gc, px, py, pw, ph, active);
        gc.restore();
        return true;
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
            rememberRow(row);
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
        lastCursor = CursorState.from(snapshot);
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

    private void rememberSnapshot(RenderStateSnapshot snapshot) {
        if (snapshot == null) {
            rowHashes = new long[0];
            lastCursor = CursorState.none();
            return;
        }
        if (rowHashes.length != snapshot.rows()) {
            rowHashes = new long[snapshot.rows()];
        }
        for (RenderRow row : snapshot.renderRows()) {
            rememberRow(row);
        }
        lastCursor = CursorState.from(snapshot);
    }

    private void rememberRow(RenderRow row) {
        if (row.row() >= 0 && row.row() < rowHashes.length) {
            rowHashes[row.row()] = rowHash(row);
        }
    }

    private static long rowHash(RenderRow row) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, row.row());
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
        if (color == null) {
            return mix(hash, -1);
        }
        return mix(hash, (color.red() << 16) | (color.green() << 8) | color.blue());
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
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
                .map(graphics -> !graphics.placements().isEmpty())
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
        return placement.image().map(snapshot -> {
            byte[] data = snapshot.data();
            KittyImageKey key = KittyImageKey.of(snapshot, data);
            Image cached = kittyImageCache.get(key);
            if (cached != null) {
                return cached;
            }

            kittyImageCache.keySet().removeIf(existing -> existing.id() == snapshot.id());
            Image decoded = decodeImage(snapshot, data);
            if (decoded != null) {
                kittyImageCache.put(key, decoded);
            }
            return decoded;
        }).orElse(null);
    }

    private Image decodeImage(KittyImageSnapshot snapshot, byte[] data) {
        if (snapshot.compression() != KittyImageCompression.NONE) {
            return null;
        }

        if (snapshot.format() == KittyImageFormat.PNG) {
            return new Image(new ByteArrayInputStream(data));
        }

        int width = Math.toIntExact(snapshot.width());
        int height = Math.toIntExact(snapshot.height());
        WritableImage image = new WritableImage(width, height);

        if (snapshot.format() == KittyImageFormat.RGBA) {
            image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getByteBgraInstance(), rgbaToBgra(data), 0, width * 4);
        } else if (snapshot.format() == KittyImageFormat.RGB) {
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

    // A kitty image is immutable for a given (id, number); re-transmitting under the same id
    // changes the number (and the snapshot below evicts stale entries by id anyway). So the
    // identity + dimensions + payload length are enough to key the decoded-image cache, and
    // we avoid fingerprinting the whole payload — which previously ran once per frame per
    // placement (O(image size)) just to look the image up.
    private record KittyImageKey(long id, long number, long width, long height, KittyImageFormat format, int dataLength) {
        private static KittyImageKey of(KittyImageSnapshot snapshot, byte[] data) {
            return new KittyImageKey(
                    snapshot.id(),
                    snapshot.number(),
                    snapshot.width(),
                    snapshot.height(),
                    snapshot.format(),
                    data.length
            );
        }
    }

    private record KittyPlaceholderKey(long imageId, long placementId) {
    }

    private record SourceRect(double x, double y, double width, double height) {
    }

    private record CursorState(boolean visible, boolean hasViewport, long viewportX, long viewportY, RenderCursorStyle style) {
        private static CursorState none() {
            return new CursorState(false, false, -1, -1, null);
        }

        private static CursorState from(RenderStateSnapshot snapshot) {
            if (snapshot == null) {
                return none();
            }
            boolean hasViewport = snapshot.cursorViewportHasValue();
            return new CursorState(
                    snapshot.cursorVisible(),
                    hasViewport,
                    hasViewport ? snapshot.cursorViewportX() : -1,
                    hasViewport ? snapshot.cursorViewportY() : -1,
                    snapshot.cursorStyle());
        }

        private long viewportRow() {
            return visible && hasViewport ? viewportY : -1;
        }
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
