package com.gregor.jprototerm;

import dev.jlibghostty.KittyImageCompression;
import dev.jlibghostty.KittyImageFormat;
import dev.jlibghostty.KittyImageSnapshot;
import dev.jlibghostty.KittyPlacement;
import dev.jlibghostty.KittyPlacementLayer;
import dev.jlibghostty.KittyPlaceholder;
import dev.jlibghostty.KittyRenderInfo;
import dev.jlibghostty.KeyModifiers;
import dev.jlibghostty.MouseButton;
import dev.jlibghostty.MouseEncoderSize;
import dev.jlibghostty.MouseInput;
import dev.jlibghostty.RenderCell;
import dev.jlibghostty.RenderColor;
import dev.jlibghostty.RenderCursorStyle;
import dev.jlibghostty.RenderRow;
import dev.jlibghostty.RenderStateSnapshot;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.InputEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ScrollEvent.VerticalTextScrollUnits;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TerminalCanvasView {
    private static final Color DEFAULT_FOREGROUND = Color.rgb(225, 229, 235);
    private static final Color SELECTED_BACKGROUND = Color.rgb(52, 92, 140);

    private final Canvas canvas = new Canvas();
    private final TerminalWorkspace workspace;
    private final AppConfig config;
    private final Map<KittyImageKey, Image> kittyImageCache = new HashMap<>();
    private final Map<TerminalPane, PaneRenderCache> paneRenderCache = new HashMap<>();
    private String fontFamily;
    private double fontSize;
    private Font cachedFont;
    private FontMetrics cachedMetrics;
    private String cachedFontFamily;
    private double cachedFontSize;
    private String lastRenderKey;
    private boolean mouseButtonPressed;
    private MouseButton pressedButton = MouseButton.UNKNOWN;

    public TerminalCanvasView(TerminalWorkspace workspace, AppConfig config) {
        this.workspace = workspace;
        this.config = config;
        this.fontFamily = config.fontFamily();
        this.fontSize = config.fontSize();
        canvas.setFocusTraversable(true);
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseReleased(this::handleMouseReleased);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnMouseMoved(this::handleMouseMoved);
        canvas.setOnScroll(this::handleScroll);
    }

    public Canvas canvas() {
        return canvas;
    }

    public void setFont(String family, double size) {
        this.fontFamily = family;
        this.fontSize = size;
        cachedFont = null;
        cachedMetrics = null;
        paneRenderCache.clear();
        lastRenderKey = null;
    }

    public void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        workspace.layout(width, height);
        Font font = currentFont();
        FontMetrics metrics = currentFontMetrics();
        List<TerminalPane> panes = workspace.panes();

        String renderKey = renderKey(width, height, metrics, panes);
        if (renderKey.equals(lastRenderKey)) {
            return;
        }
        lastRenderKey = renderKey;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.rgb(16, 16, 18));
        gc.fillRect(0, 0, width, height);
        gc.setFontSmoothingType(FontSmoothingType.LCD);

        paneRenderCache.keySet().removeIf(pane -> !panes.contains(pane));
        for (TerminalPane pane : panes) {
            drawPane(gc, pane, font, metrics);
        }
    }

    private void drawPane(GraphicsContext gc, TerminalPane pane, Font font, FontMetrics metrics) {
        if (config.kittyGraphics() && paneHasKittyGraphics(pane)) {
            paneRenderCache.remove(pane);
            gc.save();
            gc.beginPath();
            gc.rect(pane.x(), pane.y(), pane.width(), pane.height());
            gc.clip();
            drawPaneContent(gc, pane, font, metrics, pane.x(), pane.y(), pane.width(), pane.height(), false);
            gc.restore();
            return;
        }

        PaneRenderCache cache = paneRenderCache.computeIfAbsent(pane, ignored -> new PaneRenderCache());
        String cacheKey = paneCacheKey(pane, metrics);
        int imageWidth = Math.max(1, (int) Math.ceil(pane.width()));
        int imageHeight = Math.max(1, (int) Math.ceil(pane.height()));
        if (cache.image == null || cache.canvas == null || cache.imageWidth != imageWidth || cache.imageHeight != imageHeight || !cacheKey.equals(cache.key)) {
            cache.canvas = new Canvas(imageWidth, imageHeight);
            drawPaneContent(cache.canvas.getGraphicsContext2D(), pane, font, metrics, 0.0, 0.0, imageWidth, imageHeight, true);
            cache.image = new WritableImage(imageWidth, imageHeight);
            cache.canvas.snapshot(null, cache.image);
            cache.imageWidth = imageWidth;
            cache.imageHeight = imageHeight;
            cache.key = cacheKey;
        }

        gc.drawImage(cache.image, pane.x(), pane.y());
    }

    private void drawPaneContent(
            GraphicsContext gc,
            TerminalPane pane,
            Font font,
            FontMetrics metrics,
            double x,
            double y,
            double width,
            double height,
            boolean clear
    ) {
        if (clear) {
            gc.clearRect(x, y, width, height);
        }
        gc.setFontSmoothingType(FontSmoothingType.LCD);
        if (pane.floating()) {
            gc.setGlobalAlpha(0.96);
        }
        gc.setFill(Color.rgb(9, 10, 12));
        gc.fillRect(x, y, width, height);
        gc.setGlobalAlpha(1.0);

        gc.setStroke(workspace.isActive(pane) ? Color.rgb(87, 166, 255) : Color.rgb(52, 57, 65));
        gc.setLineWidth(workspace.isActive(pane) ? 2.0 : 1.0);
        gc.strokeRect(x + 0.5, y + 0.5, width - 1.0, height - 1.0);

        gc.setFont(font);

        int columns = Math.max(1, (int) ((width - 24.0) / metrics.cellWidth));
        int rows = Math.max(1, (int) ((height - 24.0) / metrics.lineHeight));
        pane.resize(columns, rows, (int) Math.round(metrics.cellWidth), (int) Math.round(metrics.lineHeight));

        double left = x + 12.0;
        double top = y + 12.0;
        double baseline = top + metrics.baselineOffset;

        RenderStateSnapshot snapshot = pane.renderSnapshot();
        Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds = config.kittyGraphics()
                ? kittyPlaceholderBounds(snapshot)
                : Map.of();

        if (config.kittyGraphics()) {
            drawKittyGraphics(gc, pane, KittyPlacementLayer.BELOW_TEXT, placeholderBounds, left, top, metrics.cellWidth, metrics.lineHeight);
        }

        if (snapshot != null) {
            for (RenderRow row : snapshot.renderRows()) {
                drawRow(gc, row, left, top, baseline, metrics.cellWidth, metrics.lineHeight);
            }
        }

        if (snapshot != null) {
            drawCursor(gc, snapshot, left, top, metrics.cellWidth, metrics.lineHeight);
        }

        if (config.kittyGraphics()) {
            drawKittyGraphics(gc, pane, KittyPlacementLayer.ABOVE_TEXT, placeholderBounds, left, top, metrics.cellWidth, metrics.lineHeight);
        }
    }

    private static FontMetrics measureFontMetrics(Font font) {
        Text text = new Text("┃MgÅjy");
        text.setFont(font);
        double lineHeight = Math.max(1.0, text.getLayoutBounds().getHeight());
        double baselineOffset = -text.getLayoutBounds().getMinY();

        Text cell = new Text("M");
        cell.setFont(font);
        double cellWidth = Math.max(1.0, cell.getLayoutBounds().getWidth());
        return new FontMetrics(cellWidth, lineHeight, baselineOffset);
    }

    private Font currentFont() {
        if (cachedFont == null || !fontFamily.equals(cachedFontFamily) || fontSize != cachedFontSize) {
            cachedFont = Font.font(fontFamily, fontSize);
            cachedMetrics = null;
            cachedFontFamily = fontFamily;
            cachedFontSize = fontSize;
        }
        return cachedFont;
    }

    private FontMetrics currentFontMetrics() {
        if (cachedMetrics == null) {
            cachedMetrics = measureFontMetrics(currentFont());
        }
        return cachedMetrics;
    }

    private String renderKey(double width, double height, FontMetrics metrics, List<TerminalPane> panes) {
        StringBuilder builder = new StringBuilder();
        builder.append(width).append(':')
                .append(height).append(':')
                .append(workspace.version()).append(':')
                .append(fontFamily).append(':')
                .append(fontSize).append(':')
                .append(metrics.cellWidth).append(':')
                .append(metrics.lineHeight);
        for (TerminalPane pane : panes) {
            builder.append('|')
                    .append(System.identityHashCode(pane)).append(',')
                    .append(pane.renderVersion()).append(',')
                    .append(workspace.isActive(pane)).append(',')
                    .append(pane.x()).append(',')
                    .append(pane.y()).append(',')
                    .append(pane.width()).append(',')
                    .append(pane.height());
        }
        return builder.toString();
    }

    private String paneCacheKey(TerminalPane pane, FontMetrics metrics) {
        return pane.renderVersion()
                + ":" + workspace.isActive(pane)
                + ":" + pane.width()
                + ":" + pane.height()
                + ":" + fontFamily
                + ":" + fontSize
                + ":" + metrics.cellWidth
                + ":" + metrics.lineHeight
                + ":" + config.kittyGraphics();
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

    private void handleMousePressed(MouseEvent event) {
        canvas.requestFocus();
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            return;
        }

        workspace.focus(pane);
        pressedButton = mouseButton(event);
        mouseButtonPressed = true;
        sendMouse(pane, MouseInput.press(pressedButton, eventX(pane, event.getX()), eventY(pane, event.getY()), modifiers(event)), true, event);
    }

    private void handleMouseReleased(MouseEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            pane = workspace.activePane();
        }

        MouseButton button = pressedButton == MouseButton.UNKNOWN ? mouseButton(event) : pressedButton;
        sendMouse(pane, MouseInput.release(button, eventX(pane, event.getX()), eventY(pane, event.getY()), modifiers(event)), false, event);
        mouseButtonPressed = false;
        pressedButton = MouseButton.UNKNOWN;
    }

    private void handleMouseDragged(MouseEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            pane = workspace.activePane();
        }

        MouseButton button = pressedButton == MouseButton.UNKNOWN ? mouseButton(event) : pressedButton;
        sendMouse(pane, MouseInput.drag(button, eventX(pane, event.getX()), eventY(pane, event.getY()), modifiers(event)), true, event);
    }

    private void handleMouseMoved(MouseEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            return;
        }

        sendMouse(pane, MouseInput.motion(eventX(pane, event.getX()), eventY(pane, event.getY()), modifiers(event)), mouseButtonPressed, event);
    }

    private void handleScroll(ScrollEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            return;
        }

        canvas.requestFocus();
        workspace.focus(pane);
        int direction = scrollDirection(event);
        if (direction == 0) {
            return;
        }

        MouseButton wheelButton = direction > 0 ? MouseButton.FOUR : MouseButton.FIVE;
        int rows = scrollRows(event);
        boolean sent = false;
        for (int i = 0; i < rows; i++) {
            sent |= sendMouse(
                    pane,
                    MouseInput.press(wheelButton, eventX(pane, event.getX()), eventY(pane, event.getY()), modifiers(event)),
                    mouseButtonPressed,
                    event
            );
        }
        if (!sent) {
            pane.scrollViewport(direction > 0 ? -rows : rows);
            event.consume();
        }
    }

    private boolean sendMouse(TerminalPane pane, MouseInput input, boolean anyButtonPressed, InputEvent event) {
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return false;
        }

        boolean sent = pane.sendMouse(input, target.size(), anyButtonPressed);
        if (sent) {
            event.consume();
        }
        return sent;
    }

    private TerminalPane paneAt(double x, double y) {
        java.util.List<TerminalPane> panes = workspace.panes();
        for (int i = panes.size() - 1; i >= 0; i--) {
            TerminalPane pane = panes.get(i);
            if (x >= pane.x() && x < pane.x() + pane.width() && y >= pane.y() && y < pane.y() + pane.height()) {
                return pane;
            }
        }
        return null;
    }

    private MouseTarget mouseTarget(TerminalPane pane) {
        if (pane.width() <= 24.0 || pane.height() <= 24.0) {
            return null;
        }

        FontMetrics metrics = currentFontMetrics();
        int columns = Math.max(1, (int) ((pane.width() - 24.0) / metrics.cellWidth));
        int rows = Math.max(1, (int) ((pane.height() - 24.0) / metrics.lineHeight));
        long cellWidth = Math.max(1L, Math.round(metrics.cellWidth));
        long cellHeight = Math.max(1L, Math.round(metrics.lineHeight));
        long screenWidth = Math.max(1L, Math.round(columns * metrics.cellWidth));
        long screenHeight = Math.max(1L, Math.round(rows * metrics.lineHeight));
        return new MouseTarget(MouseEncoderSize.of(screenWidth, screenHeight, cellWidth, cellHeight), screenWidth, screenHeight);
    }

    private double eventX(TerminalPane pane, double canvasX) {
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return 0.0;
        }
        return clamp(canvasX - pane.x() - 12.0, 0.0, target.screenWidth() - 1.0);
    }

    private double eventY(TerminalPane pane, double canvasY) {
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return 0.0;
        }
        return clamp(canvasY - pane.y() - 12.0, 0.0, target.screenHeight() - 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static KeyModifiers modifiers(MouseEvent event) {
        return KeyModifiers.of(event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
    }

    private static KeyModifiers modifiers(ScrollEvent event) {
        return KeyModifiers.of(event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown());
    }

    private static int scrollRows(ScrollEvent event) {
        double rows;
        if (event.getTextDeltaYUnits() == VerticalTextScrollUnits.LINES && event.getTextDeltaY() != 0.0) {
            rows = Math.abs(event.getTextDeltaY());
        } else if (event.getTextDeltaYUnits() == VerticalTextScrollUnits.PAGES && event.getTextDeltaY() != 0.0) {
            rows = Math.abs(event.getTextDeltaY()) * 24.0;
        } else if (event.getMultiplierY() > 0.0) {
            rows = Math.abs(event.getDeltaY()) / event.getMultiplierY();
        } else {
            rows = Math.abs(event.getDeltaY()) / 40.0;
        }
        return Math.max(1, Math.min(64, (int) Math.ceil(rows)));
    }

    private static int scrollDirection(ScrollEvent event) {
        if (event.getDeltaY() != 0.0) {
            return event.getDeltaY() > 0.0 ? 1 : -1;
        }
        if (event.getTextDeltaYUnits() != VerticalTextScrollUnits.NONE && event.getTextDeltaY() != 0.0) {
            return event.getTextDeltaY() > 0.0 ? 1 : -1;
        }
        return 0;
    }

    private static MouseButton mouseButton(MouseEvent event) {
        return switch (event.getButton()) {
            case PRIMARY -> MouseButton.LEFT;
            case SECONDARY -> MouseButton.RIGHT;
            case MIDDLE -> MouseButton.MIDDLE;
            default -> MouseButton.UNKNOWN;
        };
    }

    private static void drawRow(
            GraphicsContext gc,
            RenderRow row,
            double left,
            double top,
            double baseline,
            double cellWidth,
            double lineHeight
    ) {
        for (RenderCell cell : row.cells()) {
            if (cell.kittyPlaceholder().isPresent()) {
                continue;
            }

            double x = left + (cell.column() * cellWidth);
            double cellTop = top + (row.row() * lineHeight);
            cell.background().ifPresent(background -> {
                gc.setFill(toFxColor(background));
                gc.fillRect(x, cellTop, cellWidth, lineHeight);
            });
            if (cell.selected()) {
                gc.setFill(SELECTED_BACKGROUND);
                gc.fillRect(x, cellTop, cellWidth, lineHeight);
            }
            if (cell.codepoints().length == 0) {
                continue;
            }

            double y = baseline + (row.row() * lineHeight);
            Color foreground = cell.foreground().map(TerminalCanvasView::toFxColor).orElse(DEFAULT_FOREGROUND);
            gc.setFill(foreground);
            gc.fillText(cell.text(), x, y);
        }
    }

    private static Color toFxColor(RenderColor color) {
        return Color.rgb(color.red(), color.green(), color.blue());
    }

    private static void drawCursor(GraphicsContext gc, RenderStateSnapshot snapshot, double left, double top, double cellWidth, double lineHeight) {
        if (!snapshot.cursorVisible() || !snapshot.cursorViewportHasValue()) {
            return;
        }

        double x = left + (snapshot.cursorViewportX() * cellWidth);
        double y = top + (snapshot.cursorViewportY() * lineHeight);
        gc.setStroke(Color.rgb(225, 229, 235));
        gc.setFill(Color.rgb(225, 229, 235, 0.28));
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

    private void drawKittyGraphics(
            GraphicsContext gc,
            TerminalPane pane,
            KittyPlacementLayer layer,
            Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds,
            double originX,
            double originY,
            double cellWidth,
            double lineHeight
    ) {
        pane.kittyGraphics().ifPresent(graphics -> {
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
        double width = bounds.columns() * cellWidth;
        double height = bounds.rows() * lineHeight;

        if (sourceWidth <= 0.0 || sourceHeight <= 0.0 || width <= 0.0 || height <= 0.0) {
            return;
        }

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

    private boolean paneHasKittyGraphics(TerminalPane pane) {
        return pane.kittyGraphics()
                .map(graphics -> !graphics.placements().isEmpty())
                .orElse(false);
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

    private record FontMetrics(double cellWidth, double lineHeight, double baselineOffset) {
    }

    private record MouseTarget(MouseEncoderSize size, long screenWidth, long screenHeight) {
    }

    private record KittyImageKey(long id, long number, long width, long height, KittyImageFormat format, int dataLength, long fingerprint) {
        private static KittyImageKey of(KittyImageSnapshot snapshot, byte[] data) {
            return new KittyImageKey(
                    snapshot.id(),
                    snapshot.number(),
                    snapshot.width(),
                    snapshot.height(),
                    snapshot.format(),
                    data.length,
                    fingerprint(data)
            );
        }

        private static long fingerprint(byte[] data) {
            long hash = 0xcbf29ce484222325L;
            for (byte value : data) {
                hash ^= Byte.toUnsignedInt(value);
                hash *= 0x100000001b3L;
            }
            return hash;
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

    private static final class PaneRenderCache {
        private Canvas canvas;
        private WritableImage image;
        private int imageWidth;
        private int imageHeight;
        private String key;
    }
}
