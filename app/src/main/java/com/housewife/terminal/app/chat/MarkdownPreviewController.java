package com.housewife.terminal.app.chat;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.housewife.terminal.R;
import com.housewife.terminal.shared.markdown.MarkdownUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Flat 2D bottom-sheet preview for {@code .md}/{@code .markdown} files tapped
 * in the file explorer.
 *
 * <p>Rendering reuses the shared Markwon pipeline
 * ({@link MarkdownUtils#getSpannedMarkdownText}), which covers headers, bold,
 * lists, inline code and fenced code blocks — no ad-hoc regex renderer.
 * Files are capped at {@link #MAX_PREVIEW_CHARS} so a huge log can never
 * wedge the sheet.
 */
public final class MarkdownPreviewController {

    /** Never render more than ~128k chars in the sheet. */
    static final int MAX_PREVIEW_CHARS = 128 * 1024;

    /** Host hook for the "Edit in Shell" quick action. */
    public interface EditAction {
        void onEditInShell(@NonNull File file);
    }

    private MarkdownPreviewController() {
    }

    /** Preview without an edit handler (the "Edit in Shell" button is hidden). */
    public static void showPreview(@NonNull Context context, @NonNull File file) {
        showPreview(context, file, null);
    }

    /**
     * Render {@code file} in the preview sheet. The edit button inserts
     * {@code nano <path>} wherever the host decides (usually the chat bar).
     */
    public static void showPreview(@NonNull Context context, @NonNull File file,
                                   @Nullable EditAction editAction) {
        String markdown = readCapped(file);
        if (markdown == null) {
            Toast.makeText(context, "Cannot read " + file.getName(), Toast.LENGTH_SHORT).show();
            return;
        }
        Spanned rendered = MarkdownUtils.getSpannedMarkdownText(context, markdown);
        if (rendered == null) rendered = new SpannableStringBuilder("");

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View sheet = LayoutInflater.from(context)
            .inflate(R.layout.dialog_markdown_preview, null);
        dialog.setContentView(sheet);

        View bottomSheet = dialog.findViewById(
            com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            bottomSheet.setBackgroundResource(R.drawable.bg_markdown_sheet);
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }

        TextView title = sheet.findViewById(R.id.markdown_title);
        title.setText(file.getName());

        TextView content = sheet.findViewById(R.id.markdown_content);
        content.setText(rendered);

        ImageButton close = sheet.findViewById(R.id.markdown_close);
        close.setOnClickListener(v -> dialog.dismiss());

        Button edit = sheet.findViewById(R.id.markdown_edit);
        if (editAction == null) {
            edit.setVisibility(View.GONE);
        } else {
            edit.setOnClickListener(v -> {
                dialog.dismiss();
                editAction.onEditInShell(file);
            });
        }

        dialog.show();
    }

    @Nullable
    private static String readCapped(@NonNull File file) {
        StringBuilder out = new StringBuilder((int) Math.min(file.length(), MAX_PREVIEW_CHARS));
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            char[] buf = new char[8192];
            int remaining = MAX_PREVIEW_CHARS;
            int n;
            while (remaining > 0 && (n = reader.read(buf, 0, Math.min(buf.length, remaining))) > 0) {
                out.append(buf, 0, n);
                remaining -= n;
            }
            if (remaining == 0 && reader.read() != -1)
                out.append("\n\n…(truncated for preview)");
            return out.toString();
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }
}
