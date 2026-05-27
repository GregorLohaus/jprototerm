package com.gregor.jprototerm;

import io.github.wasabithumb.jtoml.JToml;
import io.github.wasabithumb.jtoml.document.TomlDocument;
import io.github.wasabithumb.jtoml.except.TomlException;
import io.github.wasabithumb.jtoml.value.TomlValue;
import io.github.wasabithumb.jtoml.value.primitive.TomlPrimitive;
import io.github.wasabithumb.jtoml.value.table.TomlTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public record AppConfig(
        int columns,
        int rows,
        String shell,
        String fontFamily,
        double fontSize,
        double windowWidth,
        double windowHeight,
        boolean kittyGraphics,
        Map<String, KeyBinding> keybindings
) {
    public static AppConfig load() {
        AppConfig defaults = defaults();
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return defaults;
        }

        try {
            TomlDocument document = JToml.jToml().read(path);
            return new AppConfig(
                    intValue(document, "terminal.columns", defaults.columns),
                    intValue(document, "terminal.rows", defaults.rows),
                    stringValue(document, "terminal.shell", defaults.shell),
                    stringValue(document, "terminal.font_family", defaults.fontFamily),
                    doubleValue(document, "terminal.font_size", defaults.fontSize),
                    doubleValue(document, "window.width", defaults.windowWidth),
                    doubleValue(document, "window.height", defaults.windowHeight),
                    booleanValue(document, "kitty_graphics.enabled", defaults.kittyGraphics),
                    Map.of(
                            "navigate_left", binding(document, "keybindings.navigate_left", defaults.keybindings.get("navigate_left")),
                            "navigate_down", binding(document, "keybindings.navigate_down", defaults.keybindings.get("navigate_down")),
                            "navigate_up", binding(document, "keybindings.navigate_up", defaults.keybindings.get("navigate_up")),
                            "navigate_right", binding(document, "keybindings.navigate_right", defaults.keybindings.get("navigate_right")),
                            "toggle_floating", binding(document, "keybindings.toggle_floating", defaults.keybindings.get("toggle_floating")),
                            "new_floating", binding(document, "keybindings.new_floating", defaults.keybindings.get("new_floating")),
                            "next_floating", binding(document, "keybindings.next_floating", defaults.keybindings.get("next_floating")),
                            "close_pane", binding(document, "keybindings.close_pane", defaults.keybindings.get("close_pane"))
                    )
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
                        "close_pane", KeyBinding.parse("ALT+X")
                )
        );
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
