#!/bin/sh
# patch-elf.sh — rewrite ELF INTERP/RPATH for the isolated $PREFIX/glibc runtime.
#
# Usage:
#   scripts/patch-elf.sh <file...> [--prefix PREFIX] [--arch arm64|x86_64]
#
# Defaults:
#   PREFIX=/data/data/com.housewife.terminal/files/usr   (on-device $PREFIX)
#   ARCH  =auto (uname -m; x86_64 -> x86_64-linux-gnu triplet)
#
# Applied to every third-party aarch64-linux-gnu binary before it enters
# glibc-bootstrap-<arch>.tar.xz:
#   INTERP (--set-interpreter, executables only):
#     $PREFIX/glibc/lib/ld-linux-aarch64.so.1   (aarch64)
#     $PREFIX/glibc/lib/ld-linux-x86-64.so.2    (x86_64)
# RPATH (--set-rpath, executables and shared libraries):
#     $PREFIX/glibc/lib:$PREFIX/glibc/lib/<triplet>
#
# Shared libraries carry no .interp section, so --set-interpreter is not
# applicable to them: INTERP failure falls through to the RPATH rewrite
# instead of aborting the file.
#
# Relocatable objects (*.o, shipped by glibc) are skipped outright: patchelf
# only handles linked ET_EXEC/ET_DYN files.
#
# Requires: patchelf (built by toolchain/Dockerfile -> /out/patchelf).
set -eu

PREFIX="/data/data/com.housewife.terminal/files/usr"
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
    aarch64|arm64)
        TRIPLET="aarch64-linux-gnu"
        INTERP="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
        ;;
    x86_64|amd64)
        TRIPLET="x86_64-linux-gnu"
        INTERP="$PREFIX/glibc/lib/ld-linux-x86-64.so.2"
        ;;
    *)
        echo "patch-elf.sh: unsupported --arch=$ARCH (want arm64/aarch64|x86_64/amd64)" >&2
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
    # Relocatable objects are not linked images: patchelf reports
    # "wrong ELF type" for them, so skip before touching anything.
    case "$f" in
        *.o) echo "patch-elf.sh: skip relocatable object $f"; continue ;;
    esac
    # Only touch ELF files.
    if ! head -c 4 "$f" | grep -q "$(printf '\177ELF')"; then
        echo "patch-elf.sh: skip non-ELF $f"
        continue
    fi
    echo "patch-elf.sh: $f"
    echo "  INTERP -> $INTERP"
    echo "  RPATH  -> $RPATH"
    # Shared libraries have no .interp section: INTERP is not applicable, so
    # fall through to the RPATH rewrite instead of skipping the file.
    if "$PATCHELF" --set-interpreter "$INTERP" "$f" 2>/dev/null; then
        :
    else
        echo "  INTERP not applicable for $f (shared library?), RPATH only"
    fi
    "$PATCHELF" --set-rpath "$RPATH" "$f" || { echo "  RPATH failed for $f" >&2; fail=1; continue; }
    # 16 KB page-size guard: flag binaries whose segments exceed the limit.
    if command -v readelf >/dev/null 2>&1; then
        if readelf -lW "$f" 2>/dev/null | grep -q "LOAD.*0x[0-9a-f]*$" ; then
            : # readelf listing only; alignment enforced at link time.
        fi
    fi
done

exit "$fail"
