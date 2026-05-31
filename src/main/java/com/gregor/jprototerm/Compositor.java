package com.gregor.jprototerm;

import dev.jlibghostty.KeyModifiers;
import dev.jlibghostty.MouseButton;
import dev.jlibghostty.MouseEncoderSize;
import dev.jlibghostty.MouseInput;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.InputEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ScrollEvent.VerticalTextScrollUnits;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the window's tabs and exposes the terminal surface as a JavaFX scene graph. Each
 * terminal pane is mounted as its own node, so JavaFX child order handles stacking and clipping
 * between panes. The pane model still owns terminals, ptys, cell geometry, and snapshots; this
 * class handles tab/pane lifecycle, layout, focus, mouse routing, and frame scheduling.
 */
public final class Compositor {
    private static final Color GAP_BACKGROUND = Color.rgb(16, 16, 18);
    private static final Color TAB_TEXT = Color.rgb(225, 229, 235);
    private static final Color TAB_INACTIVE_TEXT = Color.rgb(128, 136, 148);
    private static final Color TAB_ACTIVE_BACKGROUND = Color.rgb(45, 55, 72);
    private static final Color TAB_INACTIVE_BACKGROUND = Color.rgb(22, 24, 28);
    private static final double TAB_BAR_HEIGHT = 22.0;

    private final Pane root = new Pane();
    private final Pane paneLayer = new Pane();
    private final HBox tabBar = new HBox(1.0);
    private final AppConfig config;
    private final TerminalMetrics metrics;
    private final List<Tab> tabs = new ArrayList<>();
    private final Map<TerminalPane, TerminalPaneNode> nodes = new HashMap<>();
    private int currentTabIndex;
    private long layoutVersion;
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

        root.setFocusTraversable(true);
        root.setBackground(new Background(new BackgroundFill(GAP_BACKGROUND, CornerRadii.EMPTY, null)));
        root.getChildren().setAll(paneLayer, tabBar);
        root.setOnMousePressed(event -> root.requestFocus());
    }

    public Parent node() {
        return root;
    }

    public void requestFocus() {
        root.requestFocus();
    }

    public void setFont(String family, double size) {
        metrics.setFont(family, size);
        nodes.values().forEach(TerminalPaneNode::discard);
        lastWidth = -1.0;
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
        nodes.clear();
        paneLayer.getChildren().clear();
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

    private FrameType nextFrameType() {
        double width = root.getWidth();
        double height = root.getHeight();
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

    private void renderLayoutFrame() {
        double width = root.getWidth();
        double height = root.getHeight();
        double topInset = tabs.size() > 1 ? TAB_BAR_HEIGHT : 0.0;

        paneLayer.resizeRelocate(0.0, 0.0, width, height);
        updateTabBar(width, topInset);

        if (!tabs.isEmpty()) {
            currentTab().layout(width, height, topInset);
        }

        List<TerminalPane> panes = currentPanes();
        retainNodes(panes);
        List<TerminalPaneNode> orderedNodes = new ArrayList<>(panes.size());
        for (TerminalPane pane : panes) {
            pane.fitToBounds();
            TerminalPaneNode node = nodeFor(pane);
            node.resizeRelocate(Math.round(pane.x()), Math.round(pane.y()), pane.width(), pane.height());
            node.renderIncremental(isActive(pane));
            orderedNodes.add(node);
        }
        paneLayer.getChildren().setAll(orderedNodes);
    }

    private void renderContentFrame() {
        for (TerminalPane pane : currentPanes()) {
            TerminalPaneNode node = nodes.get(pane);
            if (node != null) {
                node.renderIncremental(isActive(pane));
            }
        }
    }

    private TerminalPaneNode nodeFor(TerminalPane pane) {
        return nodes.computeIfAbsent(pane, this::createNode);
    }

    private TerminalPaneNode createNode(TerminalPane pane) {
        TerminalPaneNode node = new TerminalPaneNode(pane, metrics);
        node.setOnMousePressed(event -> handleMousePressed(pane, event));
        node.setOnMouseReleased(event -> handleMouseReleased(pane, event));
        node.setOnMouseDragged(event -> handleMouseDragged(pane, event));
        node.setOnMouseMoved(event -> handleMouseMoved(pane, event));
        node.setOnScroll(event -> handleScroll(pane, event));
        return node;
    }

    private void retainNodes(List<TerminalPane> visiblePanes) {
        Set<TerminalPane> visible = new HashSet<>(visiblePanes);
        nodes.keySet().removeIf(pane -> !visible.contains(pane));
    }

    private void updateTabBar(double width, double barHeight) {
        tabBar.setVisible(barHeight > 0.0);
        tabBar.setManaged(false);
        tabBar.resizeRelocate(0.0, 0.0, width, barHeight);
        tabBar.getChildren().clear();
        if (barHeight <= 0.0) {
            return;
        }

        double segmentWidth = width / tabs.size();
        for (int i = 0; i < tabs.size(); i++) {
            Label label = new Label(Integer.toString(i + 1));
            boolean current = i == currentTabIndex;
            label.setAlignment(Pos.CENTER);
            label.setTextFill(current ? TAB_TEXT : TAB_INACTIVE_TEXT);
            label.setBackground(new Background(new BackgroundFill(
                    current ? TAB_ACTIVE_BACKGROUND : TAB_INACTIVE_BACKGROUND,
                    CornerRadii.EMPTY,
                    null)));
            label.setFont(javafx.scene.text.Font.font(metrics.fontFamily(), Math.max(9.0, Math.min(13.0, barHeight * 0.62))));
            label.setMinSize(0.0, barHeight);
            label.setPrefSize(Math.max(0.0, segmentWidth - 1.0), barHeight);
            label.setMaxSize(Double.MAX_VALUE, barHeight);
            final int index = i;
            label.setOnMousePressed(event -> {
                currentTabIndex = index;
                layoutVersion++;
                root.requestFocus();
                event.consume();
            });
            tabBar.getChildren().add(label);
        }
    }

    // ---- Input ----------------------------------------------------------------------

    private void handleMousePressed(TerminalPane pane, MouseEvent event) {
        root.requestFocus();
        focus(pane);
        pressedButton = mouseButton(event);
        mouseButtonPressed = true;
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return;
        }
        send(pane, target, MouseInput.press(pressedButton, localX(event.getX(), target), localY(event.getY(), target), modifiers(event)), true, event);
    }

    private void handleMouseReleased(TerminalPane pane, MouseEvent event) {
        MouseButton button = pressedButton == MouseButton.UNKNOWN ? mouseButton(event) : pressedButton;
        MouseTarget target = mouseTarget(pane);
        if (target != null) {
            send(pane, target, MouseInput.release(button, localX(event.getX(), target), localY(event.getY(), target), modifiers(event)), false, event);
        }
        mouseButtonPressed = false;
        pressedButton = MouseButton.UNKNOWN;
    }

    private void handleMouseDragged(TerminalPane pane, MouseEvent event) {
        MouseButton button = pressedButton == MouseButton.UNKNOWN ? mouseButton(event) : pressedButton;
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return;
        }
        send(pane, target, MouseInput.drag(button, localX(event.getX(), target), localY(event.getY(), target), modifiers(event)), true, event);
    }

    private void handleMouseMoved(TerminalPane pane, MouseEvent event) {
        MouseTarget target = mouseTarget(pane);
        if (target == null) {
            return;
        }
        send(pane, target, MouseInput.motion(localX(event.getX(), target), localY(event.getY(), target), modifiers(event)), mouseButtonPressed, event);
    }

    private void handleScroll(TerminalPane pane, ScrollEvent event) {
        root.requestFocus();
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
            double ex = localX(event.getX(), target);
            double ey = localY(event.getY(), target);
            KeyModifiers modifiers = modifiers(event);
            for (int i = 0; i < rows; i++) {
                if (!send(pane, target, MouseInput.press(wheelButton, ex, ey, modifiers), mouseButtonPressed, event)) {
                    break;
                }
                sent = true;
            }
        }
        if (!sent) {
            pane.scrollViewport(direction > 0 ? -rows : rows);
            event.consume();
        }
    }

    private boolean send(TerminalPane pane, MouseTarget target, MouseInput input, boolean anyButtonPressed, InputEvent event) {
        boolean sent = pane.sendMouse(input, target.size(), anyButtonPressed);
        if (sent) {
            event.consume();
        }
        return sent;
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

    private static double localX(double nodeX, MouseTarget target) {
        return clamp(nodeX - TerminalMetrics.PADDING, 0.0, target.screenWidth() - 1.0);
    }

    private static double localY(double nodeY, MouseTarget target) {
        return clamp(nodeY - TerminalMetrics.PADDING, 0.0, target.screenHeight() - 1.0);
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

    private enum FrameType {
        IDLE,
        LAYOUT,
        CONTENT
    }

    private record MouseTarget(MouseEncoderSize size, long screenWidth, long screenHeight) {
    }
}
