package com.housewife.terminal.app.chat;

/**
 * A single row in the flat 2D chat stream: a user command, an assistant
 * message, or the live terminal execution card.
 */
public final class ChatMessage {

    /** Right-aligned minimal user command entry. */
    public static final int TYPE_USER = 0;
    /** Left-aligned assistant message bubble. */
    public static final int TYPE_ASSISTANT = 1;
    /** Dedicated flat terminal output card (raw stdout/stderr, never a bubble). */
    public static final int TYPE_TERMINAL = 2;

    public final int type;
    public final String text;

    public ChatMessage(int type, String text) {
        this.type = type;
        this.text = text == null ? "" : text;
    }
}
