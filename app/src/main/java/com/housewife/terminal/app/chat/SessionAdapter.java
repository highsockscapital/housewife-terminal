package com.housewife.terminal.app.chat;

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
 * Fixed vertical list of active shell PTY sessions. The active row is
 * highlighted with {@code bg_session_item_active}; taps switch sessions via
 * {@link OnSessionClickListener} without touching the PTY stream.
 */
public final class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {

    /** Row model: display name plus a subtitle (cwd, pid, …). */
    public static final class SessionItem {
        public final String name;
        public final String subtitle;

        public SessionItem(@NonNull String name, @NonNull String subtitle) {
            this.name = name;
            this.subtitle = subtitle;
        }
    }

    public interface OnSessionClickListener {
        void onSessionClick(int position);
    }

    private final List<SessionItem> items = new ArrayList<>();
    private final OnSessionClickListener clickListener;
    private int activePosition = -1;

    public SessionAdapter(@NonNull OnSessionClickListener clickListener) {
        this.clickListener = clickListener;
    }

    /** Replace the whole list (session counts are tiny; full refresh is fine). */
    public void setSessions(@NonNull List<SessionItem> sessions, int activePosition) {
        items.clear();
        items.addAll(sessions);
        this.activePosition = activePosition;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        SessionItem item = items.get(position);
        holder.name.setText(item.name);
        holder.subtitle.setText(item.subtitle);
        holder.row.setBackgroundResource(position == activePosition
            ? R.drawable.bg_session_item_active
            : R.drawable.bg_session_item);
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION)
                clickListener.onSessionClick(adapterPosition);
        });
    }

    static final class SessionViewHolder extends RecyclerView.ViewHolder {
        final View row;
        final TextView name;
        final TextView subtitle;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            row = itemView.findViewById(R.id.session_row);
            name = itemView.findViewById(R.id.session_name);
            subtitle = itemView.findViewById(R.id.session_subtitle);
        }
    }
}
