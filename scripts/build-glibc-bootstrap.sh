#!/bin/sh
# build-glibc-bootstrap.sh — assemble glibc-bootstrap-<arch>.tar.xz assets.
#
# Usage:
#   scripts/build-glibc-bootstrap.sh [--arch arm64|x86_64] [--prefix PREFIX]
#       [--out DIR] [--glibc-tree DIR] [--debian-staging DIR]
#       [--bootstrap-version VER]
#
# Inputs (merged in this order):
#   --glibc-tree DIR      unpatched $PREFIX/glibc tree (toolchain Docker /out).
#   --debian-staging DIR  glibc staging tree from scripts/fetch-debian-packages.sh
#                         (--out of that script). Debian userland binaries land here.
#   assets/staging/<arch>/glibc  optional repo-local overlay, also patched.
#
# ELF patching (build time only, never on-device):
#   Every ELF file under the merged staging glibc/{bin,lib} is rewritten with
#   scripts/patch-elf.sh:
#     INTERP: $PREFIX/glibc/lib/ld-linux-aarch64.so.1 (arm64)
#             $PREFIX/glibc/lib/ld-linux-x86-64.so.2  (x86_64)
#     RPATH:  $PREFIX/glibc/lib:$PREFIX/glibc/lib/<triplet>
#
# Phase 4.1 (generated here, prefix- and arch-aware, shipped in the tarball):
#   glibc/etc/dpkg/dpkg.cfg.d/01patchelf  post-invoke hook re-patching
#                                          on-device dpkg installs: INTERP +
#                                          RPATH for executables, RPATH-only
#                                          for .so libs (patchelf resolves
#                                          via grun PATH).
#   glibc/bin/{chown,chgrp,dpkg-statoverride}
#                                          no-op fallbacks so root-only
#                                          maintainer scripts do not fail.
#   glibc/etc/apt/sources.list             Debian bookworm arm64/amd64 mirror
#                                          for on-device apt.
# Phase 5.2 (also generated here):
#   glibc/bin/disable-ppk                  prints Phantom Process Killer
#                                          background-execution guidance plus
#                                          copy-paste ADB commands.
# Sysroot preservation (also generated here):
#   glibc/bin/pkg-backup                   saves explicitly-installed package
#                                          names outside /glibc so the list
#                                          survives sysroot purges.
#
# Outputs (bundled in the APK, extracted on first start by GlibcBootstrapInstaller):
#   app/src/main/assets/glibc-bootstrap-arm64.tar.xz   (also: -x86_64)
#     ./glibc/...            GNU runtime (ld-linux, libc.so.6, gconv, binaries,
#                            .bootstrap_version stamp, debian-manifest.txt)
#     ./bin/grun             Bionic host runner (cross-checked with grun.c)
#
# Versioning: --bootstrap-version (default: content of
# toolchain/glibc-bootstrap.version) is stamped into glibc/.bootstrap_version
# and MUST equal TermuxConstants.TERMUX_GLIBC_BOOTSTRAP_VERSION, which the
# installer compares against $PREFIX/glibc/.bootstrap_version. Bump both for a
# new bootstrap release.
#
# The Bionic bootstrap zips (bootstrap-<arch>.zip in app/src/main/cpp/) stay
# tiny on purpose: only bash/patchelf/grun. Everything else ships here.
set -eu

ARCH="arm64"
PREFIX="/data/data/com.housewife.terminal/files/usr"
OUT="app/src/main/assets"
GLIBC_TREE=""
DEBIAN_STAGING=""
BOOTSTRAP_VERSION=""

while [ $# -gt 0 ]; do
    case "$1" in
        --arch=*) ARCH="${1#--arch=}"; shift ;;
        --arch) ARCH="$2"; shift 2 ;;
        --prefix=*) PREFIX="${1#--prefix=}"; shift ;;
        --prefix) PREFIX="$2"; shift 2 ;;
        --out=*) OUT="${1#--out=}"; shift ;;
        --out) OUT="$2"; shift 2 ;;
        --glibc-tree=*) GLIBC_TREE="${1#--glibc-tree=}"; shift ;;
        --glibc-tree) GLIBC_TREE="$2"; shift 2 ;;
        --debian-staging=*) DEBIAN_STAGING="${1#--debian-staging=}"; shift ;;
        --debian-staging) DEBIAN_STAGING="$2"; shift 2 ;;
        --bootstrap-version=*) BOOTSTRAP_VERSION="${1#--bootstrap-version=}"; shift ;;
        --bootstrap-version) BOOTSTRAP_VERSION="$2"; shift 2 ;;
        --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "build-glibc-bootstrap.sh: unknown arg: $1" >&2; exit 1 ;;
    esac
done

# normalize arch labels: aarch64/arm64 -> arm64 asset; x86_64/amd64 -> x86_64
case "$ARCH" in
    aarch64|arm64) ARCH="arm64"; TRIPLET="aarch64-linux-gnu" ;;
    x86_64|amd64) ARCH="x86_64"; TRIPLET="x86_64-linux-gnu" ;;
    *) echo "build-glibc-bootstrap.sh: unsupported --arch=$ARCH" >&2; exit 1 ;;
esac

if [ -z "$BOOTSTRAP_VERSION" ]; then
    if [ -f "toolchain/glibc-bootstrap.version" ]; then
        BOOTSTRAP_VERSION="$(tr -d ' \t\r\n' < toolchain/glibc-bootstrap.version)"
    else
        BOOTSTRAP_VERSION="1"
    fi
fi
if [ -z "$BOOTSTRAP_VERSION" ]; then
    echo "build-glibc-bootstrap.sh: empty bootstrap version" >&2; exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT INT TERM

echo "staging $ARCH bootstrap $BOOTSTRAP_VERSION in $STAGE"
mkdir -p "$STAGE/glibc" "$STAGE/bin" "$OUT"

if [ -n "$GLIBC_TREE" ]; then
    cp -a "$GLIBC_TREE/." "$STAGE/glibc/"
elif [ -d "assets/staging/$ARCH/glibc" ]; then
    cp -a "assets/staging/$ARCH/glibc/." "$STAGE/glibc/"
else
    echo "note: no --glibc-tree given; packing repo staging area only." >&2
fi

if [ -n "$DEBIAN_STAGING" ]; then
    cp -a "$DEBIAN_STAGING/." "$STAGE/glibc/"
fi

# Repo-local overlay (also patched below).
if [ -d "assets/staging/$ARCH/glibc/bin" ]; then
    cp -a "assets/staging/$ARCH/glibc/bin/." "$STAGE/glibc/bin/" 2>/dev/null || true
fi

# Phase 4.1: Debian APT/DPKG integration, generated with the build --prefix
# and --arch so on-device paths are always correct. Packaged into the
# tarball; the installer extracts them with everything else (bin/ entries
# land executable via the tar-mode rule in GlibcBootstrapInstaller).
mkdir -p "$STAGE/glibc/etc/dpkg/dpkg.cfg.d" "$STAGE/glibc/etc/apt" "$STAGE/glibc/bin"
case "$ARCH" in
    arm64) LD_LINUX="ld-linux-aarch64.so.1"; DEBARCH="arm64" ;;
    x86_64) LD_LINUX="ld-linux-x86-64.so.2"; DEBARCH="amd64" ;;
esac
cat > "$STAGE/glibc/etc/dpkg/dpkg.cfg.d/01patchelf" <<EOF
# Housewife Terminal: re-point INTERP/RPATH of freshly unpacked executables
# and shared libraries at the isolated \$PREFIX/glibc runtime. patchelf ships
# in the Bionic bootstrap and resolves via the grun PATH. Shared libraries
# carry no .interp section, so they get an RPATH-only second pass.
# Generated by scripts/build-glibc-bootstrap.sh — do not edit on-device.
DPKG::Post-Invoke {
    "find $PREFIX/glibc/bin $PREFIX/glibc/lib -type f -perm -111 -exec patchelf --set-interpreter $PREFIX/glibc/lib/$LD_LINUX --set-rpath $PREFIX/glibc/lib:$PREFIX/glibc/lib/$TRIPLET {} + 2>/dev/null || true";
    "find $PREFIX/glibc/bin $PREFIX/glibc/lib -type f -name '*.so*' -exec patchelf --set-rpath $PREFIX/glibc/lib:$PREFIX/glibc/lib/$TRIPLET {} + 2>/dev/null || true";
};
EOF
for util in chown chgrp dpkg-statoverride; do
    printf '#!/bin/sh\n# Housewife Terminal: root-only admin utility unavailable in the app sandbox.\nexit 0\n' > "$STAGE/glibc/bin/$util"
    chmod 0755 "$STAGE/glibc/bin/$util"
done
printf '%s\n' "deb [arch=$DEBARCH] http://deb.debian.org/debian/ bookworm main" > "$STAGE/glibc/etc/apt/sources.list"

# Phase 5.2: Phantom Process Killer helper. Prints background-execution
# guidance and copy-paste ADB commands (ADB runs on a host PC, not on-device).
cat > "$STAGE/glibc/bin/disable-ppk" <<'EOF'
#!/bin/sh
# disable-ppk — guide for heavy multi-threaded background work under
# Android 12+ Phantom Process Killer (PPK) limits.
#
# The app already runs its terminal service in the foreground
# (FOREGROUND_SERVICE_DATA_SYNC) and asks for the battery-optimizations
# exemption, which covers normal use. If the system still kills large
# background trees (signal 9, "[Process completed]" without exiting),
# raise the PPK limits from a host PC over ADB:
cat <<'INSTRUCTIONS'

Housewife Terminal keeps sessions alive with a dataSync foreground service,
a partial wake lock, and the "ignore battery optimizations" exemption
(Settings > Apps > Housewife Terminal > Battery > Unrestricted).

If long builds still die in the background, Android's Phantom Process
Killer is capping child processes (default 32 per app). From a host PC
with ADB (USB debugging or wireless debugging enabled on the phone):

  # 1. Keep device_config changes across reboots during testing
  adb shell device_config set_sync_disabled_for_tests persistent

  # 2. Raise the phantom-process ceiling (INT_MAX = effectively off)
  adb shell device_config put activity_manager_native_boot max_phantom_processes 2147483647

  # 3. Belt and braces: disable phantom monitoring (persists across reboots)
  adb shell settings put global settings_enable_monitor_phantom_procs false

  # Verify:
  adb shell device_config get activity_manager_native_boot max_phantom_processes

Notes:
- Step 2 resets on every reboot; re-run it (or keep a host-side script).
- Some vendors (notably Samsung) add their own killers; also disable
  per-app "battery optimization" and any "auto-optimize / sleep apps"
  vendor feature for Housewife Terminal.
- To revert:
  adb shell device_config delete activity_manager_native_boot max_phantom_processes
  adb shell settings delete global settings_enable_monitor_phantom_procs
INSTRUCTIONS
EOF
chmod 0755 "$STAGE/glibc/bin/disable-ppk"

# Sysroot preservation: snapshot the explicitly-installed package set outside
# /glibc ($PREFIX/user_packages.list survives sysroot purges). The
# HousewifeInstaller restore hook suggests re-installing from it after
# upgrades: xargs -a <list> apt-get install -y
USER_LIST_DIR="${PREFIX%/usr}"
cat > "$STAGE/glibc/bin/pkg-backup" <<EOF
#!/bin/sh
# pkg-backup — save explicitly-installed package names for bootstrap upgrades.
# List lands outside /glibc so sysroot purges never touch it.
set -eu
LIST_DIR="\${PREFIX:-$USER_LIST_DIR}"
LIST_DIR="\${LIST_DIR%/usr}"
LIST="\$LIST_DIR/user_packages.list"
if ! command -v dpkg >/dev/null 2>&1; then
    echo "pkg-backup: dpkg not installed in \$PREFIX/glibc" >&2
    exit 1
fi
mkdir -p "\$LIST_DIR"
dpkg --get-selections | grep -v deinstall | cut -f1 > "\$LIST"
echo "pkg-backup: saved \$(wc -l < "\$LIST" | tr -d ' ') packages to \$LIST"
EOF
chmod 0755 "$STAGE/glibc/bin/pkg-backup"

# Build-time ELF patch: rewrite every ELF under glibc/bin and glibc/lib.
if [ -d "$STAGE/glibc/bin" ] || [ -d "$STAGE/glibc/lib" ]; then
    elf_list="$(mktemp)"
    trap 'rm -rf "$STAGE" "$elf_list"' EXIT INT TERM
    for d in "$STAGE/glibc/bin" "$STAGE/glibc/lib"; do
        [ -d "$d" ] || continue
        find "$d" -type f -print >> "$elf_list"
    done
    if [ -s "$elf_list" ]; then
        PATCHELF="${PATCHELF:-patchelf}" \
            xargs -a "$elf_list" sh scripts/patch-elf.sh --prefix="$PREFIX" --arch="$ARCH" || exit 1
    fi
    rm -f "$elf_list"
    trap 'rm -rf "$STAGE"' EXIT INT TERM
fi

# Host runner: prefer the ndk-built grun, fall back to a cross-compiled copy.
if [ -f "build/grun-$ARCH/grun" ]; then
    cp -a "build/grun-$ARCH/grun" "$STAGE/bin/grun"
    chmod 0700 "$STAGE/bin/grun"
else
    echo "warning: build/grun-$ARCH/grun missing; bootstrap will lack grun until ndk build runs." >&2
fi

# Version stamp + manifest live inside the tarball; the installer compares the
# stamp against TERMUX_GLIBC_BOOTSTRAP_VERSION for idempotent upgrades.
printf '%s\n' "$BOOTSTRAP_VERSION" > "$STAGE/glibc/.bootstrap_version"
if [ ! -f "$STAGE/glibc/debian-manifest.txt" ]; then
    printf '# no Debian payload staged (glibc-tree/overlay only)\n' > "$STAGE/glibc/debian-manifest.txt"
fi

if [ ! -f "$STAGE/glibc/lib/ld-linux-aarch64.so.1" ] && [ ! -f "$STAGE/glibc/lib/ld-linux-x86-64.so.2" ]; then
    echo "warning: no ld-linux loader in staging glibc/lib (Docker glibc tree missing?)" >&2
fi
if [ ! -d "$STAGE/glibc/bin" ] || [ -z "$(ls -A "$STAGE/glibc/bin" 2>/dev/null)" ]; then
    echo "build-glibc-bootstrap.sh: refusing to pack empty glibc/bin" >&2; exit 1
fi

ARCHIVE="$OUT/glibc-bootstrap-$ARCH.tar.xz"
tar -cJf "$ARCHIVE" -C "$STAGE" glibc bin
echo "wrote $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
xz -l "$ARCHIVE" || true
