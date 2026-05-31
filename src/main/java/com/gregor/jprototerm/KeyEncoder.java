package com.gregor.jprototerm;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

final class KeyEncoder {
    private KeyEncoder() {
    }

    static String encode(KeyEvent event) {
        if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
            String control = controlSequence(event);
            if (control != null) {
                return control;
            }
        }

        if (event.isAltDown() && !event.isControlDown() && !event.isMetaDown()) {
            String alt = altSequence(event);
            if (alt != null) {
                return alt;
            }
        }

        KeyCode code = event.getCode();
        return switch (code) {
            case ENTER -> "\r";
            case BACK_SPACE -> "\u007f";
            case TAB -> event.isShiftDown() ? "\u001b[Z" : "\t";
            case ESCAPE -> "\u001b";
            case UP -> "\u001b[A";
            case DOWN -> "\u001b[B";
            case RIGHT -> "\u001b[C";
            case LEFT -> "\u001b[D";
            case HOME -> "\u001b[H";
            case END -> "\u001b[F";
            case DELETE -> "\u001b[3~";
            case PAGE_UP -> "\u001b[5~";
            case PAGE_DOWN -> "\u001b[6~";
            case F1 -> "\u001bOP";
            case F2 -> "\u001bOQ";
            case F3 -> "\u001bOR";
            case F4 -> "\u001bOS";
            case F5 -> "\u001b[15~";
            case F6 -> "\u001b[17~";
            case F7 -> "\u001b[18~";
            case F8 -> "\u001b[19~";
            case F9 -> "\u001b[20~";
            case F10 -> "\u001b[21~";
            case F11 -> "\u001b[23~";
            case F12 -> "\u001b[24~";
            default -> null;
        };
    }

    private static String controlSequence(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code.isLetterKey()) {
            return String.valueOf((char) (Character.toUpperCase(code.getName().charAt(0)) - '@'));
        }
        return switch (code) {
            case SPACE -> "\u0000";
            case OPEN_BRACKET -> "\u001b";
            case BACK_SLASH -> "\u001c";
            case CLOSE_BRACKET -> "\u001d";
            case DIGIT6 -> "\u001e";
            case MINUS -> "\u001f";
            default -> null;
        };
    }

    private static String altSequence(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code.isLetterKey() || code.isDigitKey()) {
            return "\u001b" + code.getName().toLowerCase();
        }
        return null;
    }
}
