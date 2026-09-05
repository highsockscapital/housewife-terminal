package com.housewife.terminal.app.chat;

import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.housewife.terminal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Low-latency {@link RecyclerView.Adapter} for the flat 2D chat stream.
 *
 * <p>Performance rules for continuous PTY output:
 * <ul>
 *   <li>No {@link String} allocations on the hot path: callers pass a
 *       {@link CharSequence} chunk which is appended straight into a reused
 *       pending buffer under lock.</li>
 *   <li>Flushes are coalesced to the display vsync via {@link Choreographer}
 *       (60&nbsp;fps on 60&nbsp;Hz panels, up to 120&nbsp;fps on 120&nbsp;Hz),
 *       so dense stdout bursts render at most once per frame; the terminal
 *       card rebinds with a non-empty payload so the {@link TextView} (backed
 *       by a shared {@link SpannableStringBuilder}) is never fully rebound.</li>
 *   <li>The shared builder is capped ({@link #MAX_TERMINAL_CHARS}); overflow
 *       trims the head so memory stays bounded on long executions.</li>
 *   <li>Hosts should call {@code recyclerView.setItemAnimator(null)} (done by
 *       {@link HousewifeChatController}) to skip change animations on streams.</li>
 * </ul>
 *
 * <p>Native note: the PTY reader already batches through an in-process byte
 * queue ({@code ByteQueue}) and crosses JNI only for spawn/wait/close, so no
 * extra native ring buffer sits on the hot path — throttling here at vsync is
 * the entire frame budget story.
 */
public final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String PAYLOAD_APPEND = "append";
    /** Cap the live terminal card to ~200k chars. */
    private static final int MAX_TERMINAL_CHARS = 200 * 1024;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final RecyclerView recyclerView;

    /** Live terminal text; bound once, mutated in place, observed by the TextView. */
    private final SpannableStringBuilder terminalText = new SpannableStringBuilder();
    /** Reused cross-thread staging buffer (guarded by its own monitor). */
    private final StringBuilder pending = new StringBuilder(8192);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Hop from any thread onto the main thread to schedule a vsync flush. */
    private final Runnable scheduleRunnable = this::scheduleFrame;
    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> onFrame();

    private Choreographer choreographer;
    private int terminalCardPosition = -1;
    private boolean frameScheduled;
    private boolean autoScroll = true;

    public ChatAdapter(@NonNull RecyclerView recyclerView) {
        this.recyclerView = recyclerView;
        setHasStableIds(false);
    }

    /** Enable/disable auto-scroll to the latest log line (on during executions). */
    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
    }

    public void addUserMessage(@NonNull String text) {
        messages.add(new ChatMessage(ChatMessage.TYPE_USER, text));
        notifyItemInserted(messages.size() - 1);
        scrollToBottom();
    }

    public void addAssistantMessage(@NonNull String text) {
        messages.add(new ChatMessage(ChatMessage.TYPE_ASSISTANT, text));
        notifyItemInserted(messages.size() - 1);
        scrollToBottom();
    }

    /**
     * Ensure the single terminal card exists (appended once, stays last) and
     * return its position.
     */
    public synchronized int ensureTerminalCard() {
        if (terminalCardPosition < 0) {
            messages.add(new ChatMessage(ChatMessage.TYPE_TERMINAL, ""));
            terminalCardPosition = messages.size() - 1;
            notifyItemInserted(terminalCardPosition);
        }
        return terminalCardPosition;
    }

    /**
     * Append a PTY output chunk. Safe to call from any thread; never allocates
     * on the hot path beyond the chunk itself.
     */
    public void appendTerminalText(@NonNull CharSequence chunk) {
        if (chunk.length() == 0) return;
        synchronized (pending) {
            pending.append(chunk);
        }
        // Choreographer must be touched on the main thread only.
        mainHandler.post(scheduleRunnable);
    }

    /** Main thread: request at most one pending vsync flush. */
    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        if (choreographer == null)
            choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(frameCallback);
    }

    /** Vsync tick: drain everything staged since the last frame, then re-arm. */
    private void onFrame() {
        frameScheduled = false;
        flushPending();
        synchronized (pending) {
            if (pending.length() > 0)
                scheduleFrame();
        }
    }

    private void flushPending() {
        final int cardPosition;
        synchronized (pending) {
            if (pending.length() == 0) return;
            if (terminalCardPosition < 0) {
                // Must run on the main thread: posts the insert synchronously here.
                messages.add(new ChatMessage(ChatMessage.TYPE_TERMINAL, ""));
                terminalCardPosition = messages.size() - 1;
                notifyItemInserted(terminalCardPosition);
            }
            terminalText.append(pending);
            pending.setLength(0);
            if (terminalText.length() > MAX_TERMINAL_CHARS)
                terminalText.delete(0, terminalText.length() - MAX_TERMINAL_CHARS);
            cardPosition = terminalCardPosition;
        }
        notifyItemChanged(cardPosition, PAYLOAD_APPEND);
        if (autoScroll) scrollToBottom();
    }

    private void scrollToBottom() {
        if (getItemCount() == 0) return;
        recyclerView.scrollToPosition(getItemCount() - 1);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == ChatMessage.TYPE_USER) {
            return new UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false));
        } else if (viewType == ChatMessage.TYPE_ASSISTANT) {
            return new AssistantViewHolder(inflater.inflate(R.layout.item_chat_assistant, parent, false));
        } else {
            return new TerminalLogViewHolder(inflater.inflate(R.layout.item_terminal_log, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        onBindViewHolder(holder, position, new ArrayList<>());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        ChatMessage message = messages.get(position);
        if (!payloads.isEmpty()) {
            // Append tick: terminal text is already live via the shared builder.
            return;
        }
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).text.setText(message.text);
        } else if (holder instanceof AssistantViewHolder) {
            ((AssistantViewHolder) holder).text.setText(message.text);
        } else if (holder instanceof TerminalLogViewHolder) {
            TerminalLogViewHolder terminalHolder = (TerminalLogViewHolder) holder;
            if (terminalHolder.text.getText() != terminalText)
                terminalHolder.text.setText(terminalText, TextView.BufferType.SPANNABLE);
        }
    }

    static final class UserViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.chat_user_text);
        }
    }

    static final class AssistantViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        AssistantViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.chat_assistant_text);
        }
    }

    static final class TerminalLogViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        TerminalLogViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.chat_terminal_text);
        }
    }
}
