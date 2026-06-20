package com.gregor.jprototerm;

import dev.jlibghostty.KeyModifiers;
import dev.jlibghostty.MouseButton;
import dev.jlibghostty.MouseEncoderSize;
import dev.jlibghostty.MouseInput;
import javafx.geometry.VPos;
import javafx.scene.Node;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private static final Color PANE_SYNC_SELECT_BORDER = Color.rgb(255, 183, 77);
    private static final Color PANE_SYNC_COMMITTED_BORDER = Color.rgb(105, 214, 128);
    // Keep this narrower than the terminal renderer's active border so focus remains visible.
    private static final double PANE_SYNC_BORDER_WIDTH = 1.0;
    // Thin tab strip shown at the top when more than one tab is open.
    private static final double TAB_BAR_HEIGHT = 22.0;

    private final Canvas canvas = new Canvas();
    // Kitty images are drawn as retained nodes layered over the canvas, not composited onto it.
    private final KittyImageOverlay imageOverlay = new KittyImageOverlay();
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
    // Off-screen panes (background tabs, hidden floating groups) keep their full-resolution pixel
    // backbuffer + GPU image until released. We free them after a short grace period rather than the
    // instant they're hidden, so rapidly flipping through tabs never thrashes the realloc/upload.
    private static final long RELEASE_DELAY_NANOS = 750_000_000L;
    // Hidden pane -> nanoTime it became hidden (the release timer); removed once released or shown.
    private final Map<TerminalPane, Long> hiddenSince = new HashMap<>();
    // Panes whose backbuffer is currently released, so we don't release again every frame.
    private final Set<TerminalPane> released = new HashSet<>();
    private final Set<TerminalPane> paneSyncSelection = new LinkedHashSet<>();
    private final Set<TerminalPane> paneSyncPanes = new LinkedHashSet<>();
    private boolean paneSyncSelectMode;
    // layoutVersion at the last sweep: lets an idle, all-released steady state skip the scan.
    private long lastSweepLayoutVersion = Long.MIN_VALUE;
    // Cheap per-frame dirty signal: skip the whole render when none of these changed.
    private double lastWidth = -1.0;
    private double lastHeight = -1.0;
    private String lastFontFamily;
    private double lastFontSize = -1.0;
    private long lastLayoutVersion = Long.MIN_VALUE;
    private long lastContentVersion = Long.MIN_VALUE;
    private boolean mouseButtonPressed;
    private MouseButton pressedButton = MouseButton.UNKNOWN;
    // Run when the last pane closes (so the window can quit). No-op until Main sets it.
    private Runnable onEmpty = () -> {};

    public Compositor(AppConfig config, TerminalMetrics metrics) {
        this(config, metrics, null);
    }

    /**
     * Creates a compositor whose first tab's first pane starts in {@code workingDirectory} (e.g. the
     * cwd a client passed when asking the daemon to open this window), or the user's home when
     * {@code null}.
     */
    public Compositor(AppConfig config, TerminalMetrics metrics, String workingDirectory) {
        this.config = config;
        this.metrics = metrics;
        tabs.add(new Tab(config, metrics, workingDirectory, this::closePane));
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

    /** Sets the callback run when the last pane closes (e.g. to quit the application). */
    public void setOnEmpty(Runnable onEmpty) {
        this.onEmpty = onEmpty;
    }

    /** The kitty-image overlay, to be stacked directly above {@link #canvas()} in the window. */
    public Node imageOverlay() {
        return imageOverlay.node();
    }

    public void setFont(String family, double size) {
        metrics.setFont(family, size);
        paneContentVersion.clear();
        layoutVersion++; // recomposite with the new metrics on the next frame
    }

    // ---- Tabs and panes -------------------------------------------------------------

    public boolean isEmpty() {
        return tabs.isEmpty();
    }

    /** The active pane of the current tab, or {@code null} when no tab is left. */
    public TerminalPane activePane() {
        return isEmpty() ? null : currentTab().activePane();
    }

    public void navigate(Direction direction) {
        if (!isEmpty() && currentTab().navigate(direction)) {
            layoutVersion++;
        }
    }

    public boolean isPaneSyncSelecting() {
        return paneSyncSelectMode;
    }

    public void togglePaneSync() {
        if (paneSyncSelectMode) {
            paneSyncPanes.clear();
            paneSyncPanes.addAll(paneSyncSelection);
            paneSyncSelectMode = false;
            paneSyncSelection.clear();
            prunePaneSyncState();
            layoutVersion++;
            return;
        }
        if (!paneSyncPanes.isEmpty()) {
            paneSyncPanes.clear();
            layoutVersion++;
            return;
        }
        if (activePane() == null) {
            return;
        }
        paneSyncSelectMode = true;
        paneSyncSelection.clear();
        layoutVersion++;
    }

    public void togglePaneSyncSelection() {
        TerminalPane active = activePane();
        if (active == null || !paneSyncSelectMode) {
            return;
        }
        if (!paneSyncSelection.add(active)) {
            paneSyncSelection.remove(active);
        }
        layoutVersion++;
    }

    public List<TerminalPane> paneSyncPeers(TerminalPane source) {
        prunePaneSyncState();
        if (source == null || !paneSyncPanes.contains(source)) {
            return List.of();
        }
        return paneSyncPanes.stream()
                .filter(pane -> pane != source)
                .toList();
    }

    public void syncPanes(List<TerminalPane> panes) {
        paneSyncSelectMode = false;
        paneSyncSelection.clear();
        paneSyncPanes.clear();
        for (TerminalPane pane : panes) {
            if (pane != null) {
                paneSyncPanes.add(pane);
            }
        }
        prunePaneSyncState();
        layoutVersion++;
    }

    public void toggleFloating() {
        mutateCurrentTab(() -> currentTab().toggleFloating());
    }

    public void createPane() {
        mutateCurrentTab(() -> currentTab().createPane());
    }

    public TerminalPane createTiledPane(String workingDirectory) {
        if (isEmpty()) {
            return null;
        }
        TerminalPane pane = currentTab().createTiledPane(workingDirectory);
        layoutVersion++;
        return pane;
    }

    public TerminalPane createFloatingPaneInDirectory(String workingDirectory) {
        if (isEmpty()) {
            return null;
        }
        TerminalPane pane = currentTab().createFloatingPaneInDirectory(workingDirectory);
        layoutVersion++;
        return pane;
    }

    /**
     * Opens a floating pane running {@code command} directly (auto-closing when it exits), makes it
     * active, and returns it (null when no tab exists).
     */
    public TerminalPane openFloatingPane(String command) {
        if (isEmpty()) {
            return null;
        }
        TerminalPane pane = currentTab().createFloatingPane(command);
        layoutVersion++;
        return pane;
    }

    public void nextFloatingPane() {
        mutateCurrentTab(() -> currentTab().nextFloatingPane());
    }

    public void toggleActiveFloating() {
        mutateCurrentTab(() -> currentTab().toggleActiveFloating());
    }

    // Run a structural change on the current tab and bump the layout version so the next frame
    // recomposites. No-op when no tab is left.
    private void mutateCurrentTab(Runnable change) {
        if (isEmpty()) {
            return;
        }
        change.run();
        layoutVersion++;
    }

    public void closeActivePane() {
        TerminalPane active = activePane();
        if (active != null) {
            closePane(active);
        }
    }

    /**
     * Closes a specific pane, wherever it lives. Driven both by the key-bound close (via
     * {@link #closeActivePane()}) and by a pane whose process exited on its own. Drops the owning
     * tab if it becomes empty, and fires {@link #setOnEmpty} when the last pane is gone. Must run on
     * the FX thread.
     */
    public void closePane(TerminalPane pane) {
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (tab.closePane(pane)) {
                removePaneFromSyncState(pane);
                if (tab.isEmpty()) {
                    // Closing a tab's last pane closes the tab. Keep currentTabIndex pointing at the
                    // same tab (or clamp it when the current/last tab went away).
                    tabs.remove(i);
                    if (i < currentTabIndex) {
                        currentTabIndex--;
                    } else if (currentTabIndex >= tabs.size()) {
                        currentTabIndex = Math.max(0, tabs.size() - 1);
                    }
                }
                layoutVersion++;
                if (isEmpty()) {
                    onEmpty.run();
                }
                return;
            }
        }
    }

    public void newTab() {
        // Open the new tab in the currently active pane's working directory, so it lands where the
        // user currently is rather than always in home.
        TerminalPane active = activePane();
        String workingDirectory = active != null ? active.currentWorkingDirectory() : null;
        tabs.add(new Tab(config, metrics, workingDirectory, this::closePane));
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
        paneSyncSelectMode = false;
        paneSyncSelection.clear();
        paneSyncPanes.clear();
    }

    /**
     * Signals and reaps every pane's shell process across all tabs, without tearing down render
     * state. Intended for a JVM shutdown hook (SIGTERM/SIGINT/SIGHUP), so child shells get the
     * configured close signal instead of being orphaned when jprototerm itself is killed. Safe to
     * call off the FX thread and idempotent; see {@link TerminalPane#terminateSession()}.
     */
    public void terminateSessions() {
        for (Tab tab : List.copyOf(tabs)) {
            tab.terminateSessions();
        }
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
        sweepHiddenPanes();
        switch (nextFrameType()) {
            case IDLE -> { }
            case LAYOUT -> renderLayoutFrame();
            case CONTENT -> renderContentFrame();
        }
    }

    // Free the backbuffer of any pane that has been off-screen past the grace period, and re-arm the
    // timer for newly hidden panes. The next layout frame rebuilds a released pane (paintFull goes
    // through ensure()), so showing a tab again is the only cost. Skips entirely once everything that
    // can be hidden is already released and the layout hasn't changed, so an idle multi-tab window
    // does no per-frame work here.
    private void sweepHiddenPanes() {
        if (layoutVersion == lastSweepLayoutVersion && hiddenSince.isEmpty()) {
            return;
        }
        lastSweepLayoutVersion = layoutVersion;

        // Fast path: a single tab compositing all of its panes has nothing off-screen.
        if (tabs.size() <= 1 && (tabs.isEmpty() || !currentTab().hasHiddenPanes())) {
            hiddenSince.clear();
            released.clear();
            return;
        }

        Set<TerminalPane> visible = new HashSet<>(currentPanes());
        Set<TerminalPane> live = new HashSet<>();
        long now = System.nanoTime();
        for (Tab tab : tabs) {
            for (TerminalPane pane : tab.allPanes()) {
                live.add(pane);
                if (visible.contains(pane)) {
                    hiddenSince.remove(pane);
                    released.remove(pane);
                } else if (!released.contains(pane)) {
                    Long since = hiddenSince.putIfAbsent(pane, now);
                    if (since != null && now - since >= RELEASE_DELAY_NANOS) {
                        pane.releaseRenderResources();
                        released.add(pane);
                        hiddenSince.remove(pane);
                    }
                }
            }
        }
        // Forget panes that have since closed.
        hiddenSince.keySet().retainAll(live);
        released.retainAll(live);
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
        drawPaneSyncOverlay(gc, panes);
        imageOverlay.sync(panes);
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
            imageOverlay.updatePane(pane);
        }
        drawPaneSyncOverlay(gc, panes);
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

    private void drawPaneSyncOverlay(GraphicsContext gc, List<TerminalPane> panes) {
        Set<TerminalPane> highlighted = paneSyncSelectMode ? paneSyncSelection : paneSyncPanes;
        if (highlighted.isEmpty()) {
            return;
        }

        gc.save();
        try {
            gc.setLineWidth(PANE_SYNC_BORDER_WIDTH);
            gc.setStroke(paneSyncSelectMode ? PANE_SYNC_SELECT_BORDER : PANE_SYNC_COMMITTED_BORDER);
            for (TerminalPane pane : panes) {
                if (!highlighted.contains(pane)) {
                    continue;
                }
                gc.save();
                double x = Math.round(pane.x()) + 2.0;
                double y = Math.round(pane.y()) + 2.0;
                double width = Math.max(0.0, pane.width() - 4.0);
                double height = Math.max(0.0, pane.height() - 4.0);
                TerminalRenderer.clip(gc, Math.round(pane.x()), Math.round(pane.y()), pane.width(), pane.height(), pane.clip());
                gc.strokeRect(x, y, width, height);
                gc.restore();
            }
        } finally {
            gc.restore();
        }
    }

    private void removePaneFromSyncState(TerminalPane pane) {
        boolean changed = paneSyncSelection.remove(pane);
        changed |= paneSyncPanes.remove(pane);
        if (paneSyncPanes.size() < 2) {
            changed |= !paneSyncPanes.isEmpty();
            paneSyncPanes.clear();
        }
        if (changed) {
            layoutVersion++;
        }
    }

    private void prunePaneSyncState() {
        Set<TerminalPane> live = livePanes();
        paneSyncSelection.retainAll(live);
        paneSyncPanes.retainAll(live);
        if (paneSyncPanes.size() < 2) {
            paneSyncPanes.clear();
        }
    }

    private Set<TerminalPane> livePanes() {
        Set<TerminalPane> live = new HashSet<>();
        for (Tab tab : tabs) {
            live.addAll(tab.allPanes());
        }
        return live;
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
            pane = activePane(); // released outside every pane (e.g. mid-drag): route to the active one
        }

        MouseButton button = pressedButton == MouseButton.UNKNOWN ? mouseButton(event) : pressedButton;
        MouseTarget target = pane == null ? null : mouseTarget(pane);
        if (target != null) {
            send(pane, target, MouseInput.release(button, localX(event.getX(), pane, target), localY(event.getY(), pane, target), modifiers(event)), false, event);
        }
        mouseButtonPressed = false;
        pressedButton = MouseButton.UNKNOWN;
    }

    private void handleMouseDragged(MouseEvent event) {
        TerminalPane pane = paneAt(event.getX(), event.getY());
        if (pane == null) {
            pane = activePane(); // dragged outside every pane: route to the active one
        }
        if (pane == null) {
            return;
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
