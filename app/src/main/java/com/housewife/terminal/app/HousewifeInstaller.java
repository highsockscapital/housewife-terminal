package com.housewife.terminal.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.system.Os;

import com.housewife.terminal.shared.errors.Error;
import com.housewife.terminal.shared.file.FileUtils;
import com.housewife.terminal.shared.logger.Logger;
import com.housewife.terminal.shared.notification.NotificationUtils;
import com.housewife.terminal.shared.termux.TermuxBootstrap;
import com.housewife.terminal.shared.termux.TermuxConstants;

import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Install the isolated GNU userland ({@code $PREFIX/glibc} + {@code $PREFIX/bin/grun})
 * from {@code app/src/main/assets/glibc-bootstrap-<arch>.tar.xz} on first start.
 *
 * <p>Tarball layout (see {@code scripts/build-glibc-bootstrap.sh}):
 * {@code ./glibc/...} (ld-linux, libc.so.6, gconv, Debian userland binaries,
 * {@code .bootstrap_version}, {@code debian-manifest.txt}) and {@code ./bin/grun}.
 * Entries are extracted relative to {@code $PREFIX}, so {@code glibc/bin/bash}
 * lands at {@code $PREFIX/glibc/bin/bash}.</p>
 *
 * <p>Idempotent: {@link #isInstallNeeded()} compares
 * {@link TermuxConstants#TERMUX_GLIBC_BOOTSTRAP_VERSION} against
 * {@code $PREFIX/glibc/.bootstrap_version}. No-op for non-glibc variants.</p>
 *
 * <p>Forced clean invalidation: when an install is needed and a stale sysroot
 * exists (missing/invalid stamp or version mismatch), the whole
 * {@code $PREFIX/glibc} tree is deleted and recreated before fresh
 * extraction, so on-device apt/dpkg state can never linger across releases.</p>
 */
final class HousewifeInstaller {

    private static final String LOG_TAG = "HousewifeInstaller";

    /** Version stamp file inside {@code $PREFIX/glibc}, written on every successful setup. */
    static final String BOOTSTRAP_VERSION_FILE_NAME = ".bootstrap_version";

    /** Asset file for the current ABI. Build script names it {@code glibc-bootstrap-arm64.tar.xz}. */
    static String getAssetName() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
            ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        if (abi != null && abi.contains("x86_64"))
            return "glibc-bootstrap-x86_64.tar.xz";
        return "glibc-bootstrap-arm64.tar.xz";
    }

    /** Read the on-device {@code $PREFIX/glibc/.bootstrap_version} stamp, or {@code null}. */
    static String getInstalledVersion() {
        File stamp = new File(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH + "/" + BOOTSTRAP_VERSION_FILE_NAME);
        try {
            if (!stamp.isFile()) return null;
            byte[] buf = new byte[64];
            try (InputStream in = new java.io.FileInputStream(stamp)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read glibc bootstrap stamp: " + e.getMessage());
            return null;
        }
    }

    /** Whether the glibc bootstrap must be (re)installed. */
    static boolean isInstallNeeded() {
        if (!TermuxBootstrap.isAppPackageVariantGlibcAndroid15()) return false;
        String installed = getInstalledVersion();
        if (!TermuxConstants.TERMUX_GLIBC_BOOTSTRAP_VERSION.equals(installed)) return true;
        // Same version but payload missing (wiped glibc dir, grun missing) -> reinstall.
        if (!new File(TermuxConstants.TERMUX_GLIBC_BIN_PREFIX_DIR_PATH + "/bash").isFile()) return true;
        if (!new File(TermuxConstants.TERMUX_GRUN_BIN_PATH).isFile()) return true;
        return FileUtils.directoryFileExists(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH, true)
            && new File(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH).list() != null
            && new File(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH).list().length == 0;
    }

    /**
     * First-launch entry point: ensure {@code $PREFIX/glibc} is installed and
     * ready before any terminal PTY session is spawned. Synchronous and
     * potentially slow on first install — call only from a background thread
     * (see {@code TermuxInstaller}), never from {@code onCreate()}.
     *
     * @return {@code null} when the prefix is ready, otherwise an {@link Error}.
     */
    static Error setupGlibcPrefix(Context context) {
        return installIfNeeded(context);
    }

    /**
     * Extract the tarball asset if needed. Returns {@code null} on success or
     * when no install is needed. Returns an {@link Error} when the asset is
     * missing or extraction fails; callers may downgrade a missing asset to a
     * warning for dev builds without a prebuilt bootstrap.
     */
    static Error installIfNeeded(Context context) {
        if (!isInstallNeeded()) {
            Logger.logInfo(LOG_TAG, "Glibc bootstrap " + TermuxConstants.TERMUX_GLIBC_BOOTSTRAP_VERSION + " already installed.");
            return null;
        }

        String assetName = getAssetName();
        Logger.logInfo(LOG_TAG, "Installing glibc bootstrap " + TermuxConstants.TERMUX_GLIBC_BOOTSTRAP_VERSION + " from asset " + assetName + ".");

        // Forced clean invalidation: a needed install means the on-device
        // sysroot is missing, corrupt, or from an older release (including
        // on-device apt/dpkg state). Purge it entirely before extraction so
        // stale libraries can never shadow the fresh payload.
        String installedVersion = getInstalledVersion();
        File glibcDir = new File(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH);
        if (glibcDir.exists()) {
            Logger.logWarn(LOG_TAG, "Stale or mismatched glibc bootstrap detected (installed: "
                + (installedVersion != null ? installedVersion : "<missing or invalid>")
                + ", expected: " + TermuxConstants.TERMUX_GLIBC_BOOTSTRAP_VERSION
                + "). Purging old sysroot and re-installing...");
            Error purgeError = FileUtils.deleteFile("stale glibc sysroot", glibcDir.getAbsolutePath(), true);
            if (purgeError != null) return purgeError;
        }
        Error mkdirError = FileUtils.createDirectoryFile("glibc sysroot", glibcDir.getAbsolutePath());
        if (mkdirError != null) return mkdirError;

        // Surface the purge in the UI: on-device APT state is wiped with the
        // old sysroot, so the user must know packages will be re-initialized.
        showSysrootUpgradeNotification(context);

        AssetManager assets = context.getAssets();
        try (InputStream assetIn = assets.open(assetName)) {
            Error error = extractTarXz(assetIn, TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            if (error != null) { cancelSysrootUpgradeNotification(context); return error; }
        } catch (Exception e) {
            cancelSysrootUpgradeNotification(context);
            return new Error("Failed to open glibc bootstrap asset \"" + assetName + "\". Build it with scripts/fetch-debian-packages.sh + scripts/build-glibc-bootstrap.sh.", e);
        }

        // Ensure the stamp matches even if an older tarball lacked it.
        try {
            File stamp = new File(TermuxConstants.TERMUX_GLIBC_PREFIX_DIR_PATH + "/" + BOOTSTRAP_VERSION_FILE_NAME);
            if (!stamp.isFile()) {
                Error error = FileUtils.createParentDirectoryFile("glibc bootstrap stamp parent", stamp.getAbsolutePath());
                if (error != null) { cancelSysrootUpgradeNotification(context); return error; }
                try (FileOutputStream out = new FileOutputStream(stamp)) {
                    out.write((TermuxConstants.TERMUX_GLIBC_BOOTSTRAP_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            cancelSysrootUpgradeNotification(context);
            return new Error("Failed to write glibc bootstrap version stamp.", e);
        }

        // Host runner must stay executable (build script packs it 0700).
        try {
            File grun = new File(TermuxConstants.TERMUX_GRUN_BIN_PATH);
            if (grun.isFile()) Os.chmod(grun.getAbsolutePath(), 0700);
        } catch (Exception e) {
            cancelSysrootUpgradeNotification(context);
            return new Error("Failed to chmod grun.", e);
        }

        cancelSysrootUpgradeNotification(context);
        maybeShowPackageRestoreNotification(context);

        Logger.logInfo(LOG_TAG, "Glibc bootstrap installed successfully.");
        return null;
    }

    /**
     * Show an ongoing notification while a stale sysroot is purged and
     * re-installed, so the user knows on-device APT packages are being
     * re-initialized. No-op when notifications cannot be posted (e.g. the
     * runtime permission was denied); the install itself is unaffected.
     */
    static void showSysrootUpgradeNotification(Context context) {
        try {
            NotificationUtils.setupNotificationChannel(context,
                TermuxConstants.HOUSEWIFE_SYSROOT_NOTIFICATION_CHANNEL_ID,
                TermuxConstants.HOUSEWIFE_SYSROOT_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT);
            PendingIntent contentIntent = PendingIntent.getActivity(context,
                TermuxConstants.HOUSEWIFE_SYSROOT_UPGRADE_NOTIFICATION_ID,
                TermuxActivity.newInstance(context), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = NotificationUtils.geNotificationBuilder(context,
                TermuxConstants.HOUSEWIFE_SYSROOT_NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_HIGH,
                "Upgrading core glibc sysroot",
                "User packages will be re-initialized.",
                "Upgrading core glibc sysroot... User packages will be re-initialized.\n\n"
                    + "On-device APT state lives inside $PREFIX/glibc and is wiped with the old "
                    + "sysroot. Preserve it with pkg-backup before upgrading.",
                contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
            if (builder == null) return;
            builder.setOngoing(true);
            NotificationManager manager = NotificationUtils.getNotificationManager(context);
            if (manager != null)
                manager.notify(TermuxConstants.HOUSEWIFE_SYSROOT_UPGRADE_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to show sysroot upgrade notification: " + e.getMessage());
        }
    }

    /** Dismiss the sysroot upgrade notification, if shown. Never fails the install. */
    static void cancelSysrootUpgradeNotification(Context context) {
        try {
            NotificationManager manager = NotificationUtils.getNotificationManager(context);
            if (manager != null)
                manager.cancel(TermuxConstants.HOUSEWIFE_SYSROOT_UPGRADE_NOTIFICATION_ID);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to cancel sysroot upgrade notification: " + e.getMessage());
        }
    }

    /**
     * Post-install hook: if a {@code user_packages.list} backup (see
     * {@code $PREFIX/glibc/bin/pkg-backup}) survived outside the purged
     * sysroot, notify with the one-shot restore command. Tapping opens the
     * terminal so the command can be run immediately.
     */
    static void maybeShowPackageRestoreNotification(Context context) {
        File list = new File(TermuxConstants.HOUSEWIFE_USER_PACKAGES_FILE_PATH);
        if (!list.isFile()) return;
        String restoreCommand = "xargs -a " + TermuxConstants.HOUSEWIFE_USER_PACKAGES_FILE_PATH + " apt-get install -y";
        Logger.logInfo(LOG_TAG, "User package list found, suggest restore: " + restoreCommand);
        try {
            PendingIntent contentIntent = PendingIntent.getActivity(context,
                TermuxConstants.HOUSEWIFE_SYSROOT_RESTORE_NOTIFICATION_ID,
                TermuxActivity.newInstance(context), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = NotificationUtils.geNotificationBuilder(context,
                TermuxConstants.HOUSEWIFE_SYSROOT_NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_HIGH,
                "Sysroot upgraded — restore packages",
                "Tap to open the terminal, then run the restore command.",
                "Sysroot upgraded. Re-install your packages with:\n\n" + restoreCommand,
                contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
            if (builder == null) return;
            NotificationManager manager = NotificationUtils.getNotificationManager(context);
            if (manager != null)
                manager.notify(TermuxConstants.HOUSEWIFE_SYSROOT_RESTORE_NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to show package restore notification: " + e.getMessage());
        }
    }

    /** Stream-extract a {@code tar.xz} whose top-level entries are {@code glibc/...} and {@code bin/...}. */
    static Error extractTarXz(InputStream compressedIn, String prefixPath) {
        byte[] block = new byte[512];
        String gnuLongName = null;
        int zeroBlocks = 0;

        try (XZInputStream xzIn = new XZInputStream(compressedIn)) {
            while (true) {
                if (!readFully(xzIn, block)) break; // truncated stream -> done
                if (isZeroBlock(block)) {
                    if (++zeroBlocks >= 2) break;
                    continue;
                }
                zeroBlocks = 0;

                TarEntry entry = parseHeader(block);
                if (entry == null)
                    return new Error("Malformed tar header in glibc bootstrap.");

                long dataSize = entry.size;
                long dataPad = (512 - (dataSize % 512)) % 512;

                // pax extended header: skip payload, keep going.
                if ("././@PaxHeader".equals(entry.name) || "pax_global_header".equals(entry.name)) {
                    skipFully(xzIn, dataSize + dataPad);
                    continue;
                }
                // GNU longname/longlink: next header uses this name.
                if (entry.typeflag == 'L' || entry.typeflag == 'K') {
                    byte[] nameBytes = new byte[(int) dataSize];
                    if (!readFully(xzIn, nameBytes))
                        return new Error("Truncated GNU longname in glibc bootstrap.");
                    skipFully(xzIn, dataPad);
                    gnuLongName = new String(nameBytes, StandardCharsets.UTF_8).trim().replace("\0", "");
                    continue;
                }

                String name = gnuLongName != null ? gnuLongName : entry.name;
                gnuLongName = null;

                if (name.startsWith("./")) name = name.substring(2);
                if (!name.startsWith("glibc/") && !name.startsWith("bin/") && !name.equals("glibc") && !name.equals("bin")) {
                    // Unknown top level (e.g. stray pax dir): skip payload.
                    skipFully(xzIn, dataSize + dataPad);
                    continue;
                }

                Error error = extractEntry(xzIn, prefixPath, name, entry, dataSize);
                if (error != null) return error;
                skipFully(xzIn, dataPad);
            }
        } catch (Exception e) {
            return new Error("Failed to extract glibc bootstrap tar.xz.", e);
        }
        return null;
    }

    private static Error extractEntry(InputStream in, String prefixPath, String name, TarEntry entry, long dataSize) {
        // Path traversal guard.
        if (name.contains("..") || name.startsWith("/")) {
            Logger.logError(LOG_TAG, "Skipping unsafe tar entry: " + name);
            return skipAndOk(in, dataSize);
        }
        File dest = new File(prefixPath, name);
        try {
            switch (entry.typeflag) {
                case '5': { // directory
                    Error error = FileUtils.createDirectoryFile(dest.getAbsolutePath());
                    if (error != null) return error;
                    try { Os.chmod(dest.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                    return null;
                }
                case '2': { // symlink: payload is empty, target in linkname
                    Error error = FileUtils.createParentDirectoryFile("glibc bootstrap parent", dest.getAbsolutePath());
                    if (error != null) return error;
                    FileUtils.deleteFile("glibc bootstrap symlink destination", dest.getAbsolutePath(), true);
                    Os.symlink(entry.linkname, dest.getAbsolutePath());
                    return null;
                }
                case '1': // hardlink: Debian payload should not contain these; copy target instead
                    Logger.logError(LOG_TAG, "Skipping hardlink tar entry (unsupported): " + name);
                    return skipAndOk(in, dataSize);
                case '0':
                case '\0':
                default: { // regular file
                    Error error = FileUtils.createParentDirectoryFile("glibc bootstrap parent", dest.getAbsolutePath());
                    if (error != null) { skipFully(in, dataSize); return error; }
                    FileUtils.deleteFile("glibc bootstrap file destination", dest.getAbsolutePath(), true);
                    try (FileOutputStream out = new FileOutputStream(dest)) {
                        copyExactly(in, out, dataSize);
                    }
                    boolean executable = (entry.mode & 0111) != 0
                        || name.startsWith("bin/") || name.startsWith("glibc/bin/")
                        || name.contains("ld-linux");
                    try {
                        if (dest.getAbsolutePath().equals(TermuxConstants.TERMUX_GRUN_BIN_PATH))
                            Os.chmod(dest.getAbsolutePath(), 0700);
                        else if (executable)
                            Os.chmod(dest.getAbsolutePath(), 0755);
                        else
                            Os.chmod(dest.getAbsolutePath(), 0644);
                    } catch (Exception e) {
                        return new Error("Failed to chmod \"" + dest.getAbsolutePath() + "\".", e);
                    }
                    return null;
                }
            }
        } catch (Exception e) {
            return new Error("Failed to extract tar entry \"" + name + "\".", e);
        }
    }

    private static Error skipAndOk(InputStream in, long n) {
        try { skipFully(in, n); } catch (Exception e) {
            return new Error("Failed to skip tar entry payload.", e);
        }
        return null;
    }

    private static final class TarEntry {
        String name = "";
        int mode;
        long size;
        char typeflag;
        String linkname = "";
    }

    private static TarEntry parseHeader(byte[] h) {
        TarEntry e = new TarEntry();
        e.name = readString(h, 0, 100);
        String prefix = readString(h, 345, 155);
        if (!prefix.isEmpty()) e.name = prefix + "/" + e.name;
        e.mode = parseOctal(h, 100, 8);
        e.size = parseOctalLong(h, 124, 12);
        e.typeflag = (char) h[156];
        e.linkname = readString(h, 157, 100);
        // Base-256 (binary) size for large files: GNU tar extension.
        if (h[124] == (byte) 0x80) {
            long v = 0;
            for (int i = 125; i < 136; i++) v = (v << 8) + (h[i] & 0xFF);
            e.size = v;
        }
        if (e.size < 0) return null;
        return e;
    }

    private static String readString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static int parseOctal(byte[] b, int off, int len) {
        String s = readString(b, off, len).trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s, 8); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseOctalLong(byte[] b, int off, int len) {
        String s = readString(b, off, len).trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); } catch (NumberFormatException e) { return -1; }
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte v : b) if (v != 0) return false;
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws java.io.IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off != 0; // true only for a short final block; caller treats as done
            off += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws java.io.IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                // skip() may return 0: fall back to single-byte reads.
                if (in.read() < 0) return;
                n--;
            } else {
                n -= skipped;
            }
        }
    }

    private static void copyExactly(InputStream in, FileOutputStream out, long n) throws java.io.IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int want = (int) Math.min(buf.length, n);
            int r = in.read(buf, 0, want);
            if (r < 0) throw new java.io.IOException("Truncated tar entry (expected " + n + " more bytes).");
            out.write(buf, 0, r);
            n -= r;
        }
    }

    /** Test hook: extract from raw bytes (used by unit tests). */
    static Error extractTarXzBytes(byte[] tarXz, String prefixPath) {
        try (InputStream in = new java.io.ByteArrayInputStream(tarXz)) {
            return extractTarXz(in, prefixPath);
        } catch (Exception e) {
            return new Error("Failed to extract glibc bootstrap bytes.", e);
        }
    }

    /** Build a minimal in-memory ustar+XZ blob for tests (not shipped in APK logic). */
    static byte[] buildTestTarXz(String filePath, byte[] fileContent) throws java.io.IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        writeTarEntry(tar, filePath, fileContent, (byte) '0');
        tar.write(new byte[1024]); // end-of-archive
        ByteArrayOutputStream xz = new ByteArrayOutputStream();
        try (org.tukaani.xz.XZOutputStream xzOut = new org.tukaani.xz.XZOutputStream(xz, new org.tukaani.xz.LZMA2Options())) {
            xzOut.write(tar.toByteArray());
        }
        return xz.toByteArray();
    }

    private static void writeTarEntry(ByteArrayOutputStream out, String name, byte[] content, byte type) throws java.io.IOException {
        byte[] h = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, h, 0, Math.min(nameBytes.length, 100));
        System.arraycopy("0000755\0".getBytes(StandardCharsets.US_ASCII), 0, h, 100, 8);
        System.arraycopy("0000000\0".getBytes(StandardCharsets.US_ASCII), 0, h, 108, 8);
        System.arraycopy("0000000\0".getBytes(StandardCharsets.US_ASCII), 0, h, 116, 8);
        String sizeOct = String.format("%011o\0", content.length);
        System.arraycopy(sizeOct.getBytes(StandardCharsets.US_ASCII), 0, h, 124, 12);
        System.arraycopy("0000000\0".getBytes(StandardCharsets.US_ASCII), 0, h, 136, 12);
        h[156] = type;
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, h, 263, 2);
        int sum = 0;
        for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? 32 : (h[i] & 0xFF);
        String chk = String.format("%06o\0 ", sum);
        System.arraycopy(chk.getBytes(StandardCharsets.US_ASCII), 0, h, 148, 8);
        out.write(h);
        out.write(content);
        int pad = (512 - (content.length % 512)) % 512;
        out.write(new byte[pad]);
    }
}
