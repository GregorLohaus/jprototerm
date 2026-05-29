package com.gregor.jprototerm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One tab: an isolated stack of panes (tiled + floating) with its own active pane and
 * stashed-floating state. {@link TerminalWorkspace} owns the list of tabs and renders only
 * the current one. Mutating methods return whether they actually changed anything so the
 * workspace can bump its render version conditionally.
 */
final class Tab implements AutoCloseable {
    private final AppConfig config;
    private final List<TerminalPane> panes = new ArrayList<>();
    private int activeIndex;
    private int hiddenFloatingFocusIndex = -1;
    private TerminalPane lastFocusedFloating;

    Tab(AppConfig config) {
        this.config = config;
        panes.add(openPane(false));
    }

    TerminalPane activePane() {
        return panes.get(activeIndex);
    }

    boolean isEmpty() {
        return panes.isEmpty();
    }

    List<TerminalPane> panes() {
        if (panes.isEmpty()) {
            return List.of();
        }
        List<TerminalPane> visible = panes.stream().filter(TerminalPane::visible).toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        // Draw order = z-order: all tiled panes first (they never overlap), then floating
        // panes on top, with the active floating pane last (topmost). This holds regardless
        // of creation order, so a tiled pane created after a floating one still sits behind.
        TerminalPane active = activePane();
        List<TerminalPane> ordered = new ArrayList<>(visible.size());
        for (TerminalPane pane : visible) {
            if (!pane.floating()) {
                ordered.add(pane);
            }
        }
        for (TerminalPane pane : visible) {
            if (pane.floating() && pane != active) {
                ordered.add(pane);
            }
        }
        if (active.visible() && active.floating()) {
            ordered.add(active);
        }
        return List.copyOf(ordered);
    }

    boolean isActive(TerminalPane pane) {
        return !panes.isEmpty() && activePane() == pane;
    }

    boolean focus(TerminalPane pane) {
        int index = panes.indexOf(pane);
        if (index >= 0 && pane.visible() && activeIndex != index) {
            setActive(index);
            return true;
        }
        return false;
    }

    void layout(double width, double height, double topInset) {
        double availHeight = height - topInset;
        List<TerminalPane> tiled = panes.stream()
                .filter(TerminalPane::visible)
                .filter(pane -> !pane.floating())
                .toList();
        int tileCount = Math.max(1, tiled.size());
        double tileWidth = width / tileCount;
        for (int i = 0; i < tiled.size(); i++) {
            tiled.get(i).bounds(i * tileWidth, topInset, tileWidth, availHeight);
        }

        List<TerminalPane> floating = panes.stream()
                .filter(TerminalPane::visible)
                .filter(TerminalPane::floating)
                .toList();
        for (int i = 0; i < floating.size(); i++) {
            TerminalPane pane = floating.get(i);
            double floatingWidth = Math.max(420, width * 0.58);
            double floatingHeight = Math.max(260, availHeight * 0.58);
            double offset = i * 28.0;
            pane.bounds(
                    Math.min(width - floatingWidth - 12.0, ((width - floatingWidth) / 2.0) + offset),
                    Math.min(height - floatingHeight - 12.0, topInset + ((availHeight - floatingHeight) / 2.0) + offset),
                    floatingWidth,
                    floatingHeight
            );
        }
    }

    boolean navigate(Direction direction) {
        TerminalPane current = activePane();
        if (current.floating() && navigateFloatingStack(direction)) {
            return true;
        }

        TerminalPane target = panes.stream()
                .filter(TerminalPane::visible)
                .filter(pane -> pane != current)
                .filter(pane -> directionFilter(direction, current, pane))
                .min(Comparator.comparingDouble(pane -> distance(current, pane)))
                .orElse(null);
        if (target != null) {
            setActive(panes.indexOf(target));
            return true;
        }
        return false;
    }

    void toggleFloating() {
        List<TerminalPane> floating = panes.stream()
                .filter(TerminalPane::floating)
                .toList();
        if (floating.isEmpty()) {
            createFloatingPane();
            return;
        }

        boolean anyVisible = floating.stream().anyMatch(TerminalPane::visible);
        if (anyVisible) {
            TerminalPane active = activePane();
            hiddenFloatingFocusIndex = active.floating() ? activeIndex : firstVisibleFloatingIndex();
            floating.forEach(pane -> pane.setVisible(false));
            setActive(firstVisibleNonFloatingIndex());
        } else {
            floating.forEach(pane -> pane.setVisible(true));
            setActive(visibleIndexOrFallback(hiddenFloatingFocusIndex, panes.indexOf(floating.get(floating.size() - 1))));
            hiddenFloatingFocusIndex = -1;
        }
    }

    /**
     * "New pane": adds a floating pane while floating panes are shown, otherwise adds a
     * tiled pane (the tiled row is redistributed equally by the layout).
     */
    void createPane() {
        if (anyFloatingVisible()) {
            createFloatingPane();
        } else {
            TerminalPane pane = openPane(false);
            panes.add(pane);
            setActive(panes.size() - 1);
        }
    }

    void nextFloatingPane() {
        TerminalPane next = nextFloatingAfter(activeIndex);
        next.setVisible(true);
        setActive(panes.indexOf(next));
    }

    void closeActivePane() {
        TerminalPane active = activePane();
        int removed = activeIndex;
        // When closing a floating pane, focus the next visible floating pane if there is one
        // (don't jump to a tiled pane); otherwise fall back to the nearest visible pane.
        int target = active.floating() ? nearestVisibleFloatingIndex(removed) : -1;
        if (target < 0) {
            target = previousVisibleIndex(removed);
        }
        panes.remove(removed);
        if (active == lastFocusedFloating) {
            lastFocusedFloating = null;
        }
        active.close();
        if (panes.isEmpty()) {
            activeIndex = 0;
            return;
        }
        activeIndex = adjustIndexAfterRemoval(target, removed);
        hiddenFloatingFocusIndex = adjustHiddenFocusAfterRemoval(hiddenFloatingFocusIndex, removed);

        // If the last tiled (main) pane was closed, promote a floating pane to be the new
        // main pane so the layout has a base and rendering continues normally. Prefer the
        // most recently focused floating pane.
        if (panes.stream().noneMatch(pane -> !pane.floating())) {
            TerminalPane promote = (lastFocusedFloating != null && panes.contains(lastFocusedFloating))
                    ? lastFocusedFloating
                    : panes.get(activeIndex);
            promote.setFloating(false);
            promote.setVisible(true);
            activeIndex = panes.indexOf(promote);
            lastFocusedFloating = null;
        }

        // If only hidden panes remained, reveal the one we're focusing so the screen isn't
        // blank.
        if (!panes.get(activeIndex).visible()) {
            panes.get(activeIndex).setVisible(true);
        }
    }

    private void setActive(int index) {
        activeIndex = index;
        if (index >= 0 && index < panes.size() && panes.get(index).floating()) {
            lastFocusedFloating = panes.get(index);
        }
    }

    private void createFloatingPane() {
        TerminalPane pane = openPane(true);
        panes.add(pane);
        setActive(panes.size() - 1);
    }

    private boolean anyFloatingVisible() {
        return panes.stream().anyMatch(pane -> pane.floating() && pane.visible());
    }

    private TerminalPane nextFloatingAfter(int index) {
        for (int i = index + 1; i < panes.size(); i++) {
            TerminalPane pane = panes.get(i);
            if (pane.floating()) {
                return pane;
            }
        }
        for (int i = 0; i <= index && i < panes.size(); i++) {
            TerminalPane pane = panes.get(i);
            if (pane.floating()) {
                return pane;
            }
        }
        return createAndReturnFloatingPane();
    }

    private TerminalPane createAndReturnFloatingPane() {
        TerminalPane pane = openPane(true);
        panes.add(pane);
        return pane;
    }

    private boolean navigateFloatingStack(Direction direction) {
        List<TerminalPane> floating = panes.stream()
                .filter(TerminalPane::visible)
                .filter(TerminalPane::floating)
                .toList();
        if (floating.size() < 2) {
            return false;
        }

        int current = floating.indexOf(activePane());
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

        setActive(panes.indexOf(floating.get(next)));
        return true;
    }

    private int firstVisibleFloatingIndex() {
        for (int i = 0; i < panes.size(); i++) {
            TerminalPane pane = panes.get(i);
            if (pane.visible() && pane.floating()) {
                return i;
            }
        }
        return -1;
    }

    private int firstVisibleNonFloatingIndex() {
        for (int i = 0; i < panes.size(); i++) {
            TerminalPane pane = panes.get(i);
            if (pane.visible() && !pane.floating()) {
                return i;
            }
        }
        return 0;
    }

    private int nearestVisibleFloatingIndex(int index) {
        for (int i = index + 1; i < panes.size(); i++) {
            if (panes.get(i).visible() && panes.get(i).floating()) {
                return i;
            }
        }
        for (int i = index - 1; i >= 0; i--) {
            if (panes.get(i).visible() && panes.get(i).floating()) {
                return i;
            }
        }
        return -1;
    }

    private int previousVisibleIndex(int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (panes.get(i).visible()) {
                return i;
            }
        }
        for (int i = index + 1; i < panes.size(); i++) {
            if (panes.get(i).visible()) {
                return i;
            }
        }
        return firstVisibleNonFloatingIndex();
    }

    private int visibleIndexOrFallback(int index, int fallback) {
        if (index >= 0 && index < panes.size() && panes.get(index).visible()) {
            return index;
        }
        return fallback;
    }

    private static int adjustIndexAfterRemoval(int index, int removedIndex) {
        if (index < 0) {
            return 0;
        }
        return index > removedIndex ? index - 1 : index;
    }

    private static int adjustHiddenFocusAfterRemoval(int index, int removedIndex) {
        if (index < 0 || index == removedIndex) {
            return -1;
        }
        return index > removedIndex ? index - 1 : index;
    }

    private TerminalPane openPane(boolean floating) {
        TerminalPane pane = TerminalPane.create(config.columns(), config.rows(), config.maxScrollback());
        pane.setFloating(floating);
        pane.attach(ShellSession.start(config.shell(), config.envOverride(), pane, config.columns(), config.rows()));
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
        for (TerminalPane pane : panes) {
            pane.close();
        }
        panes.clear();
    }
}
