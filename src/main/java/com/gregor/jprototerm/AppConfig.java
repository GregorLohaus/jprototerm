package com.gregor.jprototerm;

import io.github.wasabithumb.jtoml.JToml;
import io.github.wasabithumb.jtoml.document.TomlDocument;
import io.github.wasabithumb.jtoml.except.TomlException;
import io.github.wasabithumb.jtoml.key.TomlKey;
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
        String scrollbackEditorCommand,
        Map<String, String> envOverride,
        Map<String, KeyBinding> keybindings
) {
    private static final List<String> KEYBINDING_KEYS = List.of(
            "navigate_left",
            "navigate_down",
            "navigate_up",
            "navigate_right",
            "toggle_floating",
            "new_pane",
            "next_floating",
            "close_pane",
            "open_font_selector",
            "open_scrollback"
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
                    stringValue(document, "scrollback.editor_command", defaults.scrollbackEditorCommand),
                    envOverride(document, defaults.envOverride),
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
                defaultScrollbackEditorCommand(),
                Map.of(),
                Map.of(
                        "navigate_left", KeyBinding.parse("ALT+H"),
                        "navigate_down", KeyBinding.parse("ALT+J"),
                        "navigate_up", KeyBinding.parse("ALT+K"),
                        "navigate_right", KeyBinding.parse("ALT+L"),
                        "toggle_floating", KeyBinding.parse("ALT+F"),
                        "new_pane", KeyBinding.parse("ALT+N"),
                        "next_floating", KeyBinding.parse("ALT+F12"),
                        "close_pane", KeyBinding.parse("ALT+X"),
                        "open_font_selector", KeyBinding.parse("ALT+T"),
                        "open_scrollback", KeyBinding.parse("ALT+S")
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
                scrollbackEditorCommand,
                envOverride,
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

    private static String defaultScrollbackEditorCommand() {
        String editor = System.getenv("EDITOR");
        if (editor == null || editor.isBlank()) {
            editor = "vi";
        }
        return editor.trim() + " {file}";
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
        builder.append("[scrollback]\n");
        builder.append("editor_command = ").append(quoted(scrollbackEditorCommand)).append("\n\n");
        builder.append("[env.override]\n");
        for (Map.Entry<String, String> entry : envOverride.entrySet()) {
            builder.append(entry.getKey()).append(" = ").append(quoted(entry.getValue())).append('\n');
        }
        builder.append('\n');
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

    private static Map<String, String> envOverride(TomlTable table, Map<String, String> fallback) {
        TomlValue value = table.get("env.override");
        if (value == null || !value.isTable()) {
            return fallback;
        }

        Map<String, String> result = new LinkedHashMap<>();
        TomlTable overrides = value.asTable();
        for (TomlKey key : overrides.keys(false)) {
            if (key.size() != 1) {
                continue;
            }

            TomlValue override = overrides.get(key);
            if (override != null && override.isPrimitive()) {
                try {
                    result.put(key.get(0), override.asPrimitive().asString());
                } catch (RuntimeException ignored) {
                    // Ignore non-string values; environment values are strings.
                }
            }
        }
        return Map.copyOf(result);
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
