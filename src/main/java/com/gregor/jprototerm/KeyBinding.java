package com.gregor.jprototerm;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.Locale;

public record KeyBinding(boolean alt, boolean control, boolean shift, KeyCode code) {
    public static KeyBinding parse(String value) {
        boolean alt = false;
        boolean control = false;
        boolean shift = false;
        KeyCode code = null;

        for (String part : value.split("\\+")) {
            String token = part.trim().toUpperCase(Locale.ROOT);
            switch (token) {
                case "ALT", "META" -> alt = true;
                case "CTRL", "CONTROL" -> control = true;
                case "SHIFT" -> shift = true;
                default -> code = keyCode(token);
            }
        }

        if (code == null) {
            throw new IllegalArgumentException("Key binding has no key code: " + value);
        }
        return new KeyBinding(alt, control, shift, code);
    }

    public boolean matches(KeyEvent event) {
        return event.isAltDown() == alt
                && event.isControlDown() == control
                && event.isShiftDown() == shift
                && event.getCode() == code;
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
