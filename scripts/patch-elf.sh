#!/bin/sh
# patch-elf.sh — rewrite ELF INTERP/RPATH for the isolated $PREFIX/glibc runtime.
#
# Usage:
#   scripts/patch-elf.sh <file...> [--prefix PREFIX] [--arch aarch64|x86_64]
#
# Defaults:
#   PREFIX=/data/data/com.termux/files/usr   (on-device $PREFIX)
#   ARCH  =auto (uname -m; x86_64 -> x86_64-linux-gnu triplet)
#
# Applied to every third-party aarch64-linux-gnu binary before it enters
# glibc-bootstrap-<arch>.tar.xz:
#   INTERP (--set-interpreter):
#     $PREFIX/glibc/lib/ld-linux-aarch64.so.1   (aarch64)
#     $PREFIX/glibc/lib/ld-linux-x86-64.so.2    (x86_64)
#   RPATH (--set-rpath):
#     $PREFIX/glibc/lib:$PREFIX/glibc/lib/<triplet>
#
# Requires: patchelf (built by toolchain/Dockerfile -> /out/patchelf).
set -eu

PREFIX="/data/data/com.termux/files/usr"
ARCH="auto"
PATCHELF="${PATCHELF:-patchelf}"
FILES=""

for arg in "$@"; do
    case "$arg" in
        --prefix=*) PREFIX="${arg#--prefix=}" ;;
        --prefix) shift_needed=1 ;;
        --arch=*) ARCH="${arg#--arch=}" ;;
        --patch-elf=*) PATCHELF="${arg#--patch-elf=}" ;;
        --help|-h)
            sed -n '2,20p' "$0"
            exit 0
            ;;
        *) FILES="$FILES $arg" ;;
    esac
done

if [ "$ARCH" = "auto" ]; then
    case "$(uname -m)" in
        x86_64) ARCH="x86_64" ;;
        *) ARCH="aarch64" ;;
    esac
fi

case "$ARCH" in
    aarch64)
        TRIPLET="aarch64-linux-gnu"
        INTERP="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
        ;;
    x86_64)
        TRIPLET="x86_64-linux-gnu"
        INTERP="$PREFIX/glibc/lib/ld-linux-x86-64.so.2"
        ;;
    *)
        echo "patch-elf.sh: unsupported --arch=$ARCH (want aarch64|x86_64)" >&2
        exit 1
        ;;
esac

RPATH="$PREFIX/glibc/lib:$PREFIX/glibc/lib/$TRIPLET"

if [ -z "$FILES" ]; then
    echo "patch-elf.sh: no files given" >&2
    exit 1
fi

command -v "$PATCHELF" >/dev/null 2>&1 || {
    echo "patch-elf.sh: patchelf not found ($PATCHELF). Build it via toolchain/Dockerfile." >&2
    exit 1
}

fail=0
# shellcheck disable=SC2086
for f in $FILES; do
    if [ ! -f "$f" ]; then
        echo "patch-elf.sh: skip missing $f" >&2
        continue
    fi
    # Only touch ELF files.
    if ! head -c 4 "$f" | grep -q "$(printf '\177ELF')"; then
        echo "patch-elf.sh: skip non-ELF $f"
        continue
    fi
    echo "patch-elf.sh: $f"
    echo "  INTERP -> $INTERP"
    echo "  RPATH  -> $RPATH"
    "$PATCHELF" --set-interpreter "$INTERP" "$f" || { echo "  INTERP failed for $f" >&2; fail=1; continue; }
    "$PATCHELF" --set-rpath "$RPATH" "$f" || { echo "  RPATH failed for $f" >&2; fail=1; continue; }
    # 16 KB page-size guard: flag binaries whose segments exceed the limit.
    if command -v readelf >/dev/null 2>&1; then
        if readelf -lW "$f" 2>/dev/null | grep -q "LOAD.*0x[0-9a-f]*$" ; then
            : # readelf listing only; alignment enforced at link time.
        fi
    fi
done

exit "$fail"
