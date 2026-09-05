package com.housewife.terminal.app.chat;

import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Host-side wiring for the flat 2D chat UI ({@code activity_terminal_chat}).
 *
 * <p>Execution pipeline:
 * <ol>
 *   <li>Submit from the {@code #FFE8CF} input bar fires
 *       {@link OnCommandSubmitListener#onCommandSubmitted(String)} — the host
 *       writes the text to the glibc {@code grun}/PTY session there.</li>
 *   <li>The host streams captured PTY output back via
 *       {@link #postTerminalOutput(CharSequence)} (any thread); chunks are
 *       throttled to the display frame rate inside {@link ChatAdapter}.</li>
 *   <li>The terminal card auto-scrolls to the latest line while execution is
 *       active (see {@link ChatAdapter#setAutoScroll(boolean)}).</li>
 * </ol>
 *
 * <p>Minimal host integration (e.g. in {@code onCreate} after
 * {@code setContentView(R.layout.activity_terminal_chat)}):
 * <pre>
 * RecyclerView recycler = findViewById(R.id.chat_recycler);
 * EditText input = findViewById(R.id.chat_input);
 * ImageButton send = findViewById(R.id.chat_send);
 * HousewifeChatController chat = new HousewifeChatController(recycler, input, send,
 *     command -&gt; myPtySession.write(command + "\n"));
 * // PTY output callback: chat.postTerminalOutput(newChunk);
 * </pre>
 */
public final class HousewifeChatController {

    /** Fired on the main thread when the user submits a command. */
    public interface OnCommandSubmitListener {
        void onCommandSubmitted(@NonNull String command);
    }

    private final ChatAdapter adapter;
    private final EditText input;
    private final OnCommandSubmitListener submitListener;

    public HousewifeChatController(@NonNull RecyclerView recyclerView,
                                   @NonNull EditText input,
                                   @NonNull ImageButton sendButton,
                                   @NonNull OnCommandSubmitListener submitListener) {
        this(recyclerView, input, sendButton, null, submitListener);
    }

    public HousewifeChatController(@NonNull RecyclerView recyclerView,
                                   @NonNull EditText input,
                                   @NonNull ImageButton sendButton,
                                   @Nullable TextView statusChip,
                                   @NonNull OnCommandSubmitListener submitListener) {
        this.input = input;
        this.submitListener = submitListener;

        LinearLayoutManager layoutManager = new LinearLayoutManager(recyclerView.getContext());
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        // No change animations: streaming appends must not flicker or relayout.
        recyclerView.setItemAnimator(null);

        adapter = new ChatAdapter(recyclerView);
        recyclerView.setAdapter(adapter);

        sendButton.setOnClickListener(v -> submitCurrentInput());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCurrentInput();
                return true;
            }
            return false;
        });

        if (statusChip != null) setGlibcActive(statusChip, true);
    }

    /** Mark the header chip (e.g. "glibc active" vs "starting…"). */
    public void setGlibcActive(@NonNull TextView statusChip, boolean active) {
        statusChip.setText(active ? "glibc active" : "starting…");
    }

    /** Post an assistant bubble. Main thread only (or post via a Handler). */
    public void postAssistantMessage(@NonNull String text) {
        adapter.addAssistantMessage(text);
    }

    /**
     * Stream PTY output into the terminal card. Safe from any thread; enables
     * auto-scroll for the active execution.
     */
    public void postTerminalOutput(@NonNull CharSequence chunk) {
        adapter.setAutoScroll(true);
        adapter.appendTerminalText(chunk);
    }

    /** Pause auto-scroll (e.g. user scrolled up to inspect history). */
    public void setAutoScroll(boolean autoScroll) {
        adapter.setAutoScroll(autoScroll);
    }

    private void submitCurrentInput() {
        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;
        adapter.addUserMessage(command);
        adapter.ensureTerminalCard();
        adapter.setAutoScroll(true);
        input.setText("");
        submitListener.onCommandSubmitted(command);
    }
}
