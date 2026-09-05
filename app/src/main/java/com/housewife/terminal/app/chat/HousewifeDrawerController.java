package com.housewife.terminal.app.chat;

import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.housewife.terminal.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the drawer sidebar (new-session button, session switcher, glibc file
 * tree) to a {@link SessionManager} and the chat input bar.
 *
 * <p>Host integration, next to {@link HousewifeChatController}:
 * <pre>
 * DrawerLayout drawer = findViewById(R.id.drawer_layout);
 * HousewifeDrawerController sidebar = new HousewifeDrawerController(drawer,
 *     findViewById(R.id.btn_new_session), findViewById(R.id.rv_sessions),
 *     findViewById(R.id.rv_file_tree), findViewById(R.id.chat_input),
 *     sessionManager, glibcHome, callbacks);
 * </pre>
 * Everything here runs on the main thread and never touches the PTY stream:
 * session spawning/switching goes through {@link SessionManager}, whose
 * engine threads keep running undisturbed while the drawer opens.
 */
public final class HousewifeDrawerController {

    /** Host actions for drawer intents (spawning stays with the host). */
    public interface DrawerCallbacks {
        void onNewSessionRequested();
        void onSessionSelected(int index);
    }

    private final DrawerLayout drawer;
    private final EditText chatInput;
    private final SessionManager sessions;
    private final DrawerCallbacks callbacks;
    private final SessionAdapter sessionAdapter;
    private final FileExplorerAdapter fileAdapter;

    public HousewifeDrawerController(@NonNull DrawerLayout drawer,
                                     @NonNull Button newSessionButton,
                                     @NonNull RecyclerView sessionList,
                                     @NonNull RecyclerView fileTree,
                                     @NonNull EditText chatInput,
                                     @NonNull SessionManager sessions,
                                     @NonNull File fileTreeRoot,
                                     @NonNull DrawerCallbacks callbacks) {
        this.drawer = drawer;
        this.chatInput = chatInput;
        this.sessions = sessions;
        this.callbacks = callbacks;

        sessionList.setLayoutManager(new LinearLayoutManager(sessionList.getContext()));
        sessionAdapter = new SessionAdapter(position -> {
            sessions.switchSession(position);
            closeDrawer();
            callbacks.onSessionSelected(position);
        });
        sessionList.setAdapter(sessionAdapter);

        fileTree.setLayoutManager(new LinearLayoutManager(fileTree.getContext()));
        fileAdapter = new FileExplorerAdapter(fileTreeRoot, file -> {
            chatInput.append(file.getAbsolutePath() + " ");
            chatInput.requestFocus();
        });
        fileTree.setAdapter(fileAdapter);

        newSessionButton.setOnClickListener(v -> callbacks.onNewSessionRequested());

        sessions.setEvents(new SessionManager.SessionEvents() {
            @Override
            public void onSessionsChanged() {
                refreshSessions();
            }

            @Override
            public void onActiveSessionChanged(int index) {
                refreshSessions();
            }
        });
        refreshSessions();
    }

    /** Rebuild the switcher rows from the manager (names, subtitles, active). */    public void refreshSessions() {
        List<SessionManager.SessionHandle> handles = sessions.getSessions();
        List<SessionAdapter.SessionItem> items = new ArrayList<>(handles.size());
        for (SessionManager.SessionHandle handle : handles)
            items.add(new SessionAdapter.SessionItem(handle.name, handle.subtitle));
        sessionAdapter.setSessions(items, sessions.getActiveIndex());
    }

    /** Re-list the file tree root (e.g. after external changes). */
    public void refreshFiles() {
        fileAdapter.refresh();
    }

    public void openDrawer() {
        drawer.openDrawer(GravityCompat.START);
    }

    public void closeDrawer() {
        drawer.closeDrawer(GravityCompat.START);
    }

    public boolean isDrawerOpen() {
        return drawer.isDrawerOpen(GravityCompat.START);
    }

    /**
     * Resolve the explorer root: {@code $PREFIX/glibc/home} when provisioned,
     * else the glibc prefix itself, else {@code $PREFIX}. Never null; the
     * adapter renders an empty list when nothing exists yet.
     */
    @NonNull
    public static File resolveFileTreeRoot() {
        File home = new File(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH + "/home");
        if (home.isDirectory()) return home;
        File prefix = new File(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH);
        if (prefix.isDirectory()) return prefix;
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }
}
