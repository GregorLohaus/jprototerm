package com.gregor.jprototerm;

import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * One tab: a row of tiled panes with a group of floating panes shown over them. Floating panes
 * are shown/hidden as a group ({@code floatingVisible}), and there is always at least one tiled
 * pane — a floating pane is promoted if the last tiled one closes — so the layout always has a
 * base. The {@link Compositor} owns the tabs and renders only the current one; mutating methods
 * return whether they actually changed anything so it can bump its layout version.
 */
final class Tab implements AutoCloseable {
    private final AppConfig config;
    private final TerminalMetrics metrics;
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

    Tab(AppConfig config, TerminalMetrics metrics) {
        this(config, metrics, null);
    }

    /**
     * Creates a tab whose first pane starts in {@code initialWorkingDirectory} (e.g. the cwd of the
     * pane that was active when this tab was opened), or the user's home when {@code null}.
     */
    Tab(AppConfig config, TerminalMetrics metrics, String initialWorkingDirectory) {
        this.config = config;
        this.metrics = metrics;
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
        for (TerminalPane pane : floating) {
            if (pane != active) {
                ordered.add(pane);
            }
        }
        if (floating.contains(active)) {
            ordered.add(active); // active floating pane on top
        }
        return List.copyOf(ordered);
    }

    boolean isActive(TerminalPane pane) {
        return pane != null && pane == active;
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

        double floatingWidth = Math.max(420, width * 0.58);
        double floatingHeight = Math.max(260, availHeight * 0.58);
        for (int i = 0; i < floating.size(); i++) {
            double offset = i * 28.0;
            floating.get(i).bounds(
                    Math.min(width - floatingWidth - 12.0, ((width - floatingWidth) / 2.0) + offset),
                    Math.min(height - floatingHeight - 12.0, topInset + ((availHeight - floatingHeight) / 2.0) + offset),
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
            tiled.forEach(pane -> pane.setClip(null));
            floating.forEach(pane -> pane.setClip(null));
            return;
        }

        // Floating panes bottom-to-top, matching panes(): insertion order, active pane on top.
        List<TerminalPane> order = new ArrayList<>(floating.size());
        for (TerminalPane pane : floating) {
            if (pane != active) {
                order.add(pane);
            }
        }
        if (floating.contains(active)) {
            order.add(active);
        }

        // Walk top-to-bottom, accumulating the union of the panes above each one.
        Shape above = null;
        for (int i = order.size() - 1; i >= 0; i--) {
            Rectangle rect = rectOf(order.get(i));
            order.get(i).setClip(above == null ? null : Shape.subtract(rect, above));
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
            TerminalPane pane = openPane(false);
            tiled.add(pane);
            setActive(pane);
        }
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
    void promoteActiveFloating() {
        TerminalPane promote = active;
        if (!floating.remove(promote)) {
            return; // active pane is tiled (or there is none); nothing to promote
        }
        if (promote == lastFocusedFloating) {
            lastFocusedFloating = floating.isEmpty() ? null : floating.get(floating.size() - 1);
        }
        tiled.add(promote);
        if (floating.isEmpty()) {
            floatingVisible = false;
        }
        setActive(promote);
    }

    void closeActivePane() {
        TerminalPane closing = active;
        boolean wasFloating = floating.remove(closing);
        if (!wasFloating) {
            tiled.remove(closing);
        }
        if (closing == lastFocusedFloating) {
            lastFocusedFloating = null;
        }
        closing.close();

        if (tiled.isEmpty() && floating.isEmpty()) {
            active = null; // tab is now empty; the compositor drops it
            return;
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
                lastFocusedFloating = null;
                if (!floating.isEmpty()) {
                    lastFocusedFloating = floating.isEmpty() ? null : floating.get(nextFocussed);
                }
            }
        }
        if (floating.isEmpty()) {
            floatingVisible = false;
        }

        setActive(wasFloating && floatingVisible
                ? floating.get(floating.size() - 1)
                : tiled.contains(lastFocusedTiled) ? lastFocusedTiled : tiled.get(0));
    }

    private void setActive(TerminalPane pane) {
        active = pane;
        if (floating.contains(pane)) {
            lastFocusedFloating = pane;
        } else if (tiled.contains(pane)) {
            lastFocusedTiled = pane;
        }
    }

    TerminalPane createFloatingPane() {
        TerminalPane pane = openPane(true);
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
        double availHeight = lastHeight - lastTopInset;
        double widthPx;
        double heightPx;
        if (asFloating) {
            widthPx = Math.max(420, lastWidth * 0.58);
            heightPx = Math.max(260, availHeight * 0.58);
        } else {
            // A new tiled pane joins the row, so each gets 1/(n+1) of the width.
            widthPx = lastWidth / (tiled.size() + 1);
            heightPx = availHeight;
        }
        // Open the new pane in the active pane's working directory, so a split/new pane lands
        // where the user currently is. With no active pane yet (the tab's first pane), fall back to
        // the directory this tab was opened in. null (cwd unknown) falls back to home downstream.
        String workingDirectory = active != null ? active.currentWorkingDirectory() : initialWorkingDirectory;
        return TerminalPane.create(config, metrics, this::markContentChanged, widthPx, heightPx, workingDirectory);
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
}
