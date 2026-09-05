package com.housewife.terminal.app.chat;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.system.Os;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.housewife.terminal.R;
import com.housewife.terminal.shared.interact.MessageDialogUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Lazy-loaded glibc file tree. Disk is touched only on demand:
 * {@link File#listFiles()} runs when a directory node is expanded, never
 * upfront, so opening the drawer never blocks on filesystem walks and the
 * PTY stream is undisturbed.
 *
 * <p>Contracts:
 * <ul>
 *   <li>Tap a directory: toggle expansion with
 *       {@code notifyItemRangeInserted/Removed} (no full refresh).</li>
 *   <li>Tap a file: {@link FileActions#onFileTapped(File)} (the host appends
 *       the path to the chat input bar).</li>
 *   <li>Long-press anything: flat options — Copy path, Delete, Chmod.</li>
 * </ul>
 */
public final class FileExplorerAdapter extends RecyclerView.Adapter<FileExplorerAdapter.FileNodeHolder> {

    public interface FileActions {
        void onFileTapped(@NonNull File file);

        /** "Edit in Shell" from the markdown preview sheet. */
        void onEditMarkdownInShell(@NonNull File file);
    }

    /** Visible row: file, tree depth (for indentation), expansion state. */
    static final class FileNode {
        final File file;
        final int depth;
        boolean expanded;

        FileNode(@NonNull File file, int depth) {
            this.file = file;
            this.depth = depth;
        }
    }

    private static final Comparator<File> DIRS_FIRST = (a, b) -> {
        boolean aDir = a.isDirectory();
        boolean bDir = b.isDirectory();
        if (aDir != bDir) return aDir ? -1 : 1;
        return a.getName().compareToIgnoreCase(b.getName());
    };

    private final List<FileNode> visible = new ArrayList<>();
    private final FileActions actions;
    private File root;
    private Context context;

    public FileExplorerAdapter(@NonNull File root, @NonNull FileActions actions) {
        this.actions = actions;
        setRoot(root);
    }

    /** Collapse everything and re-list the root (e.g. after deletions). */
    public void setRoot(@NonNull File root) {
        this.root = root;
        visible.clear();
        File[] children = listChildren(root);
        for (File child : children)
            visible.add(new FileNode(child, 0));
        notifyDataSetChanged();
    }

    /** Re-read the current root, collapsing all nodes. */
    public void refresh() {
        setRoot(root);
    }

    /** Markdown sources render in the preview sheet instead of the input bar. */
    static boolean isMarkdownFile(@NonNull File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private static File[] listChildren(@NonNull File dir) {        File[] children = dir.listFiles();
        if (children == null) return new File[0];
        Arrays.sort(children, DIRS_FIRST);
        return children;
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    @NonNull
    @Override
    public FileNodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (context == null) context = parent.getContext();
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_file_node, parent, false);
        return new FileNodeHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileNodeHolder holder, int position) {
        FileNode node = visible.get(position);
        boolean isDir = node.file.isDirectory();
        int indentPx = (int) (node.depth * 16 * holder.itemView.getResources().getDisplayMetrics().density);
        holder.itemView.setPaddingRelative(indentPx,
            holder.itemView.getPaddingTop(),
            holder.itemView.getPaddingEnd(),
            holder.itemView.getPaddingBottom());
        holder.marker.setText(isDir ? (node.expanded ? "▾" : "▸") : "•");
        holder.name.setText(node.file.getName());
        holder.name.setTypeface(isDir ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            FileNode tapped = visible.get(adapterPosition);
            if (tapped.file.isDirectory()) {
                toggleDirectory(adapterPosition);
            } else if (isMarkdownFile(tapped.file)) {
                MarkdownPreviewController.showPreview(context, tapped.file,
                    file -> actions.onEditMarkdownInShell(file));
            } else {
                actions.onFileTapped(tapped.file);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return true;
            showNodeOptions(visible.get(adapterPosition), adapterPosition);
            return true;
        });
    }

    private void toggleDirectory(int position) {
        FileNode node = visible.get(position);
        if (node.expanded) {
            int count = 0;
            while (position + 1 + count < visible.size()
                && visible.get(position + 1 + count).depth > node.depth)
                count++;
            for (int i = 0; i < count; i++)
                visible.remove(position + 1);
            node.expanded = false;
            if (count > 0) notifyItemRangeRemoved(position + 1, count);
            notifyItemChanged(position);
        } else {
            File[] children = listChildren(node.file);
            if (children.length == 0) {
                Toast.makeText(context, "Empty directory", Toast.LENGTH_SHORT).show();
                return;
            }
            for (int i = 0; i < children.length; i++)
                visible.add(position + 1 + i, new FileNode(children[i], node.depth + 1));
            node.expanded = true;
            notifyItemRangeInserted(position + 1, children.length);
            notifyItemChanged(position);
        }
    }

    private void showNodeOptions(@NonNull FileNode node, int position) {
        CharSequence[] options = {"Copy path", "Delete", "Chmod 755", "Chmod 644"};
        new AlertDialog.Builder(context)
            .setTitle(node.file.getName())
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        copyPath(node.file);
                        break;
                    case 1:
                        confirmDelete(node, position);
                        break;
                    case 2:
                        chmod(node.file, 0755);
                        break;
                    default:
                        chmod(node.file, 0644);
                        break;
                }
            })
            .show();
    }

    private void copyPath(@NonNull File file) {
        ClipboardManager clipboard =
            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("path", file.getAbsolutePath()));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(@NonNull FileNode node, int position) {
        MessageDialogUtils.showMessage(context, "Delete " + node.file.getName() + "?",
            node.file.getAbsolutePath(),
            "Delete", (dialog, which) -> deleteNode(node, position),
            "Cancel", null, null);
    }

    private void deleteNode(@NonNull FileNode node, int position) {
        if (!deleteRecursive(node.file)) {
            Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = 0;
        while (position + 1 + count < visible.size()
            && visible.get(position + 1 + count).depth > node.depth)
            count++;
        visible.remove(position);
        notifyItemRemoved(position);
        if (count > 0) notifyItemRangeRemoved(position, count);
        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show();
    }

    private static boolean deleteRecursive(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private void chmod(@NonNull File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
            Toast.makeText(context, "Chmod " + Integer.toOctalString(mode), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "Chmod failed", Toast.LENGTH_SHORT).show();
        }
    }

    static final class FileNodeHolder extends RecyclerView.ViewHolder {
        final TextView marker;
        final TextView name;

        FileNodeHolder(@NonNull View itemView) {
            super(itemView);
            marker = itemView.findViewById(R.id.file_marker);
            name = itemView.findViewById(R.id.file_name);
        }
    }
}
