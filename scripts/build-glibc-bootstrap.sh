#!/bin/sh
# build-glibc-bootstrap.sh — assemble glibc-bootstrap-<arch>.tar.xz assets.
#
# Usage:
#   scripts/build-glibc-bootstrap.sh [--arch aarch64|x86_64] [--prefix PREFIX]
#                                    [--out DIR] [--glibc-tree DIR]
#
# Inputs:
#   --glibc-tree DIR   unpatched $PREFIX/glibc tree (from toolchain Docker /out).
#                      Default: build/glibc-<arch>/usr/glibc staged by CI.
#   Third-party aarch64-linux-gnu binaries dropped into
#   assets/staging/<arch>/glibc/bin are auto-patched with patch-elf.sh.
#
# Outputs (consumed by the app on first start, Phase 3 installer):
#   app/src/main/assets/glibc-bootstrap-<arch>.tar.xz
#     ./glibc/...            GNU runtime (ld-linux, libc.so.6, gconv, binaries)
#     ./bin/grun             Bionic host runner (cross-checked with grun.c)
#     ./bin/bash             minimal host shell stub (until glibc bash lands)
#
# The Bionic bootstrap zips (bootstrap-<arch>.zip in app/src/main/cpp/) stay
# tiny on purpose: only bash/patchelf/grun. Everything else ships here.
set -eu

ARCH="aarch64"
PREFIX="/data/data/com.termux/files/usr"
OUT="app/src/main/assets"
GLIBC_TREE=""

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
        --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$ARCH" in
    aarch64|x86_64) ;;
    *) echo "unsupported --arch=$ARCH" >&2; exit 1 ;;
esac

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT INT TERM

echo "staging $ARCH bootstrap in $STAGE"
mkdir -p "$STAGE/glibc" "$STAGE/bin" "$OUT"

if [ -n "$GLIBC_TREE" ]; then
    cp -a "$GLIBC_TREE/." "$STAGE/glibc/"
else
    echo "note: no --glibc-tree given; packing repo staging area only." >&2
    if [ -d "assets/staging/$ARCH/glibc" ]; then
        cp -a "assets/staging/$ARCH/glibc/." "$STAGE/glibc/"
    fi
fi

# Auto-patch third-party binaries staged for inclusion.
if [ -d "assets/staging/$ARCH/glibc/bin" ]; then
    # shellcheck disable=SC2044
    for bin in assets/staging/"$ARCH"/glibc/bin/*; do
        [ -f "$bin" ] || continue
        PATCHELF="${PATCHELF:-patchelf}" \
            sh scripts/patch-elf.sh "$bin" --prefix="$PREFIX" --arch="$ARCH" || exit 1
    done
    cp -a "assets/staging/$ARCH/glibc/bin/." "$STAGE/glibc/bin/" 2>/dev/null || true
fi

# Host runner: prefer the ndk-built grun, fall back to a cross-compiled copy.
if [ -f "build/grun-$ARCH/grun" ]; then
    cp -a "build/grun-$ARCH/grun" "$STAGE/bin/grun"
    chmod 0700 "$STAGE/bin/grun"
else
    echo "warning: build/grun-$ARCH/grun missing; bootstrap will lack grun until ndk build runs." >&2
fi

ARCHIVE="$OUT/glibc-bootstrap-$ARCH.tar.xz"
tar -cJf "$ARCHIVE" -C "$STAGE" glibc bin
echo "wrote $ARCHIVE"
xz -l "$ARCHIVE" || true
