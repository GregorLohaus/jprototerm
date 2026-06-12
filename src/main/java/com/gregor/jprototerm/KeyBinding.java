package com.gregor.jprototerm;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.Locale;

public record KeyBinding(boolean alt, boolean control, boolean shift, boolean meta, KeyCode code) {
    public static KeyBinding parse(String value) {
        boolean alt = false;
        boolean control = false;
        boolean shift = false;
        boolean meta = false;
        KeyCode code = null;

        for (String part : value.split("\\+")) {
            String token = part.trim().toUpperCase(Locale.ROOT);
            switch (token) {
                case "ALT" -> alt = true;
                case "META", "SUPER" -> meta = true;
                case "CTRL", "CONTROL" -> control = true;
                case "SHIFT" -> shift = true;
                default -> code = keyCode(token);
            }
        }

        if (code == null) {
            throw new IllegalArgumentException("Key binding has no key code: " + value);
        }
        return new KeyBinding(alt, control, shift, meta, code);
    }

    public boolean matches(KeyEvent event) {
        return event.isAltDown() == alt
                && event.isControlDown() == control
                && event.isShiftDown() == shift
                && event.isMetaDown() == meta
                && event.getCode() == code;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (control) {
            builder.append("CTRL+");
        }
        if (alt) {
            builder.append("ALT+");
        }
        if (meta) {
            builder.append("META+");
        }
        if (shift) {
            builder.append("SHIFT+");
        }
        builder.append(code.getName().toUpperCase(Locale.ROOT).replace(' ', '_'));
        return builder.toString();
    }

    private static KeyCode keyCode(String token) {
        KeyCode alias = switch (token) {
            case "GRAVE", "BACKTICK", "BACK_QUOTE", "`" -> KeyCode.BACK_QUOTE;
            default -> null;
        };
        if (alias != null) {
            return alias;
        }
        try {
            return KeyCode.valueOf(token);
        } catch (IllegalArgumentException ex) {
            return KeyCode.getKeyCode(token);
        }
    }
}
