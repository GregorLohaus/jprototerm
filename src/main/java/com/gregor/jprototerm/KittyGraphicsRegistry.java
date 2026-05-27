package com.gregor.jprototerm;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class KittyGraphicsRegistry {
    private final boolean enabled;
    private final StringBuilder stream = new StringBuilder();
    private final Map<Integer, StringBuilder> chunks = new HashMap<>();
    private final List<Placement> placements = new ArrayList<>();

    public KittyGraphicsRegistry(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized void accept(String text) {
        if (!enabled) {
            return;
        }
        stream.append(text);
        parseBufferedCommands();
    }

    public synchronized void draw(GraphicsContext gc, double originX, double originY, double cellWidth, double lineHeight) {
        if (!enabled) {
            return;
        }

        for (Placement placement : placements) {
            double x = originX + placement.column * cellWidth;
            double y = originY + placement.row * lineHeight;
            double width = placement.columns <= 0 ? placement.image.getWidth() : placement.columns * cellWidth;
            double height = placement.rows <= 0 ? placement.image.getHeight() : placement.rows * lineHeight;
            gc.drawImage(placement.image, x, y, width, height);
        }
    }

    public synchronized void clear() {
        chunks.clear();
        placements.clear();
        stream.setLength(0);
    }

    private void parseBufferedCommands() {
        int start;
        while ((start = stream.indexOf("\u001b_G")) >= 0) {
            int end = commandEnd(start + 3);
            if (end < 0) {
                if (start > 0) {
                    stream.delete(0, start);
                }
                return;
            }

            String command = stream.substring(start + 3, end);
            handleCommand(command);
            stream.delete(0, end + terminatorLength(end));
        }

        if (stream.length() > 16384) {
            stream.delete(0, stream.length() - 4096);
        }
    }

    private int commandEnd(int from) {
        int bell = stream.indexOf("\u0007", from);
        int st = stream.indexOf("\u001b\\", from);
        if (bell < 0) {
            return st;
        }
        if (st < 0) {
            return bell;
        }
        return Math.min(bell, st);
    }

    private int terminatorLength(int end) {
        return stream.charAt(end) == '\u0007' ? 1 : 2;
    }

    private void handleCommand(String command) {
        int separator = command.indexOf(';');
        if (separator < 0) {
            return;
        }

        Map<String, String> control = parseControl(command.substring(0, separator));
        String payload = command.substring(separator + 1).replace("\n", "").replace("\r", "");

        int id = intControl(control, "i", 1);
        boolean more = intControl(control, "m", 0) == 1;
        chunks.computeIfAbsent(id, ignored -> new StringBuilder()).append(payload);
        if (more) {
            return;
        }

        String data = chunks.remove(id).toString();
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (!image.isError()) {
                placements.add(new Placement(
                        image,
                        intControl(control, "x", 0),
                        intControl(control, "y", 0),
                        intControl(control, "c", 0),
                        intControl(control, "r", 0)
                ));
            }
        } catch (IllegalArgumentException ignored) {
            chunks.remove(id);
        }
    }

    private static Map<String, String> parseControl(String text) {
        Map<String, String> result = new HashMap<>();
        for (String part : text.split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                result.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        return result;
    }

    private static int intControl(Map<String, String> control, String key, int fallback) {
        try {
            return Integer.parseInt(control.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record Placement(Image image, int column, int row, int columns, int rows) {
    }
}
