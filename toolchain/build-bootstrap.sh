#!/bin/sh
# toolchain/build-bootstrap.sh — end-to-end glibc bootstrap pipeline.
#
# Assembles the APK-embedded GNU userland from the Debian archive (stock
# Debian ld-linux/libc: proven working on Android, unlike a from-source
# cross build whose loader segfaulted instantly on-device):
#
#   1. scripts/fetch-debian-packages.sh -> build/glibc-staging/<arch>/glibc
#      (full userland incl. libc6/libcrypt1/libgcc-s1, apt, dpkg; the
#      dynamic loader is also placed at <staging>/lib/ld-linux-*.so.1).
#   2. scripts/build-glibc-bootstrap.sh (PATCHELF from the environment)
#      -> app/src/main/assets/glibc-bootstrap-<arch>.tar.xz (ELF INTERP/RPATH
#      rewrites, dpkg hook, dummy admin utils, apt sources, pkg-backup,
#      disable-ppk).
#
# The Bionic host runner ($PREFIX/bin/grun) is NOT built here: CI compiles it
# with the NDK clang into build/grun-<arch>/grun (see glibc-bootstrap.yml),
# which the pack step picks up automatically. Local runs without it pack a
# grun-less tarball (installer falls back to Bionic bash).
#
# Usage:
#   toolchain/build-bootstrap.sh [--arch arm64|x86_64] [--prefix PREFIX]
#       [--suite SUITE] [--skip-debian]
#
# Defaults:
#   --arch arm64 --prefix /data/data/com.housewife.terminal/files/usr --suite bookworm
#
# --skip-debian reuses build/glibc-staging/<arch>.
# Requires: curl, xz, dpkg-deb, awk, patchelf (for the pack step).
set -eu

cd "$(dirname "$0")/.."

ARCH="arm64"
PREFIX="/data/data/com.housewife.terminal/files/usr"
SUITE="bookworm"
SKIP_DEBIAN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --arch=*) ARCH="${1#--arch=}"; shift ;;
        --arch) ARCH="$2"; shift 2 ;;
        --prefix=*) PREFIX="${1#--prefix=}"; shift ;;
        --prefix) PREFIX="$2"; shift 2 ;;
        --suite=*) SUITE="${1#--suite=}"; shift ;;
        --suite) SUITE="$2"; shift 2 ;;
        --skip-debian) SKIP_DEBIAN=1; shift ;;
        --help|-h) sed -n '2,28p' "$0"; exit 0 ;;
        *) echo "build-bootstrap.sh: unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$ARCH" in
    aarch64|arm64)
        ARCH="arm64"; TRIPLET="aarch64-linux-gnu"
        STAGING="build/glibc-staging/arm64/glibc"
        CACHE_DIR="build/deb-cache/arm64"
        ;;
    x86_64|amd64)
        ARCH="x86_64"; TRIPLET="x86_64-linux-gnu"
        STAGING="build/glibc-staging/x86_64/glibc"
        CACHE_DIR="build/deb-cache/x86_64"
        ;;
    *) echo "build-bootstrap.sh: unsupported --arch=$ARCH" >&2; exit 1 ;;
esac

for tool in curl xz dpkg-deb awk; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "build-bootstrap.sh: required tool missing: $tool" >&2; exit 1; }
done
command -v patchelf >/dev/null 2>&1 || {
    echo "build-bootstrap.sh: patchelf not found (apt install patchelf)" >&2; exit 1; }

if [ "$SKIP_DEBIAN" -eq 0 ]; then
    echo "fetching Debian userland (suite=$SUITE, $TRIPLET)..."
    sh scripts/fetch-debian-packages.sh --arch "$ARCH" \
        --suite "$SUITE" --triplet "$TRIPLET" \
        --out "$STAGING" --cache-dir "$CACHE_DIR"
else
    [ -d "$STAGING" ] || {
        echo "build-bootstrap.sh: --skip-debian but $STAGING missing" >&2; exit 1; }
    echo "reusing Debian staging in $STAGING (--skip-debian)"
fi

echo "packing glibc-bootstrap-$ARCH.tar.xz..."
sh scripts/build-glibc-bootstrap.sh --arch "$ARCH" \
    --prefix "$PREFIX" \
    --debian-staging "$STAGING"

echo "done: app/src/main/assets/glibc-bootstrap-$ARCH.tar.xz"
