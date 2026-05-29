package com.gregor.jprototerm;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the tabs and renders only the current one. Pane operations delegate to the current
 * tab; tab operations switch which tab is current. A single render version bumps on any
 * change (intra-tab or tab switch) so the renderer recomposites when needed.
 */
public final class TerminalWorkspace implements AutoCloseable {
    private final AppConfig config;
    private final List<Tab> tabs = new ArrayList<>();
    private int currentTab;
    private long version;

    public TerminalWorkspace(AppConfig config) {
        this.config = config;
        tabs.add(new Tab(config));
    }

    private Tab current() {
        return tabs.get(currentTab);
    }

    public long version() {
        return version;
    }

    public boolean isEmpty() {
        return tabs.isEmpty();
    }

    public TerminalPane activePane() {
        return current().activePane();
    }

    public List<TerminalPane> panes() {
        return tabs.isEmpty() ? List.of() : current().panes();
    }

    public boolean isActive(TerminalPane pane) {
        return !tabs.isEmpty() && current().isActive(pane);
    }

    public void layout(double width, double height, double topInset) {
        if (!tabs.isEmpty()) {
            current().layout(width, height, topInset);
        }
    }

    public int tabCount() {
        return tabs.size();
    }

    public int currentTabIndex() {
        return currentTab;
    }

    public void focus(TerminalPane pane) {
        if (!tabs.isEmpty() && current().focus(pane)) {
            version++;
        }
    }

    public void navigate(Direction direction) {
        if (!tabs.isEmpty() && current().navigate(direction)) {
            version++;
        }
    }

    public void toggleFloating() {
        if (tabs.isEmpty()) {
            return;
        }
        current().toggleFloating();
        version++;
    }

    public void createPane() {
        if (tabs.isEmpty()) {
            return;
        }
        current().createPane();
        version++;
    }

    public void nextFloatingPane() {
        if (tabs.isEmpty()) {
            return;
        }
        current().nextFloatingPane();
        version++;
    }

    public void closeActivePane() {
        if (tabs.isEmpty()) {
            return;
        }
        current().closeActivePane();
        if (current().isEmpty()) {
            // Closing a tab's last pane closes the tab. When no tabs remain the workspace
            // is empty and Main quits.
            tabs.remove(currentTab);
            if (currentTab >= tabs.size()) {
                currentTab = Math.max(0, tabs.size() - 1);
            }
        }
        version++;
    }

    public void newTab() {
        tabs.add(new Tab(config));
        currentTab = tabs.size() - 1;
        version++;
    }

    public void nextTab() {
        if (tabs.size() > 1) {
            currentTab = (currentTab + 1) % tabs.size();
            version++;
        }
    }

    public void previousTab() {
        if (tabs.size() > 1) {
            currentTab = (currentTab - 1 + tabs.size()) % tabs.size();
            version++;
        }
    }

    @Override
    public void close() {
        for (Tab tab : tabs) {
            tab.close();
        }
        tabs.clear();
    }
}
