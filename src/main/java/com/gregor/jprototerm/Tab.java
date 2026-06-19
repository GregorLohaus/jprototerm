package com.gregor.jprototerm;

import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One tab: a row of tiled panes with a group of floating panes shown over them. Floating panes
 * are shown/hidden as a group ({@code floatingVisible}), and there is always at least one tiled
 * pane — a floating pane is promoted if the last tiled one closes — so the layout always has a
 * base. The {@link Compositor} owns the tabs and renders only the current one; mutating methods
 * return whether they actually changed anything so it can bump its layout version.
 */
final class Tab implements AutoCloseable {
    // Floating-pane sizing policy: a fraction of the tab's size with a floor so panes stay
    // usable in small windows, cascaded diagonally per pane, kept off the window edge.
    private static final double FLOATING_SIZE_FRACTION = 0.58;
    private static final double FLOATING_MIN_WIDTH = 420.0;
    private static final double FLOATING_MIN_HEIGHT = 260.0;
    private static final double FLOATING_CASCADE_OFFSET = 28.0;
    private static final double FLOATING_EDGE_MARGIN = 12.0;

    private final AppConfig config;
    private final TerminalMetrics metrics;
    // Notified (on the FX thread) when one of this tab's panes' process exits on its own, so the
    // compositor can close that pane and reap the tab/app if it was the last one.
    private final Consumer<TerminalPane> onPaneExit;
    private final List<TerminalPane> tiled = new ArrayList<>();
    private final List<TerminalPane> floating = new ArrayList<>();
    private boolean floatingVisible;
    private TerminalPane active;
    private final String initialWorkingDirectory;
    // The floating pane to re-focus when the group is shown again, and to prefer when promoting
    // after the last tiled pane closes.
    private TerminalPane lastFocusedFloating;
    // The tiled pane to re-focus when the floating group is hidden.
    private TerminalPane lastFocusedTiled;
    // Last laid-out size, so a newly opened pane can be created at roughly its eventual rect
    // (and thus grid). Seeded from the configured window size for the first pane, which is
    // opened before any layout pass runs.
    private double lastWidth;
    private double lastHeight;
    private double lastTopInset;
    // Bumped whenever one of this tab's panes changes content; the compositor reads the current
    // tab's value each frame as an O(1) "anything to repaint?" check.
    private final AtomicLong contentVersion = new AtomicLong();

    Tab(AppConfig config, TerminalMetrics metrics, Consumer<TerminalPane> onPaneExit) {
        this(config, metrics, null, onPaneExit);
    }

    /**
     * Creates a tab whose first pane starts in {@code initialWorkingDirectory} (e.g. the cwd of the
     * pane that was active when this tab was opened), or the user's home when {@code null}.
     */
    Tab(AppConfig config, TerminalMetrics metrics, String initialWorkingDirectory,
            Consumer<TerminalPane> onPaneExit) {
        this.config = config;
        this.metrics = metrics;
        this.onPaneExit = onPaneExit;
        this.lastWidth = config.windowWidth();
        this.lastHeight = config.windowHeight();
        this.initialWorkingDirectory = initialWorkingDirectory;
        TerminalPane first = openPane(false);
        tiled.add(first);
        active = first;
    }

    TerminalPane activePane() {
        return active;
    }

    boolean isEmpty() {
        return tiled.isEmpty() && floating.isEmpty();
    }

    long contentVersion() {
        return contentVersion.get();
    }

    /**
     * Panes to composite, bottom-to-top: tiled first, then (when shown) the floating group with
     * the active floating pane on top.
     */
    List<TerminalPane> panes() {
        if (!floatingVisible || floating.isEmpty()) {
            return List.copyOf(tiled);
        }
        List<TerminalPane> ordered = new ArrayList<>(tiled.size() + floating.size());
        ordered.addAll(tiled);
        ordered.addAll(floatingOrder());
        return List.copyOf(ordered);
    }

    // Floating panes bottom-to-top: insertion order, with the active pane moved to the top.
    // Single source of the stacking order, so the clips assigned in assignClips() always match
    // the compositing order in panes().
    private List<TerminalPane> floatingOrder() {
        List<TerminalPane> order = new ArrayList<>(floating.size());
        for (TerminalPane pane : floating) {
            if (pane != active) {
                order.add(pane);
            }
        }
        if (floating.contains(active)) {
            order.add(active);
        }
        return order;
    }

    boolean isActive(TerminalPane pane) {
        return pane != null && pane == active;
    }

    /** Every pane this tab owns, composited or not (tiled then floating). */
    List<TerminalPane> allPanes() {
        List<TerminalPane> all = new ArrayList<>(tiled.size() + floating.size());
        all.addAll(tiled);
        all.addAll(floating);
        return all;
    }

    /** Whether this tab owns panes that {@link #panes()} does not currently composite. */
    boolean hasHiddenPanes() {
        return !floatingVisible && !floating.isEmpty();
    }

    boolean focus(TerminalPane pane) {
        if (pane == active || !isFocusable(pane)) {
            return false;
        }
        setActive(pane);
        return true;
    }

    void layout(double width, double height, double topInset) {
        this.lastWidth = width;
        this.lastHeight = height;
        this.lastTopInset = topInset;
        double availHeight = height - topInset;

        double tileWidth = width / Math.max(1, tiled.size());
        for (int i = 0; i < tiled.size(); i++) {
            tiled.get(i).bounds(i * tileWidth, topInset, tileWidth, availHeight);
        }

        double floatingWidth = Math.max(FLOATING_MIN_WIDTH, width * FLOATING_SIZE_FRACTION);
        double floatingHeight = Math.max(FLOATING_MIN_HEIGHT, availHeight * FLOATING_SIZE_FRACTION);
        for (int i = 0; i < floating.size(); i++) {
            double offset = i * FLOATING_CASCADE_OFFSET;
            floating.get(i).bounds(
                    Math.min(width - floatingWidth - FLOATING_EDGE_MARGIN,
                            ((width - floatingWidth) / 2.0) + offset),
                    Math.min(height - floatingHeight - FLOATING_EDGE_MARGIN,
                            topInset + ((availHeight - floatingHeight) / 2.0) + offset),
                    floatingWidth,
                    floatingHeight);
        }

        assignClips();
    }

    // Give each pane its clip region for the next paints, so repainting a pane on a content
    // frame can never bleed over one stacked on top of it. Each pane is clipped to its rect
    // minus the union of the panes above it: floating panes are clipped by the floating panes
    // higher in the stack, and tiled panes by the whole floating group. When nothing floats,
    // every pane clips to its plain bounds.
    private void assignClips() {
        if (!floatingVisible || floating.isEmpty()) {
            allPanes().forEach(pane -> pane.setClip(null));
            return;
        }

        // Walk the floating stack top-to-bottom, accumulating the union of the panes above
        // each one. The topmost pane has nothing above it and keeps an unclipped bounds.
        List<TerminalPane> order = floatingOrder();
        Shape above = null;
        for (int i = order.size() - 1; i >= 0; i--) {
            TerminalPane pane = order.get(i);
            Rectangle rect = rectOf(pane);
            pane.setClip(above == null ? null : Shape.subtract(rect, above));
            above = (above == null) ? rect : Shape.union(above, rect);
        }

        // `above` is now the union of every floating pane; tiled panes sit under all of them.
        for (TerminalPane pane : tiled) {
            pane.setClip(Shape.subtract(rectOf(pane), above));
        }
    }

    // Match the renderer's pixel snapping (round the origin, keep width/height) so the clip
    // lines up exactly with where the floating panes are drawn.
    private static Rectangle rectOf(TerminalPane pane) {
        return new Rectangle(Math.round(pane.x()), Math.round(pane.y()), pane.width(), pane.height());
    }

    boolean navigate(Direction direction) {
        if (floating.contains(active) && navigateFloatingStack(direction)) {
            return true;
        }
        TerminalPane target = focusable()
                .filter(pane -> pane != active)
                .filter(pane -> directionFilter(direction, active, pane))
                .min(Comparator.comparingDouble(pane -> distance(active, pane)))
                .orElse(null);
        if (target != null) {
            setActive(target);
            return true;
        }
        return false;
    }

    void toggleFloating() {
        if (floating.isEmpty()) {
            createFloatingPane();
            return;
        }
        if (floatingVisible) {
            floatingVisible = false;
            if (floating.contains(active)) {
                setActive(tiled.contains(lastFocusedTiled) ? lastFocusedTiled : tiled.get(0));
            }
        } else {
            floatingVisible = true;
            setActive(floating.contains(lastFocusedFloating) ? lastFocusedFloating : floating.get(floating.size() - 1));
        }
    }

    /** Adds a floating pane while the floating group is shown, otherwise a tiled pane. */
    void createPane() {
        if (floatingVisible) {
            createFloatingPane();
        } else {
            createTiledPane(paneWorkingDirectory());
        }
    }

    TerminalPane createTiledPane(String workingDirectory) {
        TerminalPane pane = openPane(false, workingDirectory);
        tiled.add(pane);
        setActive(pane);
        return pane;
    }

    TerminalPane createFloatingPaneInDirectory(String workingDirectory) {
        return addFloating(openPane(true, workingDirectory));
    }

    void nextFloatingPane() {
        if (floating.isEmpty()) {
            createFloatingPane();
            return;
        }
        floatingVisible = true;
        int current = floating.indexOf(active); // -1 when the active pane is tiled
        setActive(floating.get((current + 1 + floating.size()) % floating.size()));
    }

    /** Promotes the active floating pane to a tiled pane, joining the tiled row. No-op otherwise. */
    void toggleActiveFloating() {
        TerminalPane toggled = active;
        if (floating.remove(toggled)) {
            lastFocusedFloating = floating.isEmpty() ? null : floating.get(floating.size() - 1);
            tiled.add(toggled);
            floatingVisible = false;
            setActive(toggled);
        } else if (tiled.remove(toggled)) {
            lastFocusedTiled = tiled.isEmpty() ? null : tiled.get(tiled.size() -1);
            floating.add(toggled);
            floatingVisible = true;
            setActive(toggled);
        }
    }

    /**
     * Closes {@code closing} (the active pane on a key-bound close, or any pane whose process just
     * exited) and re-selects the active pane only when the one that closed was active. Returns
     * false when the pane is not in this tab. Leaves the tab empty ({@code active == null}) when its
     * last pane closes, so the compositor can drop it.
     */
    boolean closePane(TerminalPane closing) {
        boolean wasFloating = floating.remove(closing);
        boolean wasTiled = !wasFloating && tiled.remove(closing);
        if (!wasFloating && !wasTiled) {
            return false; // not one of this tab's panes (already gone)
        }
        boolean wasActive = closing == active;
        if (closing == lastFocusedFloating) {
            lastFocusedFloating = null;
        }
        if (closing == lastFocusedTiled) {
            lastFocusedTiled = null;
        }
        closing.close();

        if (tiled.isEmpty() && floating.isEmpty()) {
            active = null; // tab is now empty; the compositor drops it
            return true;
        }

        // Always keep a tiled base: if the last tiled pane just closed, promote a floating one
        // (preferring the last focused).
        if (tiled.isEmpty()) {
            TerminalPane promote = floating.contains(lastFocusedFloating) ? lastFocusedFloating : floating.get(0);
            var promoteIndex = floating.indexOf(promote);
            var nextFocussed = promoteIndex == 0 ? 0 : promoteIndex - 1;
            floating.remove(promote);
            tiled.add(promote);
            if (promote == lastFocusedFloating) {
                lastFocusedFloating = floating.isEmpty() ? null : floating.get(nextFocussed);
            }
        }
        if (floating.isEmpty()) {
            floatingVisible = false;
        }

        // Only the active pane closing forces a re-selection; closing a background pane (e.g. one
        // whose process exited while another is focused) leaves focus where it is.
        if (wasActive) {
            setActive(wasFloating && floatingVisible
                    ? floating.get(floating.size() - 1)
                    : tiled.contains(lastFocusedTiled) ? lastFocusedTiled : tiled.get(0));
        }
        return true;
    }

    private void setActive(TerminalPane pane) {
        active = pane;
        if (floating.contains(pane)) {
            lastFocusedFloating = pane;
        } else if (tiled.contains(pane)) {
            lastFocusedTiled = pane;
            // A tiled pane gaining focus hides the floating group: leaving it shown while a tiled
            // pane is active strands focus behind the overlay and disables navigation.
            floatingVisible = false;
        }
    }

    private void createFloatingPane() {
        addFloating(openPane(true, paneWorkingDirectory()));
    }

    /**
     * Opens a floating pane whose process runs {@code command} directly (auto-closing when it
     * exits), rather than an interactive shell. Used for one-shot panes like the scrollback editor.
     */
    TerminalPane createFloatingPane(String command) {
        double[] size = paneSize(true);
        return addFloating(register(TerminalPane.createWithCommand(
                config, metrics, this::markContentChanged, size[0], size[1], paneWorkingDirectory(), command)));
    }

    private TerminalPane addFloating(TerminalPane pane) {
        floating.add(pane);
        floatingVisible = true;
        setActive(pane);
        return pane;
    }

    private boolean navigateFloatingStack(Direction direction) {
        if (floating.size() < 2) {
            return false;
        }
        int current = floating.indexOf(active);
        if (current < 0) {
            return false;
        }
        int next = switch (direction) {
            case LEFT, UP -> current - 1;
            case DOWN, RIGHT -> current + 1;
        };
        if (next < 0 || next >= floating.size()) {
            return false;
        }
        setActive(floating.get(next));
        return true;
    }

    private boolean isFocusable(TerminalPane pane) {
        return tiled.contains(pane) || (floatingVisible && floating.contains(pane));
    }

    private Stream<TerminalPane> focusable() {
        return floatingVisible ? Stream.concat(tiled.stream(), floating.stream()) : tiled.stream();
    }

    private void markContentChanged() {
        contentVersion.incrementAndGet();
    }

    private TerminalPane openPane(boolean asFloating) {
        return openPane(asFloating, paneWorkingDirectory());
    }

    private TerminalPane openPane(boolean asFloating, String workingDirectory) {
        double[] size = paneSize(asFloating);
        return register(TerminalPane.create(
                config, metrics, this::markContentChanged, size[0], size[1], workingDirectory));
    }

    private double[] paneSize(boolean asFloating) {
        double availHeight = lastHeight - lastTopInset;
        if (asFloating) {
            return new double[] {
                    Math.max(FLOATING_MIN_WIDTH, lastWidth * FLOATING_SIZE_FRACTION),
                    Math.max(FLOATING_MIN_HEIGHT, availHeight * FLOATING_SIZE_FRACTION)};
        }
        // A new tiled pane joins the row, so each gets 1/(n+1) of the width.
        return new double[] {lastWidth / (tiled.size() + 1), availHeight};
    }

    // Open a new pane in the active pane's working directory, so a split/new pane lands where the
    // user currently is. With no active pane yet (the tab's first pane), fall back to the directory
    // this tab was opened in. null (cwd unknown) falls back to home downstream.
    private String paneWorkingDirectory() {
        return active != null ? active.currentWorkingDirectory() : initialWorkingDirectory;
    }

    // Wire the pane's self-exit (process ended) back to the compositor so it gets reaped.
    private TerminalPane register(TerminalPane pane) {
        pane.setOnExit(() -> onPaneExit.accept(pane));
        return pane;
    }

    private static boolean directionFilter(Direction direction, TerminalPane current, TerminalPane candidate) {
        double currentCenterX = current.x() + current.width() / 2.0;
        double currentCenterY = current.y() + current.height() / 2.0;
        double candidateCenterX = candidate.x() + candidate.width() / 2.0;
        double candidateCenterY = candidate.y() + candidate.height() / 2.0;

        return switch (direction) {
            case LEFT -> candidateCenterX < currentCenterX;
            case DOWN -> candidateCenterY > currentCenterY;
            case UP -> candidateCenterY < currentCenterY;
            case RIGHT -> candidateCenterX > currentCenterX;
        };
    }

    private static double distance(TerminalPane current, TerminalPane candidate) {
        double dx = (current.x() + current.width() / 2.0) - (candidate.x() + candidate.width() / 2.0);
        double dy = (current.y() + current.height() / 2.0) - (candidate.y() + candidate.height() / 2.0);
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public void close() {
        tiled.forEach(TerminalPane::close);
        floating.forEach(TerminalPane::close);
        tiled.clear();
        floating.clear();
    }

    /**
     * Signals and reaps every pane's shell process without tearing down render state. Safe to call
     * off the FX thread (see {@link TerminalPane#terminateSession()}); iterates snapshots so a
     * concurrent close on the FX thread can't trigger a {@link java.util.ConcurrentModificationException}.
     */
    public void terminateSessions() {
        List.copyOf(tiled).forEach(TerminalPane::terminateSession);
        List.copyOf(floating).forEach(TerminalPane::terminateSession);
    }
}
