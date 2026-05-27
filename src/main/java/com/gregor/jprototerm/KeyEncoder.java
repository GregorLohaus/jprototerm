package com.gregor.jprototerm;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

final class KeyEncoder {
    private KeyEncoder() {
    }

    static String encode(KeyEvent event) {
        KeyCode code = event.getCode();
        return switch (code) {
            case ENTER -> "\r";
            case BACK_SPACE -> "\u007f";
            case TAB -> "\t";
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
            default -> null;
        };
    }
}
