package com.housewife.terminal.app.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.housewife.terminal.terminal.TerminalSession;
import com.housewife.terminal.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of live PTY sessions for the drawer switcher.
 *
 * <p>Each {@link SessionHandle} owns a real {@link TerminalSession} — which
 * spawns its own {@code TermSessionInputReader} thread and emulator buffer —
 * plus an isolated in-memory transcript the host feeds alongside the chat
 * adapter. The manager never touches the PTY stream itself; switching only
 * swaps which handle the UI observes.
 */
public final class SessionManager {

    /** UI callbacks; invoked on the calling thread (host calls from main). */
    public interface SessionEvents {
        void onSessionsChanged();
        void onActiveSessionChanged(int index);
    }

    /** One live session: engine handle, display metadata, isolated transcript. */
    public static final class SessionHandle {
        public final String name;
        public String subtitle;
        public final TerminalSession terminalSession;
        public final StringBuilder transcript = new StringBuilder(65536);

        SessionHandle(@NonNull String name, @NonNull String subtitle,
                      @NonNull TerminalSession terminalSession) {
            this.name = name;
            this.subtitle = subtitle;
            this.terminalSession = terminalSession;
        }
    }

    private final List<SessionHandle> sessions = new ArrayList<>();
    private SessionEvents events;
    private int activeIndex = -1;

    public void setEvents(@Nullable SessionEvents events) {
        this.events = events;
    }

    /** Live handles (unmodifiable snapshot). */
    @NonNull
    public List<SessionHandle> getSessions() {
        return Collections.unmodifiableList(new ArrayList<>(sessions));
    }

    public int size() {
        return sessions.size();
    }

    /** Zero-based index of the observed session, or -1 when empty. */
    public int getActiveIndex() {
        return activeIndex;
    }

    @Nullable
    public SessionHandle getActive() {
        return activeIndex >= 0 && activeIndex < sessions.size()
            ? sessions.get(activeIndex) : null;
    }

    /**
     * Spawn a new PTY session through the terminal engine and select it.
     * The engine constructor forks the shell, attaches its reader thread and
     * emulator buffer; the host feeds output into both the chat adapter and
     * {@link SessionHandle#transcript} via its {@link TerminalSessionClient}.
     */
    @NonNull
    public SessionHandle createNewSession(@NonNull String shellPath, @NonNull String cwd,
                                          @NonNull String[] args, @NonNull String[] env,
                                          int transcriptRows, @NonNull TerminalSessionClient client) {
        TerminalSession session =
            new TerminalSession(shellPath, cwd, args, env, transcriptRows, client);
        SessionHandle handle =
            new SessionHandle("Session " + (sessions.size() + 1), cwd, session);
        sessions.add(handle);
        activeIndex = sessions.size() - 1;
        if (events != null) {
            events.onSessionsChanged();
            events.onActiveSessionChanged(activeIndex);
        }
        return handle;
    }

    /**
     * Observe another session. The host restores its transcript into the chat
     * adapter (see {@code ChatAdapter.replaceTerminalText}) and requests a
     * vsync flush so the swap paints on the next frame.
     */
    public void switchSession(int index) {
        if (index < 0 || index >= sessions.size() || index == activeIndex) return;
        activeIndex = index;
        if (events != null) events.onActiveSessionChanged(index);
    }

    /** Stop the PTY process and drop the handle, keeping selection valid. */
    public void closeSession(int index) {
        if (index < 0 || index >= sessions.size()) return;
        sessions.get(index).terminalSession.finishIfRunning();
        sessions.remove(index);
        if (sessions.isEmpty()) {
            activeIndex = -1;
        } else if (activeIndex >= sessions.size()) {
            activeIndex = sessions.size() - 1;
        }
        if (events != null) {
            events.onSessionsChanged();
            events.onActiveSessionChanged(activeIndex);
        }
    }
}
