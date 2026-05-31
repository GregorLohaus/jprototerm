package com.gregor.jprototerm;

import dev.jlibghostty.KeyModifiers;
import dev.jlibghostty.MouseButton;
import dev.jlibghostty.MouseEncoderSize;
import dev.jlibghostty.MouseInput;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.InputEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ScrollEvent.VerticalTextScrollUnits;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the window's tabs and drives rendering and input. It composites only the current tab:
 * each frame it lays that tab out, paints the panes bottom-to-top (so the active floating pane
 * lands on top) and lets each pane paint its own content, clipped to the region the layout gave
 * it. The cross-pane concerns live here — the dirty-frame bookkeeping, the tab strip, routing
 * mouse/scroll to the pane under the pointer, and the tab/pane lifecycle that {@link Main}'s key
 * bindings invoke.
 */
public final class Compositor {
    // Canvas background shown wherever no pane covers (gaps). Painted on a full recomposite.
    private static final Color GAP_BACKGROUND = Color.rgb(16, 16, 18);
    private static final Color TAB_TEXT = Color.rgb(225, 229, 235);
    // Thin tab strip shown at the top when more than one tab is open.
    private static final double TAB_BAR_HEIGHT = 22.0;

    private final Canvas canvas = new Canvas();
    private final AppConfig config;
    private final TerminalMetrics metrics;
    private final List<Tab> tabs = new ArrayList<>();
    private int currentTabIndex;
    // Bumped on any structural change (tab switch, pane add/close/focus/move) so render()
    // knows to recomposite. Terminal *content* changes are tracked separately through each
    // tab's content version.
    private long layoutVersion;
    // Last content version drawn to the canvas per pane, so a content frame repaints only
    // the panes that actually changed.
    private final Map<TerminalPane, Long> paneContentVersion = new HashMap<>();
    // Cheap per-frame dirty signal: skip the whole render when none of these changed.
    private double lastWidth = -1.0;
    private double lastHeight = -1.0;
    private String lastFontFamily;
    private double lastFontSize = -1.0;
    private long lastLayoutVersion = Long.MIN_VALUE;
    private long lastContentVersion = Long.MIN_VALUE;
    private boolean mouseButtonPressed;
    private MouseButton pressedButton = MouseButton.UNKNOWN;

    public Compositor(AppConfig config, TerminalMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
        tabs.add(new Tab(config, metrics));
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
        metrics.setFont(family, size);
        paneContentVersion.clear();
        lastWidth = -1.0; // force a redraw on the next frame
    }

    // ---- Tabs and panes -------------------------------------------------------------

    public boolean isEmpty() {
        return tabs.isEmpty();
    }

    public TerminalPane activePane() {
        return currentTab().activePane();
    }

    public void navigate(Direction direction) {
        if (!isEmpty() && currentTab().navigate(direction)) {
            layoutVersion++;
        }
    }

    public void toggleFloating() {
        if (isEmpty()) {
            return;
        }
        currentTab().toggleFloating();
        layoutVersion++;
    }

    public void createPane() {
        if (isEmpty()) {
            return;
        }
        currentTab().createPane();
        layoutVersion++;
    }

    public void nextFloatingPane() {
        if (isEmpty()) {
            return;
        }
        currentTab().nextFloatingPane();
        layoutVersion++;
    }

    public void closeActivePane() {
        if (isEmpty()) {
            return;
        }
        currentTab().closeActivePane();
        if (currentTab().isEmpty()) {
            // Closing a tab's last pane closes the tab. When no tabs remain the surface is
            // empty and Main quits.
            tabs.remove(currentTabIndex);
            if (currentTabIndex >= tabs.size()) {
                currentTabIndex = Math.max(0, tabs.size() - 1);
            }
        }
        layoutVersion++;
    }

    public void newTab() {
        tabs.add(new Tab(config, metrics));
        currentTabIndex = tabs.size() - 1;
        layoutVersion++;
    }

    public void nextTab() {
        if (tabs.size() > 1) {
            currentTabIndex = (currentTabIndex + 1) % tabs.size();
            layoutVersion++;
        }
    }

    public void previousTab() {
        if (tabs.size() > 1) {
            currentTabIndex = (currentTabIndex - 1 + tabs.size()) % tabs.size();
            layoutVersion++;
        }
    }

    public void close() {
        for (Tab tab : tabs) {
            tab.close();
        }
        tabs.clear();
    }

    private Tab currentTab() {
        return tabs.get(currentTabIndex);
    }

    private List<TerminalPane> currentPanes() {
        return tabs.isEmpty() ? List.of() : currentTab().panes();
    }

    private boolean isActive(TerminalPane pane) {
        return !tabs.isEmpty() && currentTab().isActive(pane);
    }

    private void focus(TerminalPane pane) {
        if (!tabs.isEmpty() && currentTab().focus(pane)) {
            layoutVersion++;
        }
    }

    // ---- Rendering ------------------------------------------------------------------

    public void render() {
        switch (nextFrameType()) {
            case IDLE -> { }
            case LAYOUT -> renderLayoutFrame();
            case CONTENT -> renderContentFrame();
        }
    }

    // Classify this frame and commit the change trackers. A layout change (size, font,
    // tab/pane set, z-order, active pane) needs a full recomposite; otherwise a change to the
    // current tab's content version repaints only the panes that changed; otherwise nothing
    // changed and the frame is idle.
    private FrameType nextFrameType() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        long contentVersion = tabs.isEmpty() ? 0 : currentTab().contentVersion();

        boolean layoutChanged = width != lastWidth || height != lastHeight
                || metrics.fontSize() != lastFontSize || !Objects.equals(metrics.fontFamily(), lastFontFamily)
                || layoutVersion != lastLayoutVersion;
        boolean contentChanged = contentVersion != lastContentVersion;

        lastWidth = width;
        lastHeight = height;
        lastFontFamily = metrics.fontFamily();
        lastFontSize = metrics.fontSize();
        lastLayoutVersion = layoutVersion;
        lastContentVersion = contentVersion;

        if (layoutChanged) {
            return FrameType.LAYOUT;
        }
        if (contentChanged) {
            return FrameType.CONTENT;
        }
        return FrameType.IDLE;
    }

    // Full recomposite onto the retained canvas: lay the tab out, clear to the gap colour,
    // draw the tab strip, then paint every pane bottom-to-top (panes() puts the active
    // floating pane last == on top).
    private void renderLayoutFrame() {
        double topInset = tabs.size() > 1 ? TAB_BAR_HEIGHT : 0.0;
        if (!tabs.isEmpty()) {
            currentTab().layout(canvas.getWidth(), canvas.getHeight(), topInset);
        }
        List<TerminalPane> panes = currentPanes();
        // Sync each pane's ghostty grid to its (possibly new) bounds; a no-op when unchanged.
        for (TerminalPane pane : panes) {
            pane.fitToBounds();
        }

        GraphicsContext gc = beginFrame();
        paneContentVersion.keySet().retainAll(panes);
        gc.setFill(GAP_BACKGROUND);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (topInset > 0.0) {
            drawTabBar(gc, canvas.getWidth(), topInset);
        }
        for (TerminalPane pane : panes) {
            paneContentVersion.put(pane, pane.paintFull(gc, isActive(pane)));
        }
    }

    // Repaint just the panes whose content changed, directly on the retained canvas. Each pane
    // clips itself to its rect minus the panes above it, so a lower pane's repaint can't bleed
    // over one stacked on top — no restore pass needed. Bounds and grids can't have changed
    // without a layout frame, so a content frame reuses the existing layout untouched.
    private void renderContentFrame() {
        List<TerminalPane> panes = currentPanes();
        GraphicsContext gc = beginFrame();

        for (TerminalPane pane : panes) {
            Long drawn = paneContentVersion.get(pane);
            if (drawn != null && drawn == pane.contentVersion()) {
                continue;
            }
            paneContentVersion.put(pane, pane.paintIncremental(gc, isActive(pane)));
        }
    }

    private GraphicsContext beginFrame() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFontSmoothingType(FontSmoothingType.LCD); // the per-cell renderer relies on LCD
        return gc;
    }

    // Thin tab strip: one equal-width segment per tab, the current one highlighted, with a
    // small 1-based number centred in each segment.
    private void drawTabBar(GraphicsContext gc, double width, double barHeight) {
        int count = tabs.size();
        Font barFont = Font.font(metrics.fontFamily(), Math.max(9.0, Math.min(13.0, barHeight * 0.62)));
        gc.setFont(barFont);
        gc.setFontSmoothingType(FontSmoothingType.GRAY);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);

        double gap = 1.0;
        double segmentWidth = width / count;
        for (int i = 0; i < count; i++) {
            double x = i * segmentWidth;
            boolean current = i == currentTabIndex;
            gc.setFill(current ? Color.rgb(45, 55, 72) : Color.rgb(22, 24, 28));
            gc.fillRect(x, 0.0, segmentWidth - gap, barHeight);
            gc.setFill(current ? TAB_TEXT : Color.rgb(128, 136, 148));
            gc.fillText(Integer.toString(i + 1), x + (segmentWidth - gap) / 2.0, barHeight / 2.0);
        }

        // Restore the defaults the cell renderer relies on (left-aligned, baseline, LCD).
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        gc.setFontSmoothingType(FontSmoothingType.LCD);
    }

    // ---- Input ----------------------------------------------------------------------

    private void handleMousePressed(MouseEvent event) {
        canvas.requestFocus();
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            return;
        }

        focus(pane);
        pressedButton = mouseButton(event);
        mouseButtonPressed = true;
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return;
        }
        send(pane, target, MouseInput.press(pressedButton, localX(event.getX(), pane, target), localY(event.getY(), pane, target), modifiers(event)), true, event);
    }

    private void handleMouseReleased(MouseEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            pane = activePane();
        }

        MouseButton button = pressedButton == MouseButton.UNKNOWN ? mouseButton(event) : pressedButton;
        MouseTarget target = mouseTarget(pane);
        if (target != null) {
            send(pane, target, MouseInput.release(button, localX(event.getX(), pane, target), localY(event.getY(), pane, target), modifiers(event)), false, event);
        }
        mouseButtonPressed = false;
        pressedButton = MouseButton.UNKNOWN;
    }

    private void handleMouseDragged(MouseEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            pane = activePane();
        }

        MouseButton button = pressedButton == MouseButton.UNKNOWN ? mouseButton(event) : pressedButton;
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return;
        }
        send(pane, target, MouseInput.drag(button, localX(event.getX(), pane, target), localY(event.getY(), pane, target), modifiers(event)), true, event);
    }

    private void handleMouseMoved(MouseEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            return;
        }

        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return;
        }
        send(pane, target, MouseInput.motion(localX(event.getX(), pane, target), localY(event.getY(), pane, target), modifiers(event)), mouseButtonPressed, event);
    }

    private void handleScroll(ScrollEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            return;
        }

        canvas.requestFocus();
        focus(pane);
        int direction = scrollDirection(event);
        if (direction == 0) {
            return;
        }

        MouseButton wheelButton = direction > 0 ? MouseButton.FOUR : MouseButton.FIVE;
        int rows = scrollRows(event);
        MouseTarget target = mouseTarget(pane);
        boolean sent = false;
        if (target != null) {
            // The wheel sends one button press per scrolled row; resolve the position once.
            double ex = localX(event.getX(), pane, target);
            double ey = localY(event.getY(), pane, target);
            KeyModifiers modifiers = modifiers(event);
            for (int i = 0; i < rows; i++) {
                if (!send(pane, target, MouseInput.press(wheelButton, ex, ey, modifiers), mouseButtonPressed, event)) {
                    break;
                }
                sent = true;
            }
        }
        if (!sent) {
            // Not consumed by the app (e.g. mouse reporting off): scroll the local viewport.
            pane.scrollViewport(direction > 0 ? -rows : rows);
            event.consume();
        }
    }

    // Forward an already-positioned mouse event to the pane, consuming it if the pane (i.e.
    // the app running in it) acted on it. Returns whether it was sent.
    private boolean send(TerminalPane pane, MouseTarget target, MouseInput input, boolean anyButtonPressed, InputEvent event) {
        boolean sent = pane.sendMouse(input, target.size(), anyButtonPressed);
        if (sent) {
            event.consume();
        }
        return sent;
    }

    private TerminalPane paneAt(double x, double y) {
        List<TerminalPane> panes = currentPanes();
        for (int i = panes.size() - 1; i >= 0; i--) {
            TerminalPane pane = panes.get(i);
            if (x >= pane.x() && x < pane.x() + pane.width() && y >= pane.y() && y < pane.y() + pane.height()) {
                return pane;
            }
        }
        return null;
    }

    private MouseTarget mouseTarget(TerminalPane pane) {
        if (pane.width() <= 2 * TerminalMetrics.PADDING || pane.height() <= 2 * TerminalMetrics.PADDING) {
            return null;
        }

        int columns = metrics.columnsFor(pane.width());
        int rows = metrics.rowsFor(pane.height());
        long cellWidth = Math.max(1L, Math.round(metrics.cellWidth()));
        long cellHeight = Math.max(1L, Math.round(metrics.lineHeight()));
        long screenWidth = Math.max(1L, Math.round(columns * metrics.cellWidth()));
        long screenHeight = Math.max(1L, Math.round(rows * metrics.lineHeight()));
        return new MouseTarget(MouseEncoderSize.of(screenWidth, screenHeight, cellWidth, cellHeight), screenWidth, screenHeight);
    }

    // Resolve a canvas-space pointer position to a pane-local pixel coordinate, clamped to
    // the pane's reported screen size (what ghostty's mouse encoder expects).
    private static double localX(double canvasX, TerminalPane pane, MouseTarget target) {
        return clamp(canvasX - pane.x() - TerminalMetrics.PADDING, 0.0, target.screenWidth() - 1.0);
    }

    private static double localY(double canvasY, TerminalPane pane, MouseTarget target) {
        return clamp(canvasY - pane.y() - TerminalMetrics.PADDING, 0.0, target.screenHeight() - 1.0);
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

    // What one render() pass should do, decided from the change trackers in nextFrame().
    private enum FrameType {
        IDLE,     // nothing changed since the last frame
        LAYOUT,   // geometry/font/tab/pane set changed: clear and repaint everything
        CONTENT   // only terminal content changed: repaint the panes that changed
    }

    private record MouseTarget(MouseEncoderSize size, long screenWidth, long screenHeight) {
    }
}
