package com.housewife.terminal.app;

import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.housewife.terminal.R;
import com.housewife.terminal.app.chat.HousewifeChatController;
import com.housewife.terminal.app.chat.HousewifeDrawerController;
import com.housewife.terminal.app.chat.SessionManager;
import com.housewife.terminal.shared.logger.Logger;
import com.housewife.terminal.shared.shell.ShellUtils;
import com.housewife.terminal.shared.termux.TermuxConstants;
import com.housewife.terminal.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.housewife.terminal.terminal.TerminalSession;
import com.housewife.terminal.terminal.TerminalSessionClient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Launcher activity: flat 2D chat UI over live PTY sessions.
 *
 * <p>Owns its emulator {@link TerminalSession}s directly (no service binding):
 * submit writes to the active session, {@link #onTextChanged} diffs each
 * session's transcript and streams new output into the chat controller, and
 * the drawer switches the observed session. First launch runs the standard
 * {@link TermuxInstaller} bootstrap flow (Bionic prefix, then the glibc
 * sysroot via {@link HousewifeInstaller}) before spawning.
 */
public final class HousewifeChatActivity extends AppCompatActivity {

    private static final String LOG_TAG = "HousewifeChatActivity";
    private static final int TRANSCRIPT_ROWS = 2000;

    private HousewifeChatController chatController;
    private HousewifeDrawerController drawerController;
    private SessionManager sessionManager;
    private final Map<TerminalSession, SessionManager.SessionHandle> handleBySession = new java.util.HashMap<>();
    private boolean bootstrapReady;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal_chat);

        sessionManager = new SessionManager();

        RecyclerView recycler = findViewById(R.id.chat_recycler);
        EditText input = findViewById(R.id.chat_input);
        ImageButton send = findViewById(R.id.chat_send);
        chatController = new HousewifeChatController(recycler, input, send, this::submitCommand);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        Button newSessionButton = findViewById(R.id.btn_new_session);
        RecyclerView sessionList = findViewById(R.id.rv_sessions);
        RecyclerView fileTree = findViewById(R.id.rv_file_tree);
        drawerController = new HousewifeDrawerController(drawer, newSessionButton,
            sessionList, fileTree, input, sessionManager,
            HousewifeDrawerController.resolveFileTreeRoot(),
            new HousewifeDrawerController.DrawerCallbacks() {
                @Override
                public void onNewSessionRequested() {
                    createShellSession();
                    drawerController.closeDrawer();
                }

                @Override
                public void onSessionSelected(int index) {
                    restoreSession(index);
                }
            });

        TermuxInstaller.setupBootstrapIfNeeded(this, this::onBootstrapReady);
    }

    private void onBootstrapReady() {
        bootstrapReady = true;
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!home.isDirectory() && !home.mkdirs())
            Logger.logError(LOG_TAG, "Failed to create home directory: " + home.getAbsolutePath());
        if (sessionManager.size() == 0)
            createShellSession();
    }

    /** Spawn a Bionic bash login session (-bash) on the PTY engine. */
    private void createShellSession() {
        if (!bootstrapReady) return;
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        HashMap<String, String> environment =
            new TermuxShellEnvironment().getEnvironment(this, false);
        List<String> envList = new ArrayList<>(environment.size());
        for (Map.Entry<String, String> entry : environment.entrySet())
            envList.add(entry.getKey() + "=" + entry.getValue());
        try {
            SessionManager.SessionHandle handle = sessionManager.createNewSession(
                bash, home, new String[]{"-bash"},
                envList.toArray(new String[0]), TRANSCRIPT_ROWS, sessionClient);
            handleBySession.put(handle.terminalSession, handle);
            // No TerminalView is attached in the chat UI, so start the
            // emulator + PTY fork explicitly (80x24 fallback size).
            handle.terminalSession.updateSize(80, 24, 0, 0);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create shell session", e);
            chatController.postAssistantMessage("Could not start shell: " + e.getMessage());
        }
    }

    private void restoreSession(int index) {
        SessionManager.SessionHandle handle = null;
        List<SessionManager.SessionHandle> handles = sessionManager.getSessions();
        if (index >= 0 && index < handles.size())
            handle = handles.get(index);
        if (handle == null) return;
        chatController.replaceTerminalOutput(handle.transcript);
    }

    private void submitCommand(@NonNull String command) {
        SessionManager.SessionHandle active = sessionManager.getActive();
        if (active == null) {
            createShellSession();
            active = sessionManager.getActive();
            if (active == null) return;
        }
        try {
            byte[] data = (command + "\n").getBytes(StandardCharsets.UTF_8);
            active.terminalSession.write(data, 0, data.length);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write to session", e);
        }
    }

    /** Pump emulator output into per-session buffers + the visible card. */
    private final TerminalSessionClient sessionClient = new TerminalSessionClient() {
        @Override
        public void onTextChanged(@NonNull TerminalSession changedSession) {
            SessionManager.SessionHandle handle = handleBySession.get(changedSession);
            if (handle == null) return;
            String full = ShellUtils.getTerminalSessionTranscriptText(changedSession, true, false);
            if (full == null) return;
            if (full.length() < handle.transcript.length()) {
                // Scrollback trimmed: reset both buffer and card.
                handle.transcript.setLength(0);
                handle.transcript.append(full);
                if (handle == sessionManager.getActive())
                    chatController.replaceTerminalOutput(handle.transcript);
            } else if (full.length() > handle.transcript.length()) {
                CharSequence delta = full.subSequence(handle.transcript.length(), full.length());
                handle.transcript.append(delta);
                if (handle == sessionManager.getActive())
                    chatController.postTerminalOutput(delta);
            }
        }

        @Override
        public void onTitleChanged(@NonNull TerminalSession changedSession) {
            SessionManager.SessionHandle handle = handleBySession.get(changedSession);
            if (handle == null) return;
            handle.subtitle = changedSession.getTitle();
            drawerController.refreshSessions();
        }

        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            SessionManager.SessionHandle handle = handleBySession.get(finishedSession);
            if (handle == null) return;
            handle.subtitle = handle.subtitle + " (exited)";
            drawerController.refreshSessions();
        }

        @Override
        public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        }

        @Override
        public void onBell(@NonNull TerminalSession session) {
        }

        @Override
        public void onColorsChanged(@NonNull TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
            Logger.logError(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Logger.logWarn(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Logger.logInfo(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Logger.logDebug(tag, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Logger.logVerbose(tag, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Logger.logStackTraceWithMessage(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Logger.logStackTrace(tag, e);
        }
    };

    /** Main-thread factory for external intents. */
    @NonNull
    public static android.content.Intent newInstance(@NonNull Context context) {
        android.content.Intent intent = new android.content.Intent(context, HousewifeChatActivity.class);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    public void onBackPressed() {
        if (drawerController != null && drawerController.isDrawerOpen()) {
            drawerController.closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (SessionManager.SessionHandle handle : sessionManager.getSessions()) {
            try {
                handle.terminalSession.finishIfRunning();
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to finish session: " + e.getMessage());
            }
        }
        handleBySession.clear();
        super.onDestroy();
    }
}
