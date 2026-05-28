package com.gregor.jprototerm;

import dev.jlibghostty.KittyImageCompression;
import dev.jlibghostty.KittyImageFormat;
import dev.jlibghostty.KittyImageSnapshot;
import dev.jlibghostty.KittyPlacement;
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
import java.util.Map;

public final class TerminalCanvasView {
    private static final Color DEFAULT_FOREGROUND = Color.rgb(225, 229, 235);
    private static final Color SELECTED_BACKGROUND = Color.rgb(52, 92, 140);

    private final Canvas canvas = new Canvas();
    private final TerminalWorkspace workspace;
    private final AppConfig config;
    private final Map<Long, Image> kittyImageCache = new HashMap<>();
    private String fontFamily;
    private double fontSize;
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
    }

    public void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        workspace.layout(width, height);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.rgb(16, 16, 18));
        gc.fillRect(0, 0, width, height);
        gc.setFontSmoothingType(FontSmoothingType.LCD);

        for (TerminalPane pane : workspace.panes()) {
            drawPane(gc, pane);
        }
    }

    private void drawPane(GraphicsContext gc, TerminalPane pane) {
        gc.save();
        gc.beginPath();
        gc.rect(pane.x(), pane.y(), pane.width(), pane.height());
        gc.clip();

        if (pane.floating()) {
            gc.setGlobalAlpha(0.96);
        }
        gc.setFill(Color.rgb(9, 10, 12));
        gc.fillRect(pane.x(), pane.y(), pane.width(), pane.height());
        gc.setGlobalAlpha(1.0);

        gc.setStroke(workspace.isActive(pane) ? Color.rgb(87, 166, 255) : Color.rgb(52, 57, 65));
        gc.setLineWidth(workspace.isActive(pane) ? 2.0 : 1.0);
        gc.strokeRect(pane.x() + 0.5, pane.y() + 0.5, pane.width() - 1.0, pane.height() - 1.0);

        Font font = Font.font(fontFamily, fontSize);
        gc.setFont(font);

        FontMetrics metrics = measureFontMetrics(font);
        int columns = Math.max(1, (int) ((pane.width() - 24.0) / metrics.cellWidth));
        int rows = Math.max(1, (int) ((pane.height() - 24.0) / metrics.lineHeight));
        pane.resize(columns, rows, (int) Math.round(metrics.cellWidth), (int) Math.round(metrics.lineHeight));

        double left = pane.x() + 12.0;
        double top = pane.y() + 12.0;
        double baseline = top + metrics.baselineOffset;

        RenderStateSnapshot snapshot = pane.renderSnapshot();
        if (snapshot != null) {
            for (RenderRow row : snapshot.renderRows()) {
                drawRow(gc, row, left, top, baseline, metrics.cellWidth, metrics.lineHeight);
            }
        }

        if (snapshot != null) {
            drawCursor(gc, snapshot, left, top, metrics.cellWidth, metrics.lineHeight);
        }

        if (config.kittyGraphics()) {
            drawKittyGraphics(gc, pane, left, top, metrics.cellWidth, metrics.lineHeight);
        }
        gc.restore();
    }

    private static FontMetrics measureFontMetrics(Font font) {
        Text text = new Text("┃MgÅjy");
        text.setFont(font);
        double lineHeight = Math.max(1.0, text.getLayoutBounds().getHeight());
        double baselineOffset = -text.getLayoutBounds().getMinY();

        String sample = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Text cell = new Text(sample);
        cell.setFont(font);
        double cellWidth = Math.max(1.0, cell.getLayoutBounds().getWidth() / sample.length());
        return new FontMetrics(cellWidth, lineHeight, baselineOffset);
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

        FontMetrics metrics = measureFontMetrics(Font.font(fontFamily, fontSize));
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
            double x = left + (cell.column() * cellWidth);
            double cellTop = top + (row.row() * lineHeight);
            cell.background().ifPresent(background -> {
                gc.setFill(toFxColor(background));
                fillCellRect(gc, x, cellTop, cellWidth, lineHeight);
            });
            if (cell.selected()) {
                gc.setFill(SELECTED_BACKGROUND);
                fillCellRect(gc, x, cellTop, cellWidth, lineHeight);
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

    private static void fillCellRect(GraphicsContext gc, double x, double y, double width, double height) {
        double x1 = Math.floor(x);
        double y1 = Math.floor(y);
        double x2 = Math.ceil(x + width);
        double y2 = Math.ceil(y + height);
        gc.fillRect(x1, y1, Math.max(1.0, x2 - x1), Math.max(1.0, y2 - y1));
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

    private void drawKittyGraphics(GraphicsContext gc, TerminalPane pane, double originX, double originY, double cellWidth, double lineHeight) {
        pane.kittyGraphics().ifPresent(graphics -> {
            for (KittyPlacement placement : graphics.placements()) {
                Image image = imageFor(placement);
                if (image == null) {
                    continue;
                }

                KittyRenderInfo renderInfo = placement.renderInfo().orElse(null);
                double x = originX;
                double y = originY;
                double width = image.getWidth();
                double height = image.getHeight();

                if (renderInfo != null) {
                    x += renderInfo.viewportColumn() * cellWidth;
                    y += renderInfo.viewportRow() * lineHeight;
                    width = renderInfo.gridColumns() > 0 ? renderInfo.gridColumns() * cellWidth : renderInfo.pixelWidth();
                    height = renderInfo.gridRows() > 0 ? renderInfo.gridRows() * lineHeight : renderInfo.pixelHeight();
                } else {
                    width = placement.columns() > 0 ? placement.columns() * cellWidth : width;
                    height = placement.rows() > 0 ? placement.rows() * lineHeight : height;
                }

                gc.drawImage(image, x + placement.xOffset(), y + placement.yOffset(), width, height);
            }
        });
    }

    private Image imageFor(KittyPlacement placement) {
        return placement.image()
                .map(snapshot -> kittyImageCache.computeIfAbsent(snapshot.id(), ignored -> decodeImage(snapshot)))
                .orElse(null);
    }

    private Image decodeImage(KittyImageSnapshot snapshot) {
        if (snapshot.compression() != KittyImageCompression.NONE) {
            return null;
        }

        if (snapshot.format() == KittyImageFormat.PNG) {
            return new Image(new ByteArrayInputStream(snapshot.data()));
        }

        int width = Math.toIntExact(snapshot.width());
        int height = Math.toIntExact(snapshot.height());
        WritableImage image = new WritableImage(width, height);
        byte[] data = snapshot.data();

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
}
