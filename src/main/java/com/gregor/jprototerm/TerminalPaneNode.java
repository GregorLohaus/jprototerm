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
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.FontSmoothingType;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JavaFX node for one terminal pane. The pane is composed from JavaFX primitives: one node per
 * terminal row, kitty graphics as ImageView nodes, plus background/cursor/border nodes.
 */
final class TerminalPaneNode extends Region {
    private static final int DIRTY_PARTIAL = 1;
    private static final int DIRTY_FULL = 2;

    // Debug toggle: when set, skip the per-column repaint and always repaint the whole row.
    // Used to bisect partial-repaint artifacts (stale black bars near the cursor).
    private static final boolean FULL_ROW_REPAINT =
            Boolean.getBoolean("jprototerm.fullRowRepaint")
                    || "1".equals(System.getenv("JPROTOTERM_FULL_ROW_REPAINT"));

    // Debug toggle: paint each repaint run's cleared span red instead of clearing it to
    // transparent. If the black bars turn red, they are spans repaintColumns clears but never
    // refills; if they stay black, those pixels are never touched by repaintColumns at all.
    private static final boolean DEBUG_REPAINT =
            Boolean.getBoolean("jprototerm.debugRepaint")
                    || "1".equals(System.getenv("JPROTOTERM_DEBUG_REPAINT"));

    private static final Color DEFAULT_FOREGROUND = Color.rgb(225, 229, 235);
    private static final Color SELECTED_BACKGROUND = Color.rgb(52, 92, 140);
    private static final Color PANE_BACKGROUND = Color.rgb(9, 10, 12);
    private static final Map<Integer, Color> COLOR_CACHE = new HashMap<>();

    private final TerminalPane pane;
    private final TerminalMetrics metrics;
    private final Rectangle background = new Rectangle();
    private final Pane belowImageLayer = new Pane();
    private final Pane rowLayer = new Pane();
    private final Pane cursorLayer = new Pane();
    private final Pane aboveImageLayer = new Pane();
    private final Rectangle topPadding = new Rectangle();
    private final Rectangle bottomPadding = new Rectangle();
    private final Rectangle border = new Rectangle();
    private final Map<Integer, TerminalRowNode> rows = new HashMap<>();
    private final Map<Integer, Long> rowFingerprints = new HashMap<>();
    private final Map<KittyImageKey, Image> kittyImageCache = new HashMap<>();
    private long drawnContentVersion = Long.MIN_VALUE;
    private double drawnWidth = -1.0;
    private double drawnHeight = -1.0;

    TerminalPaneNode(TerminalPane pane, TerminalMetrics metrics) {
        this.pane = pane;
        this.metrics = metrics;
        setPickOnBounds(true);
        setClip(new Rectangle());
        background.setFill(PANE_BACKGROUND);
        border.setFill(Color.TRANSPARENT);
        getChildren().setAll(background, belowImageLayer, rowLayer, cursorLayer, aboveImageLayer, border);
        rowLayer.getChildren().setAll(topPadding, bottomPadding);
    }

    void discard() {
        drawnContentVersion = Long.MIN_VALUE;
        drawnWidth = -1.0;
        drawnHeight = -1.0;
        rows.clear();
        rowFingerprints.clear();
        rowLayer.getChildren().setAll(topPadding, bottomPadding);
        belowImageLayer.getChildren().clear();
        aboveImageLayer.getChildren().clear();
        cursorLayer.getChildren().clear();
    }

    void renderFull(boolean active) {
        prepareGeometry();
        long snapshotStart = RenderProfiler.start();
        RenderStateSnapshot snapshot = pane.snapshotFull();
        RenderProfiler.stop(RenderProfiler.SNAPSHOT, snapshotStart);
        long renderedVersion = pane.snapshotVersion();
        boolean withKitty = pane.kittyEnabled() && hasKittyGraphics();
        updateRowsFull(snapshot);
        updateKittyGraphics(snapshot, withKitty);
        updateCursor(snapshot);
        updateBorder(active);
        markDrawn(renderedVersion);
    }

    void renderIncremental(boolean active) {
        long frameStart = RenderProfiler.start();
        renderIncrementalBody(active);
        RenderProfiler.stop(RenderProfiler.FRAME, frameStart);
        RenderProfiler.frame();
    }

    private void renderIncrementalBody(boolean active) {
        boolean geometryChanged = prepareGeometry();
        boolean withKitty = pane.kittyEnabled() && hasKittyGraphics();
        if (drawnContentVersion == Long.MIN_VALUE || geometryChanged || withKitty) {
            renderFull(active);
            return;
        }
        if (drawnContentVersion == pane.contentVersion()) {
            updateBorder(active);
            return;
        }

        long snapshotStart = RenderProfiler.start();
        RenderStateSnapshot snapshot = pane.snapshot();
        RenderProfiler.stop(RenderProfiler.SNAPSHOT, snapshotStart);
        long renderedVersion = pane.snapshotVersion();
        int dirty = snapshot == null ? DIRTY_FULL : snapshot.dirty();
        if (dirty == DIRTY_FULL) {
            updateChangedRows(snapshot, snapshot.renderRows());
        } else if (dirty == DIRTY_PARTIAL) {
            updateDirtyRows(snapshot);
        }
        updateKittyGraphics(snapshot, false);
        updateCursor(snapshot);
        updateBorder(active);
        markDrawn(renderedVersion);
    }

    private boolean prepareGeometry() {
        double width = Math.max(0.0, pane.width());
        double height = Math.max(0.0, pane.height());
        boolean changed = drawnWidth != width || drawnHeight != height;
        resize(width, height);
        background.setWidth(width);
        background.setHeight(height);
        resizeLayer(belowImageLayer, width, height);
        resizeLayer(rowLayer, width, height);
        resizeLayer(cursorLayer, width, height);
        resizeLayer(aboveImageLayer, width, height);
        border.setWidth(Math.max(0.0, width - 1.0));
        border.setHeight(Math.max(0.0, height - 1.0));
        border.relocate(0.5, 0.5);
        Node clip = getClip();
        if (clip instanceof Rectangle rectangle) {
            rectangle.setWidth(width);
            rectangle.setHeight(height);
        }
        return changed;
    }

    private static void resizeLayer(Pane layer, double width, double height) {
        layer.resizeRelocate(0.0, 0.0, width, height);
    }

    private void updateRowsFull(RenderStateSnapshot snapshot) {
        if (snapshot == null) {
            rows.clear();
            rowFingerprints.clear();
            rowLayer.getChildren().setAll(topPadding, bottomPadding);
            return;
        }

        List<Node> ordered = new ArrayList<>(snapshot.renderRows().size() + 2);
        ordered.add(topPadding);
        ordered.add(bottomPadding);
        Set<Integer> liveRows = new HashSet<>();
        for (RenderRow row : snapshot.renderRows()) {
            TerminalRowNode node = rowNode(row.row());
            long fpStart = RenderProfiler.start();
            long fingerprint = rowFingerprint(row);
            RenderProfiler.stop(RenderProfiler.FINGERPRINT, fpStart);
            node.render(row);
            rowFingerprints.put(row.row(), fingerprint);
            liveRows.add(row.row());
            ordered.add(node);
        }
        rows.keySet().retainAll(liveRows);
        rowFingerprints.keySet().retainAll(liveRows);
        rowLayer.getChildren().setAll(ordered);
        updateVerticalPadding(snapshot);
    }

    private void updateDirtyRows(RenderStateSnapshot snapshot) {
        List<RenderRow> dirtyRows = snapshot.renderRows().stream()
                .filter(RenderRow::dirty)
                .toList();
        updateChangedRows(snapshot, dirtyRows);
    }

    private void updateChangedRows(RenderStateSnapshot snapshot, List<RenderRow> changedRows) {
        if (snapshot == null || changedRows.isEmpty()) {
            return;
        }

        Set<Integer> movedRows = moveShiftedRows(snapshot, changedRows);
        for (RenderRow row : snapshot.renderRows()) {
            if (!changedRows.contains(row) || movedRows.contains(row.row())) {
                continue;
            }
            TerminalRowNode node = rowNode(row.row());
            long fpStart = RenderProfiler.start();
            long fingerprint = rowFingerprint(row);
            RenderProfiler.stop(RenderProfiler.FINGERPRINT, fpStart);
            node.renderChanged(row);
            rowFingerprints.put(row.row(), fingerprint);
        }
        for (RenderRow row : changedRows) {
            updateDirtyVerticalPadding(snapshot, row);
        }
        syncRowChildren();
    }

    private Set<Integer> moveShiftedRows(RenderStateSnapshot snapshot, List<RenderRow> changedRows) {
        if (rowFingerprints.isEmpty() || changedRows.size() < Math.max(4, snapshot.rows() / 3)) {
            return Set.of();
        }

        long shiftStart = RenderProfiler.start();
        ShiftPlan plan = detectShift(snapshot, changedRows);
        RenderProfiler.stop(RenderProfiler.FINGERPRINT, shiftStart);
        if (plan == null) {
            return Set.of();
        }

        Map<Integer, TerminalRowNode> oldRows = new HashMap<>(rows);
        Map<Integer, Long> oldFingerprints = new HashMap<>(rowFingerprints);
        for (RowMove move : plan.moves()) {
            rows.remove(move.sourceRow());
            rowFingerprints.remove(move.sourceRow());
        }
        for (RowMove move : plan.moves()) {
            TerminalRowNode node = oldRows.get(move.sourceRow());
            if (node == null) {
                continue;
            }
            node.moveToRow(move.targetRow());
            rows.put(move.targetRow(), node);
            rowFingerprints.put(move.targetRow(), oldFingerprints.get(move.sourceRow()));
        }
        return plan.targetRows();
    }

    private ShiftPlan detectShift(RenderStateSnapshot snapshot, List<RenderRow> changedRows) {
        int rowCount = snapshot.rows();
        int changedCount = changedRows.size();
        // The new-content hash of each changed row is invariant across the delta scan below,
        // so compute it once here instead of re-hashing the whole row for every candidate delta.
        long[] changedHashes = new long[changedCount];
        for (int i = 0; i < changedCount; i++) {
            changedHashes[i] = rowFingerprint(changedRows.get(i));
        }

        int bestDelta = 0;
        int bestScore = 0;
        for (int delta = -rowCount + 1; delta < rowCount; delta++) {
            if (delta == 0) {
                continue;
            }
            int score = 0;
            for (int i = 0; i < changedCount; i++) {
                int sourceRow = changedRows.get(i).row() + delta;
                if (sourceRow < 0 || sourceRow >= rowCount || !rows.containsKey(sourceRow)) {
                    continue;
                }
                Long previous = rowFingerprints.get(sourceRow);
                if (previous != null && previous == changedHashes[i]) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestDelta = delta;
            }
        }

        int threshold = Math.max(4, (changedRows.size() * 2 + 2) / 3);
        if (bestScore < threshold) {
            return null;
        }

        List<RowMove> moves = new ArrayList<>(bestScore);
        Set<Integer> targetRows = new HashSet<>();
        for (int i = 0; i < changedCount; i++) {
            RenderRow row = changedRows.get(i);
            int sourceRow = row.row() + bestDelta;
            if (sourceRow < 0 || sourceRow >= rowCount || !rows.containsKey(sourceRow)) {
                continue;
            }
            Long previous = rowFingerprints.get(sourceRow);
            if (previous != null && previous == changedHashes[i]) {
                moves.add(new RowMove(sourceRow, row.row()));
                targetRows.add(row.row());
            }
        }
        return new ShiftPlan(moves, targetRows);
    }

    private void syncRowChildren() {
        List<Node> ordered = new ArrayList<>(rows.size() + 2);
        ordered.add(topPadding);
        ordered.add(bottomPadding);
        rows.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .forEach(ordered::add);
        rowLayer.getChildren().setAll(ordered);
    }

    private TerminalRowNode rowNode(int row) {
        return rows.computeIfAbsent(row, ignored -> {
            TerminalRowNode created = new TerminalRowNode(metrics);
            if (!rowLayer.getChildren().contains(created)) {
                rowLayer.getChildren().add(created);
            }
            return created;
        });
    }

    private void updateVerticalPadding(RenderStateSnapshot snapshot) {
        List<RenderRow> renderRows = snapshot.renderRows();
        if (renderRows.isEmpty()) {
            topPadding.setVisible(false);
            bottomPadding.setVisible(false);
            return;
        }

        double width = pane.width();
        double top = TerminalMetrics.PADDING;
        double contentBottom = top + snapshot.rows() * metrics.lineHeight();
        topPadding.setVisible(true);
        topPadding.setFill(rowEdgeBackground(renderRows.get(0), true));
        topPadding.relocate(0.0, 0.0);
        topPadding.setWidth(width);
        topPadding.setHeight(top);

        bottomPadding.setVisible(true);
        bottomPadding.setFill(rowEdgeBackground(renderRows.get(renderRows.size() - 1), true));
        bottomPadding.relocate(0.0, contentBottom);
        bottomPadding.setWidth(width);
        bottomPadding.setHeight(Math.max(0.0, pane.height() - contentBottom));
    }

    private void updateDirtyVerticalPadding(RenderStateSnapshot snapshot, RenderRow row) {
        if (row.row() == 0) {
            topPadding.setVisible(true);
            topPadding.setFill(rowEdgeBackground(row, true));
            topPadding.relocate(0.0, 0.0);
            topPadding.setWidth(pane.width());
            topPadding.setHeight(TerminalMetrics.PADDING);
        }
        if (row.row() == snapshot.rows() - 1) {
            double contentBottom = TerminalMetrics.PADDING + snapshot.rows() * metrics.lineHeight();
            bottomPadding.setVisible(true);
            bottomPadding.setFill(rowEdgeBackground(row, true));
            bottomPadding.relocate(0.0, contentBottom);
            bottomPadding.setWidth(pane.width());
            bottomPadding.setHeight(Math.max(0.0, pane.height() - contentBottom));
        }
    }

    private void updateCursor(RenderStateSnapshot snapshot) {
        cursorLayer.getChildren().clear();
        if (snapshot == null || !snapshot.cursorVisible() || !snapshot.cursorViewportHasValue()) {
            return;
        }

        double x = TerminalMetrics.PADDING + (snapshot.cursorViewportX() * metrics.cellWidth());
        double y = TerminalMetrics.PADDING + (snapshot.cursorViewportY() * metrics.lineHeight());
        double cellWidth = metrics.cellWidth();
        double lineHeight = metrics.lineHeight();
        RenderCursorStyle style = snapshot.cursorStyle();
        if (style == RenderCursorStyle.BAR) {
            Line line = new Line(x + 0.5, y + 2.0, x + 0.5, y + lineHeight - 2.0);
            line.setStroke(DEFAULT_FOREGROUND);
            line.setStrokeWidth(1.5);
            cursorLayer.getChildren().add(line);
        } else if (style == RenderCursorStyle.UNDERLINE) {
            Line line = new Line(x + 1.0, y + lineHeight - 2.0, x + cellWidth - 1.0, y + lineHeight - 2.0);
            line.setStroke(DEFAULT_FOREGROUND);
            line.setStrokeWidth(1.5);
            cursorLayer.getChildren().add(line);
        } else if (style == RenderCursorStyle.BLOCK) {
            Rectangle rectangle = new Rectangle(x + 0.5, y + 1.0, Math.max(1.0, cellWidth - 1.0), Math.max(1.0, lineHeight - 2.0));
            rectangle.setFill(Color.rgb(225, 229, 235, 0.28));
            cursorLayer.getChildren().add(rectangle);
        } else {
            Rectangle rectangle = new Rectangle(x + 0.5, y + 1.0, Math.max(1.0, cellWidth - 1.0), Math.max(1.0, lineHeight - 2.0));
            rectangle.setFill(Color.TRANSPARENT);
            rectangle.setStroke(DEFAULT_FOREGROUND);
            rectangle.setStrokeWidth(1.5);
            cursorLayer.getChildren().add(rectangle);
        }
    }

    private void updateBorder(boolean active) {
        border.setStroke(active ? Color.rgb(87, 166, 255) : Color.rgb(52, 57, 65));
        border.setStrokeWidth(active ? 2.0 : 1.0);
    }

    private void updateKittyGraphics(RenderStateSnapshot snapshot, boolean withKitty) {
        belowImageLayer.getChildren().clear();
        aboveImageLayer.getChildren().clear();
        if (!withKitty || snapshot == null) {
            return;
        }

        Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds = kittyPlaceholderBounds(snapshot);
        addKittyGraphics(belowImageLayer, KittyPlacementLayer.BELOW_TEXT, placeholderBounds);
        addKittyGraphics(aboveImageLayer, KittyPlacementLayer.ABOVE_TEXT, placeholderBounds);
    }

    private void addKittyGraphics(Pane layer, KittyPlacementLayer placementLayer, Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds) {
        pane.kittyGraphics().ifPresent(graphics -> {
            for (KittyPlacement placement : graphics.placements(placementLayer)) {
                Image image = imageFor(placement);
                if (image == null) {
                    continue;
                }
                ImageView view = placement.virtual()
                        ? virtualKittyView(placement, image, placeholderBounds)
                        : pinnedKittyView(placement, image);
                if (view != null) {
                    layer.getChildren().add(view);
                }
            }
        });
    }

    private ImageView pinnedKittyView(KittyPlacement placement, Image image) {
        KittyRenderInfo renderInfo = placement.renderInfo().orElse(null);
        if (renderInfo == null || !renderInfo.viewportVisible()) {
            return null;
        }

        double sourceX = renderInfo.sourceX();
        double sourceY = renderInfo.sourceY();
        double sourceWidth = renderInfo.sourceWidth();
        double sourceHeight = renderInfo.sourceHeight();
        if (sourceWidth <= 0.0 || sourceHeight <= 0.0) {
            return null;
        }

        double x = TerminalMetrics.PADDING + (renderInfo.viewportColumn() * metrics.cellWidth()) + placement.xOffset();
        double y = TerminalMetrics.PADDING + (renderInfo.viewportRow() * metrics.lineHeight()) + placement.yOffset();
        double width = renderInfo.pixelWidth() > 0 ? renderInfo.pixelWidth() : renderInfo.gridColumns() * metrics.cellWidth();
        double height = renderInfo.pixelHeight() > 0 ? renderInfo.pixelHeight() : renderInfo.gridRows() * metrics.lineHeight();
        return imageView(image, sourceX, sourceY, sourceWidth, sourceHeight, x, y, width, height);
    }

    private ImageView virtualKittyView(KittyPlacement placement, Image image, Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds) {
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
            return null;
        }

        SourceRect source = sourceRect(placement, image);
        if (source.width() <= 0.0 || source.height() <= 0.0) {
            return null;
        }

        long gridColumns = gridColumns(placement, bounds);
        long gridRows = gridRows(placement, bounds);
        double sourceCellWidth = source.width() / Math.max(1L, gridColumns);
        double sourceCellHeight = source.height() / Math.max(1L, gridRows);

        double sourceX = source.x() + (bounds.minSourceColumn * sourceCellWidth);
        double sourceY = source.y() + (bounds.minSourceRow * sourceCellHeight);
        double sourceWidth = bounds.sourceColumns() * sourceCellWidth;
        double sourceHeight = bounds.sourceRows() * sourceCellHeight;
        double x = TerminalMetrics.PADDING + (bounds.minColumn * metrics.cellWidth());
        double y = TerminalMetrics.PADDING + (bounds.minRow * metrics.lineHeight());
        double availableWidth = bounds.columns() * metrics.cellWidth();
        double availableHeight = bounds.rows() * metrics.lineHeight();
        if (sourceWidth <= 0.0 || sourceHeight <= 0.0 || availableWidth <= 0.0 || availableHeight <= 0.0) {
            return null;
        }

        double scale = Math.min(availableWidth / sourceWidth, availableHeight / sourceHeight);
        return imageView(image, sourceX, sourceY, sourceWidth, sourceHeight, x, y, sourceWidth * scale, sourceHeight * scale);
    }

    private static ImageView imageView(Image image, double sourceX, double sourceY, double sourceWidth, double sourceHeight,
            double x, double y, double width, double height) {
        if (width <= 0.0 || height <= 0.0) {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setViewport(new Rectangle2D(sourceX, sourceY, sourceWidth, sourceHeight));
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(false);
        view.relocate(x, y);
        return view;
    }

    private boolean hasKittyGraphics() {
        return pane.kittyGraphics()
                .map(graphics -> !graphics.placements().isEmpty())
                .orElse(false);
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

    private static Image decodeImage(KittyImageSnapshot snapshot, byte[] data) {
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

    private void markDrawn(long renderedVersion) {
        drawnContentVersion = renderedVersion;
        drawnWidth = pane.width();
        drawnHeight = pane.height();
    }

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

    private static Color cellBackgroundOverride(RenderCell cell) {
        if (cell.inverse()) {
            var fg = cell.foreground();
            return fg.isPresent() ? toFxColor(fg.get()) : DEFAULT_FOREGROUND;
        }
        var bg = cell.background();
        return bg.isPresent() ? toFxColor(bg.get()) : null;
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

    private static long rowFingerprint(RenderRow row) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, row.cells().size());
        for (RenderCell cell : row.cells()) {
            hash = mix(hash, cellFingerprint(cell));
        }
        return hash;
    }

    private static long cellFingerprint(RenderCell cell) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, cell.column());
        hash = mix(hash, cell.inverse() ? 1 : 0);
        hash = mix(hash, cell.selected() ? 1 : 0);
        hash = mix(hash, colorFingerprint(cell.foreground().orElse(null)));
        hash = mix(hash, colorFingerprint(cell.background().orElse(null)));
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
        return hash;
    }

    private static long colorFingerprint(RenderColor color) {
        if (color == null) {
            return -1L;
        }
        return ((long) color.red() << 16) | ((long) color.green() << 8) | color.blue();
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static final class TerminalRowNode extends Region {
        private final TerminalMetrics metrics;
        private final Canvas canvas = new Canvas();
        private long[] cellFingerprints = new long[0];

        private TerminalRowNode(TerminalMetrics metrics) {
            this.metrics = metrics;
            getChildren().add(canvas);
        }

        private void render(RenderRow row) {
            prepareCanvas(row);

            long drawStart = RenderProfiler.start();
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.clearRect(0.0, 0.0, canvas.getWidth(), canvas.getHeight());
            gc.setFontSmoothingType(FontSmoothingType.LCD);
            gc.setFont(metrics.font());

            paintSidePadding(gc, row, canvas.getWidth(), canvas.getHeight());
            drawRow(gc, row, rowTop(row), metrics.cellWidth(), metrics.lineHeight());
            RenderProfiler.stop(RenderProfiler.DRAW, drawStart);

            long fpStart = RenderProfiler.start();
            cellFingerprints = cellFingerprints(row);
            RenderProfiler.stop(RenderProfiler.FINGERPRINT, fpStart);
        }

        private void renderChanged(RenderRow row) {
            if (FULL_ROW_REPAINT) {
                render(row);
                return;
            }
            double oldWidth = canvas.getWidth();
            double oldHeight = canvas.getHeight();
            prepareCanvas(row);
            long fpStart = RenderProfiler.start();
            long[] nextFingerprints = cellFingerprints(row);
            RenderProfiler.stop(RenderProfiler.FINGERPRINT, fpStart);
            if (cellFingerprints.length != nextFingerprints.length
                    || oldWidth != canvas.getWidth()
                    || oldHeight != canvas.getHeight()) {
                render(row);
                return;
            }

            long drawStart = RenderProfiler.start();
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.setFontSmoothingType(FontSmoothingType.LCD);
            gc.setFont(metrics.font());

            int runStart = -1;
            int runEnd = -1;
            for (int column = 0; column < nextFingerprints.length; column++) {
                if (cellFingerprints[column] == nextFingerprints[column]) {
                    continue;
                }

                int start = Math.max(0, column - 1);
                int end = Math.min(nextFingerprints.length - 1, column + 1);
                if (runStart < 0) {
                    runStart = start;
                    runEnd = end;
                } else if (start <= runEnd + 1) {
                    runEnd = Math.max(runEnd, end);
                } else {
                    repaintColumns(gc, row, runStart, runEnd);
                    runStart = start;
                    runEnd = end;
                }
            }
            if (runStart >= 0) {
                repaintColumns(gc, row, runStart, runEnd);
            }
            RenderProfiler.stop(RenderProfiler.DRAW, drawStart);
            cellFingerprints = nextFingerprints;
        }

        private void prepareCanvas(RenderRow row) {
            double paneWidth = ((Region) getParent()).getWidth();
            double rowTop = rowTop(row);
            double rowBottom = rowBottom(row);
            double rowHeight = Math.max(1.0, rowBottom - rowTop);
            resizeRelocate(0.0, rowTop, paneWidth, rowHeight);
            canvas.setWidth(Math.max(0.0, paneWidth));
            canvas.setHeight(rowHeight);
        }

        private void moveToRow(int row) {
            double paneWidth = ((Region) getParent()).getWidth();
            double rowTop = rowTop(row);
            double rowBottom = rowBottom(row);
            double rowHeight = Math.max(1.0, rowBottom - rowTop);
            resizeRelocate(0.0, rowTop, paneWidth, rowHeight);
            canvas.setWidth(Math.max(0.0, paneWidth));
            canvas.setHeight(rowHeight);
        }

        private double rowTop(RenderRow row) {
            return rowTop(row.row());
        }

        private double rowTop(int row) {
            return Math.floor(TerminalMetrics.PADDING + row * metrics.lineHeight());
        }

        private double rowBottom(RenderRow row) {
            return rowBottom(row.row());
        }

        private double rowBottom(int row) {
            return Math.ceil(TerminalMetrics.PADDING + (row + 1) * metrics.lineHeight());
        }

        private void repaintColumns(GraphicsContext gc, RenderRow row, int startColumn, int endColumn) {
            if (endColumn < startColumn) {
                return;
            }

            double cellWidth = metrics.cellWidth();
            double lineHeight = metrics.lineHeight();
            double rowTop = rowTop(row);
            double contentTop = TerminalMetrics.PADDING + row.row() * lineHeight;
            double localCellTop = contentTop - rowTop;
            double baseline = TerminalMetrics.PADDING + metrics.baselineOffset() + row.row() * lineHeight - rowTop;
            double x = TerminalMetrics.PADDING + startColumn * cellWidth;
            double width = (endColumn - startColumn + 1) * cellWidth;

            // Opaque base fill rather than clearRect: a transparent clear leaves the run's
            // fractional edge pixels transparent, which show the near-black pane background
            // as a thin seam bar against the adjacent (un-repainted) line. Filling opaque
            // removes every transparent pixel; per-cell backgrounds then paint on top, and
            // default-background cells correctly show PANE_BACKGROUND. Safe because the
            // per-column path never runs while kitty graphics (which need a transparent row
            // canvas for below-text images) are present.
            gc.setFill(DEBUG_REPAINT ? Color.RED : PANE_BACKGROUND);
            gc.fillRect(x, 0.0, width, canvas.getHeight());
            if (startColumn == 0) {
                gc.setFill(rowEdgeBackground(row, true));
                gc.fillRect(0.0, 0.0, TerminalMetrics.PADDING, canvas.getHeight());
            }
            if (endColumn >= row.cells().size() - 1) {
                double contentRight = TerminalMetrics.PADDING + row.cells().size() * cellWidth;
                gc.setFill(rowEdgeBackground(row, false));
                gc.fillRect(contentRight, 0.0, canvas.getWidth() - contentRight, canvas.getHeight());
            }

            drawRowBackgrounds(gc, row, localCellTop, cellWidth, lineHeight, startColumn, endColumn);
            drawRowText(gc, row, baseline, cellWidth, startColumn, endColumn);
        }

        private void paintSidePadding(GraphicsContext gc, RenderRow row, double paneWidth, double bandHeight) {
            int columns = row.cells().size();
            if (columns == 0) {
                return;
            }
            double contentLeft = TerminalMetrics.PADDING;
            double contentRight = contentLeft + columns * metrics.cellWidth();
            gc.setFill(rowEdgeBackground(row, true));
            gc.fillRect(0.0, 0.0, contentLeft, bandHeight);
            gc.setFill(rowEdgeBackground(row, false));
            gc.fillRect(contentRight, 0.0, paneWidth - contentRight, bandHeight);
        }

        private void drawRow(GraphicsContext gc, RenderRow row, double rowTop, double cellWidth, double lineHeight) {
            double contentTop = TerminalMetrics.PADDING + row.row() * lineHeight;
            double localCellTop = contentTop - rowTop;
            double baseline = TerminalMetrics.PADDING + metrics.baselineOffset() + row.row() * lineHeight - rowTop;
            drawRowBackgrounds(gc, row, localCellTop, cellWidth, lineHeight, 0, row.cells().size() - 1);
            drawRowText(gc, row, baseline, cellWidth, 0, row.cells().size() - 1);
        }

        private void drawRowBackgrounds(GraphicsContext gc, RenderRow row, double localCellTop,
                double cellWidth, double lineHeight, int startColumn, int endColumn) {
            Color runBackground = null;
            int runStartColumn = 0;
            int previousColumn = -1;
            for (RenderCell cell : row.cells()) {
                if (cell.column() < startColumn || cell.column() > endColumn) {
                    continue;
                }
                if (cell.kittyPlaceholder().isPresent()) {
                    flushBackgroundRun(gc, runBackground, localCellTop, cellWidth, lineHeight, runStartColumn, previousColumn);
                    runBackground = null;
                    previousColumn = -1;
                    continue;
                }

                Color background = cell.selected() ? SELECTED_BACKGROUND : cellBackgroundOverride(cell);
                if (background == null) {
                    flushBackgroundRun(gc, runBackground, localCellTop, cellWidth, lineHeight, runStartColumn, previousColumn);
                    runBackground = null;
                    previousColumn = -1;
                    continue;
                }

                if (runBackground == null || background != runBackground || cell.column() != previousColumn + 1) {
                    flushBackgroundRun(gc, runBackground, localCellTop, cellWidth, lineHeight, runStartColumn, previousColumn);
                    runBackground = background;
                    runStartColumn = cell.column();
                }
                previousColumn = cell.column();
            }
            flushBackgroundRun(gc, runBackground, localCellTop, cellWidth, lineHeight, runStartColumn, previousColumn);
        }

        private void flushBackgroundRun(GraphicsContext gc, Color background, double localCellTop,
                double cellWidth, double lineHeight, int startColumn, int endColumn) {
            if (background == null || endColumn < startColumn) {
                return;
            }
            gc.setFill(background);
            gc.fillRect(
                    TerminalMetrics.PADDING + startColumn * cellWidth,
                    localCellTop,
                    (endColumn - startColumn + 1) * cellWidth,
                    lineHeight);
        }

        private void drawRowText(GraphicsContext gc, RenderRow row, double baseline,
                double cellWidth, int startColumn, int endColumn) {
            for (RenderCell cell : row.cells()) {
                if (cell.column() < startColumn || cell.column() > endColumn) {
                    continue;
                }
                if (cell.kittyPlaceholder().isPresent() || cell.codepoints().length == 0) {
                    continue;
                }

                gc.setFill(cellForegroundColor(cell));
                gc.fillText(cell.text(), TerminalMetrics.PADDING + cell.column() * cellWidth, baseline);
            }
        }

        private static long[] cellFingerprints(RenderRow row) {
            int columns = row.cells().size();
            for (RenderCell cell : row.cells()) {
                columns = Math.max(columns, cell.column() + 1);
            }

            long[] fingerprints = new long[columns];
            for (RenderCell cell : row.cells()) {
                fingerprints[cell.column()] = cellFingerprint(cell);
            }
            return fingerprints;
        }
    }

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

    private record RowMove(int sourceRow, int targetRow) {
    }

    private record ShiftPlan(List<RowMove> moves, Set<Integer> targetRows) {
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
