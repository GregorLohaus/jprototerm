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
    // The default cell background (used for cells with no explicit bg, and as the foreground
    // for reverse-video cells whose background is the terminal default).
    private static final Color PANE_BACKGROUND = Color.rgb(9, 10, 12);
    // Canvas background shown wherever no pane covers (gaps, behind nothing once tiled panes
    // fill the canvas). Painted on a full recomposite.
    private static final Color GAP_BACKGROUND = Color.rgb(16, 16, 18);

    private final Canvas canvas = new Canvas();
    private final TerminalWorkspace workspace;
    private final AppConfig config;
    private final Map<KittyImageKey, Image> kittyImageCache = new HashMap<>();
    // Last content version drawn to the canvas per pane, so a content frame repaints only
    // the panes that actually changed.
    private final Map<TerminalPane, Long> paneContentVersion = new HashMap<>();
    private String fontFamily;
    private double fontSize;
    private Font cachedFont;
    private FontMetrics cachedMetrics;
    private String cachedFontFamily;
    private double cachedFontSize;
    // Cheap per-frame dirty signal: skip the whole render when none of these changed.
    private double lastWidth = -1.0;
    private double lastHeight = -1.0;
    private String lastFontFamily;
    private double lastFontSize = -1.0;
    private long lastWorkspaceVersion = Long.MIN_VALUE;
    private long lastRenderTick = Long.MIN_VALUE;
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
        paneContentVersion.clear();
        lastWidth = -1.0; // force a redraw on the next frame
    }

    // GhosttyRenderStateDirty values (stable C ABI; see ghostty/vt/render.h).
    private static final int DIRTY_PARTIAL = 1;
    private static final int DIRTY_FULL = 2;

    public void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        long workspaceVersion = workspace.version();
        long renderTick = TerminalPane.renderTick();

        // Two kinds of change: a layout change (size, font, active pane, pane set / z-order)
        // forces a full recomposite; a content change (renderTick) only repaints the panes
        // whose terminal content changed. Idle frames — neither — bail out immediately.
        boolean layoutChanged = width != lastWidth || height != lastHeight
                || fontSize != lastFontSize || !java.util.Objects.equals(fontFamily, lastFontFamily)
                || workspaceVersion != lastWorkspaceVersion;
        boolean contentChanged = renderTick != lastRenderTick;
        if (!layoutChanged && !contentChanged) {
            return;
        }
        lastWidth = width;
        lastHeight = height;
        lastFontFamily = fontFamily;
        lastFontSize = fontSize;
        lastWorkspaceVersion = workspaceVersion;
        lastRenderTick = renderTick;

        workspace.layout(width, height);
        Font font = currentFont();
        FontMetrics metrics = currentFontMetrics();
        List<TerminalPane> panes = workspace.panes();

        // Apply terminal resizes up front so snapshots reflect current geometry (a no-op
        // when the grid is unchanged).
        for (TerminalPane pane : panes) {
            applyResize(pane, metrics);
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFontSmoothingType(FontSmoothingType.LCD);

        if (layoutChanged) {
            // Recomposite everything onto the retained canvas: clear, then paint panes
            // bottom-to-top (workspace.panes() puts the active floating pane last == on top).
            paneContentVersion.keySet().retainAll(panes);
            gc.setFill(GAP_BACKGROUND);
            gc.fillRect(0, 0, width, height);
            for (TerminalPane pane : panes) {
                paintPane(gc, pane, font, metrics, pane.renderSnapshotFull());
                paneContentVersion.put(pane, pane.renderVersion());
            }
            return;
        }

        // Content-only frame: repaint just the panes whose content changed, directly on the
        // retained canvas, then restore any panes stacked above where they overlap.
        for (int i = 0; i < panes.size(); i++) {
            TerminalPane pane = panes.get(i);
            Long drawn = paneContentVersion.get(pane);
            if (drawn != null && drawn == pane.renderVersion()) {
                continue;
            }
            repaintPaneContent(gc, panes, i, font, metrics);
            paneContentVersion.put(pane, pane.renderVersion());
        }
    }

    private void applyResize(TerminalPane pane, FontMetrics metrics) {
        int columns = Math.max(1, (int) ((pane.width() - 24.0) / metrics.cellWidth));
        int rows = Math.max(1, (int) ((pane.height() - 24.0) / metrics.lineHeight));
        pane.resize(columns, rows, (int) Math.round(metrics.cellWidth), (int) Math.round(metrics.lineHeight));
    }

    // Paint a pane's whole body, clipped to its rect. Used for full recomposites.
    private void paintPane(GraphicsContext gc, TerminalPane pane, Font font, FontMetrics metrics, RenderStateSnapshot snapshot) {
        double px = Math.round(pane.x());
        double py = Math.round(pane.y());
        gc.save();
        clipRect(gc, px, py, pane.width(), pane.height());
        drawPaneContent(gc, pane, font, metrics, snapshot, px, py, pane.width(), pane.height(),
                config.kittyGraphics() && paneHasKittyGraphics(pane));
        gc.restore();
    }

    // Repaint one pane whose content changed, then restore the (opaque) panes stacked above
    // it wherever they overlap the repainted region, so the z-order stays correct.
    private void repaintPaneContent(GraphicsContext gc, List<TerminalPane> panes, int index, Font font, FontMetrics metrics) {
        TerminalPane pane = panes.get(index);
        double px = Math.round(pane.x());
        double py = Math.round(pane.y());
        double pw = pane.width();
        double ph = pane.height();
        boolean kitty = config.kittyGraphics() && paneHasKittyGraphics(pane);

        double regionY0;
        double regionY1;
        gc.save();
        clipRect(gc, px, py, pw, ph);
        if (kitty) {
            drawPaneContent(gc, pane, font, metrics, pane.renderSnapshotFull(), px, py, pw, ph, true);
            regionY0 = py;
            regionY1 = py + ph;
        } else {
            RenderStateSnapshot snapshot = pane.renderSnapshot();
            int dirty = snapshot == null ? DIRTY_FULL : snapshot.dirty();
            if (dirty == DIRTY_FULL) {
                drawPaneContent(gc, pane, font, metrics, snapshot, px, py, pw, ph, false);
                regionY0 = py;
                regionY1 = py + ph;
            } else if (dirty == DIRTY_PARTIAL) {
                double[] band = drawDirtyRows(gc, pane, font, metrics, snapshot, px, py, pw, ph);
                gc.restore();
                if (band == null) {
                    return;
                }
                restoreStackedAbove(gc, panes, index, font, metrics, px, band[0], pw, band[1] - band[0]);
                return;
            } else {
                gc.restore();
                return; // dirty == FALSE: nothing visible changed.
            }
        }
        gc.restore();
        restoreStackedAbove(gc, panes, index, font, metrics, px, regionY0, pw, regionY1 - regionY0);
    }

    // Redraw any panes above `index` in z-order that intersect the given screen rect, so a
    // lower pane's repaint doesn't leak through a pane stacked on top of it.
    private void restoreStackedAbove(GraphicsContext gc, List<TerminalPane> panes, int index,
            Font font, FontMetrics metrics, double rx, double ry, double rw, double rh) {
        for (int j = index + 1; j < panes.size(); j++) {
            TerminalPane above = panes.get(j);
            double ax = Math.round(above.x());
            double ay = Math.round(above.y());
            double ox0 = Math.max(rx, ax);
            double oy0 = Math.max(ry, ay);
            double ox1 = Math.min(rx + rw, ax + above.width());
            double oy1 = Math.min(ry + rh, ay + above.height());
            if (ox1 <= ox0 || oy1 <= oy0) {
                continue;
            }
            gc.save();
            clipRect(gc, ox0, oy0, ox1 - ox0, oy1 - oy0);
            drawPaneContent(gc, above, font, metrics, above.renderSnapshotFull(), ax, ay, above.width(), above.height(),
                    config.kittyGraphics() && paneHasKittyGraphics(above));
            gc.restore();
        }
    }

    private static void clipRect(GraphicsContext gc, double x, double y, double width, double height) {
        gc.beginPath();
        gc.rect(x, y, width, height);
        gc.clip();
    }

    // Full content render: background, border, all rows, cursor, and (when enabled) kitty
    // graphics. Used by the kitty direct path and by full offscreen redraws.
    private void drawPaneContent(
            GraphicsContext gc,
            TerminalPane pane,
            Font font,
            FontMetrics metrics,
            RenderStateSnapshot snapshot,
            double x,
            double y,
            double width,
            double height,
            boolean withKitty
    ) {
        gc.setFontSmoothingType(FontSmoothingType.LCD);
        // Paint content fully opaque. LCD subpixel text rendering produces colour fringing
        // on a translucent surface, so floating-pane translucency is applied by the caller
        // when the finished (opaque) buffer is composited onto the canvas.
        gc.setFill(Color.rgb(9, 10, 12));
        gc.fillRect(x, y, width, height);
        drawBorder(gc, pane, x, y, width, height);
        gc.setFont(font);

        double left = x + 12.0;
        double top = y + 12.0;
        double baseline = top + metrics.baselineOffset;

        Map<KittyPlaceholderKey, KittyPlaceholderBounds> placeholderBounds = withKitty
                ? kittyPlaceholderBounds(snapshot)
                : Map.of();

        if (withKitty) {
            drawKittyGraphics(gc, pane, KittyPlacementLayer.BELOW_TEXT, placeholderBounds, left, top, metrics.cellWidth, metrics.lineHeight);
        }

        if (snapshot != null) {
            for (RenderRow row : snapshot.renderRows()) {
                drawRow(gc, row, left, top, baseline, metrics.cellWidth, metrics.lineHeight);
            }
            drawCursor(gc, snapshot, left, top, metrics.cellWidth, metrics.lineHeight);
        }

        if (withKitty) {
            drawKittyGraphics(gc, pane, KittyPlacementLayer.ABOVE_TEXT, placeholderBounds, left, top, metrics.cellWidth, metrics.lineHeight);
        }
    }

    // Incremental render: repaint only the rows ghostty flagged dirty, at the pane's screen
    // origin, then restore the cursor and border. Returns the screen-space [minY, maxY] band
    // that was repainted (for restoring panes stacked above), or null if nothing was dirty.
    private double[] drawDirtyRows(
            GraphicsContext gc,
            TerminalPane pane,
            Font font,
            FontMetrics metrics,
            RenderStateSnapshot snapshot,
            double px,
            double py,
            double pw,
            double ph
    ) {
        gc.setFontSmoothingType(FontSmoothingType.LCD);
        gc.setFont(font);
        double left = px + 12.0;
        double top = py + 12.0;
        double baseline = top + metrics.baselineOffset;

        boolean cursorRowDirty = false;
        double bandMin = Double.POSITIVE_INFINITY;
        double bandMax = Double.NEGATIVE_INFINITY;
        for (RenderRow row : snapshot.renderRows()) {
            if (!row.dirty()) {
                continue;
            }
            // Snap the row band to integer pixels and paint opaque: a fractional-height fill
            // would leave sub-pixel seams between rows.
            double y0 = Math.floor(top + (row.row() * metrics.lineHeight));
            double y1 = Math.ceil(top + ((row.row() + 1) * metrics.lineHeight));
            gc.setFill(PANE_BACKGROUND);
            gc.fillRect(px, y0, pw, y1 - y0);
            drawRow(gc, row, left, top, baseline, metrics.cellWidth, metrics.lineHeight);
            bandMin = Math.min(bandMin, y0);
            bandMax = Math.max(bandMax, y1);
            if (snapshot.cursorViewportHasValue() && row.row() == snapshot.cursorViewportY()) {
                cursorRowDirty = true;
            }
        }
        if (bandMin > bandMax) {
            return null;
        }

        // The cursor overlays its cell; redraw it only when its row was repainted, so we
        // neither leave a stale cursor nor stack the translucent overlay on itself.
        if (cursorRowDirty) {
            drawCursor(gc, snapshot, left, top, metrics.cellWidth, metrics.lineHeight);
        }
        // Repainting rows clears the side borders within the band; restore just those
        // segments. Clipping to the band is important: the full border rectangle extends
        // outside the repainted region, and only the band gets restored over panes stacked
        // above — an unclipped stroke would leave this pane's outline on top of them.
        gc.save();
        clipRect(gc, px, bandMin, pw, bandMax - bandMin);
        drawBorder(gc, pane, px, py, pw, ph);
        gc.restore();
        return new double[] {bandMin, bandMax};
    }

    private void drawBorder(GraphicsContext gc, TerminalPane pane, double x, double y, double width, double height) {
        gc.setStroke(workspace.isActive(pane) ? Color.rgb(87, 166, 255) : Color.rgb(52, 57, 65));
        gc.setLineWidth(workspace.isActive(pane) ? 2.0 : 1.0);
        gc.strokeRect(x + 0.5, y + 0.5, width - 1.0, height - 1.0);
    }

    private static FontMetrics measureFontMetrics(Font font) {
        Text text = new Text("┃MgÅjy");
        text.setFont(font);
        // Snap the cell size to whole pixels so cells tile on integer boundaries. Fractional
        // cell metrics put every cell edge on a sub-pixel position, leaving anti-aliased
        // seams that show up as a faint grid behind the themed cell backgrounds. Rounding
        // leaves a few pixels of unused space at the right/bottom edge, which is fine.
        double lineHeight = Math.max(1.0, Math.round(text.getLayoutBounds().getHeight()));
        double baselineOffset = -text.getLayoutBounds().getMinY();

        Text cell = new Text("M");
        cell.setFont(font);
        double cellWidth = Math.max(1.0, Math.round(cell.getLayoutBounds().getWidth()));
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

    // Layout identity of a pane: everything that forces a redraw EXCEPT terminal content
    // (which is tracked separately by renderVersion). Deliberately omits renderVersion so
    // content changes go through the incremental dirty-row path instead of a full redraw.
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

            // Resolve fg/bg (null bg = terminal default, painted by the pane background).
            // Avoid Optional.map's allocation on this hot path.
            var fgOpt = cell.foreground();
            var bgOpt = cell.background();
            Color fg = fgOpt.isPresent() ? toFxColor(fgOpt.get()) : DEFAULT_FOREGROUND;
            Color bg = bgOpt.isPresent() ? toFxColor(bgOpt.get()) : null;

            // Reverse video: ghostty does not bake inverse into the resolved colours, so we
            // swap them here, falling back to the terminal defaults for whichever is unset.
            if (cell.inverse()) {
                Color swappedBg = fg;
                fg = (bg != null) ? bg : PANE_BACKGROUND;
                bg = swappedBg;
            }

            if (bg != null) {
                gc.setFill(bg);
                gc.fillRect(x, cellTop, cellWidth, lineHeight);
            }
            if (cell.selected()) {
                gc.setFill(SELECTED_BACKGROUND);
                gc.fillRect(x, cellTop, cellWidth, lineHeight);
            }
            if (cell.codepoints().length == 0) {
                continue;
            }

            double y = baseline + (row.row() * lineHeight);
            gc.setFill(fg);
            gc.fillText(cell.text(), x, y);
        }
    }

    // A full-screen redraw asks for one Color per cell; most cells share a handful of
    // colors, so cache them by packed RGB instead of allocating a Color each time. Bounded
    // so a truecolor gradient can't grow it without limit.
    private static final Map<Integer, Color> COLOR_CACHE = new HashMap<>();

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

    // A kitty image is immutable for a given (id, number); re-transmitting under the same
    // id changes the number (and the snapshot below evicts stale entries by id anyway). So
    // the identity + dimensions + payload length are enough to key the decoded-image cache,
    // and we avoid fingerprinting the whole payload — which previously ran once per frame
    // per placement (O(image size)) just to look the image up.
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
