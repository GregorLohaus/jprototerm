package com.gregor.jprototerm;

import io.github.wasabithumb.jtoml.JToml;
import io.github.wasabithumb.jtoml.document.TomlDocument;
import io.github.wasabithumb.jtoml.except.TomlException;
import io.github.wasabithumb.jtoml.value.TomlValue;
import io.github.wasabithumb.jtoml.value.primitive.TomlPrimitive;
import io.github.wasabithumb.jtoml.value.table.TomlTable;

import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AppConfig(
        int columns,
        int rows,
        long maxScrollback,
        String shell,
        String fontFamily,
        double fontSize,
        double windowWidth,
        double windowHeight,
        boolean kittyGraphics,
        Map<String, KeyBinding> keybindings
) {
    private static final List<String> KEYBINDING_KEYS = List.of(
            "navigate_left",
            "navigate_down",
            "navigate_up",
            "navigate_right",
            "toggle_floating",
            "new_floating",
            "next_floating",
            "close_pane",
            "open_font_selector"
    );

    public static AppConfig load() {
        AppConfig defaults = defaults();
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            writeDefaultConfig(path, defaults);
            return defaults;
        }

        try {
            TomlDocument document = JToml.jToml().read(path);
            return new AppConfig(
                    intValue(document, "terminal.columns", defaults.columns),
                    intValue(document, "terminal.rows", defaults.rows),
                    longValue(document, "terminal.max_scrollback", defaults.maxScrollback),
                    stringValue(document, "terminal.shell", defaults.shell),
                    stringValue(document, "terminal.font_family", defaults.fontFamily),
                    doubleValue(document, "terminal.font_size", defaults.fontSize),
                    doubleValue(document, "window.width", defaults.windowWidth),
                    doubleValue(document, "window.height", defaults.windowHeight),
                    booleanValue(document, "kitty_graphics.enabled", defaults.kittyGraphics),
                    keybindings(document, defaults)
            );
        } catch (TomlException ex) {
            System.err.println("Could not parse " + path + ": " + ex.getMessage());
            return defaults;
        }
    }

    public static AppConfig defaults() {
        return new AppConfig(
                100,
                30,
                100_000,
                defaultShell(),
                "JetBrainsMono Nerd Font",
                15.0,
                1200.0,
                760.0,
                true,
                Map.of(
                        "navigate_left", KeyBinding.parse("ALT+H"),
                        "navigate_down", KeyBinding.parse("ALT+J"),
                        "navigate_up", KeyBinding.parse("ALT+K"),
                        "navigate_right", KeyBinding.parse("ALT+L"),
                        "toggle_floating", KeyBinding.parse("ALT+F"),
                        "new_floating", KeyBinding.parse("ALT+SHIFT+F"),
                        "next_floating", KeyBinding.parse("ALT+F12"),
                        "close_pane", KeyBinding.parse("ALT+X"),
                        "open_font_selector", KeyBinding.parse("ALT+T")
                )
        );
    }

    public AppConfig withFont(String family, double size) {
        return new AppConfig(
                columns,
                rows,
                maxScrollback,
                shell,
                family,
                size,
                windowWidth,
                windowHeight,
                kittyGraphics,
                keybindings
        );
    }

    public void save() {
        save(configPath(), this);
    }

    public static Path configPath() {
        String configHome = System.getenv("XDG_CONFIG_HOME");
        if (configHome != null && !configHome.isBlank()) {
            return Path.of(configHome, "jprototerm", "config.toml");
        }
        return Path.of(System.getProperty("user.home"), ".config", "jprototerm", "config.toml");
    }

    private static String defaultShell() {
        return "/bin/bash";
    }

    private static Map<String, KeyBinding> keybindings(TomlTable table, AppConfig defaults) {
        Map<String, KeyBinding> parsed = new LinkedHashMap<>();
        for (String key : KEYBINDING_KEYS) {
            parsed.put(key, binding(table, "keybindings." + key, defaults.keybindings.get(key)));
        }
        return Map.copyOf(parsed);
    }

    private static void writeDefaultConfig(Path path, AppConfig defaults) {
        save(path, defaults);
    }

    private static void save(Path path, AppConfig config) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    config.toToml(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            System.err.println("Could not write " + path + ": " + ex.getMessage());
        }
    }

    private String toToml() {
        StringBuilder builder = new StringBuilder();
        builder.append("[terminal]\n");
        builder.append("columns = ").append(columns).append('\n');
        builder.append("rows = ").append(rows).append('\n');
        builder.append("max_scrollback = ").append(maxScrollback).append('\n');
        builder.append("shell = ").append(quoted(shell)).append('\n');
        builder.append("font_family = ").append(quoted(fontFamily)).append('\n');
        builder.append("font_size = ").append(trimDouble(fontSize)).append("\n\n");
        builder.append("[window]\n");
        builder.append("width = ").append(trimDouble(windowWidth)).append('\n');
        builder.append("height = ").append(trimDouble(windowHeight)).append("\n\n");
        builder.append("[kitty_graphics]\n");
        builder.append("enabled = ").append(kittyGraphics).append("\n\n");
        builder.append("[keybindings]\n");
        for (String key : KEYBINDING_KEYS) {
            KeyBinding binding = keybindings.get(key);
            if (binding != null) {
                builder.append(key).append(" = ").append(quoted(binding.toString())).append('\n');
            }
        }
        return builder.toString();
    }

    private static String quoted(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    private static String trimDouble(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static KeyBinding binding(TomlTable table, String key, KeyBinding fallback) {
        String value = stringValue(table, key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return KeyBinding.parse(value);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String stringValue(TomlTable table, String key, String fallback) {
        TomlPrimitive primitive = primitive(table, key);
        return primitive == null ? fallback : primitive.asString();
    }

    private static int intValue(TomlTable table, String key, int fallback) {
        TomlPrimitive primitive = primitive(table, key);
        if (primitive == null) {
            return fallback;
        }
        try {
            return primitive.asInteger();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static long longValue(TomlTable table, String key, long fallback) {
        TomlPrimitive primitive = primitive(table, key);
        if (primitive == null) {
            return fallback;
        }
        try {
            return primitive.asInteger();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static double doubleValue(TomlTable table, String key, double fallback) {
        TomlPrimitive primitive = primitive(table, key);
        if (primitive == null) {
            return fallback;
        }
        try {
            return primitive.asDouble();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean booleanValue(TomlTable table, String key, boolean fallback) {
        TomlPrimitive primitive = primitive(table, key);
        if (primitive == null) {
            return fallback;
        }
        try {
            return primitive.asBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static TomlPrimitive primitive(TomlTable table, String key) {
        TomlValue value = table.get(key);
        if (value == null || !value.isPrimitive()) {
            return null;
        }
        return value.asPrimitive();
    }
}
