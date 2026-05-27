package com.gregor.jprototerm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TerminalWorkspace implements AutoCloseable {
    private final AppConfig config;
    private final List<TerminalPane> panes = new ArrayList<>();
    private int activeIndex;

    public TerminalWorkspace(AppConfig config) {
        this.config = config;
        panes.add(openPane(false));
    }

    public TerminalPane activePane() {
        return panes.get(activeIndex);
    }

    public List<TerminalPane> panes() {
        return List.copyOf(panes);
    }

    public boolean isActive(TerminalPane pane) {
        return activePane() == pane;
    }

    public void layout(double width, double height) {
        List<TerminalPane> tiled = panes.stream().filter(pane -> !pane.floating()).toList();
        int tileCount = Math.max(1, tiled.size());
        double tileWidth = width / tileCount;
        for (int i = 0; i < tiled.size(); i++) {
            tiled.get(i).bounds(i * tileWidth, 0, tileWidth, height);
        }

        for (TerminalPane pane : panes) {
            if (pane.floating()) {
                double floatingWidth = Math.max(420, width * 0.58);
                double floatingHeight = Math.max(260, height * 0.58);
                pane.bounds(
                        (width - floatingWidth) / 2.0,
                        (height - floatingHeight) / 2.0,
                        floatingWidth,
                        floatingHeight
                );
            }
        }
    }

    public void navigate(Direction direction) {
        TerminalPane current = activePane();
        panes.stream()
                .filter(pane -> pane != current)
                .filter(pane -> directionFilter(direction, current, pane))
                .min(Comparator.comparingDouble(pane -> distance(current, pane)))
                .ifPresent(pane -> activeIndex = panes.indexOf(pane));
    }

    public void toggleFloating() {
        TerminalPane active = activePane();
        if (active.floating()) {
            panes.remove(activeIndex);
            active.close();
            activeIndex = Math.max(0, activeIndex - 1);
            return;
        }

        TerminalPane pane = openPane(true);
        panes.add(pane);
        activeIndex = panes.size() - 1;
    }

    private TerminalPane openPane(boolean floating) {
        TerminalPane pane = TerminalPane.create(config.columns(), config.rows(), config.kittyGraphics());
        pane.setFloating(floating);
        pane.attach(ShellSession.start(config.shell(), pane, pane.graphicsRegistry()));
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
