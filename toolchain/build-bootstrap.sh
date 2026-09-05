#!/bin/sh
# toolchain/build-bootstrap.sh — end-to-end glibc bootstrap pipeline.
#
# Orchestrates the full Docker-based cross-compilation and asset packaging
# flow that produces the APK-embedded GNU userland:
#
#   1. docker build toolchain/      -> cross toolchain image (glibc sources
#      configured --host=<triplet> --prefix=<PREFIX>/glibc, 16 KB LDFLAGS).
#   2. docker run (OUT:/out, grun.c mounted)
#      -> OUT/glibc-<docker-arch>.tar, OUT/patchelf (static), OUT/grun.
#   3. scripts/fetch-debian-packages.sh -> build/glibc-staging/<arch>/glibc.
#   4. scripts/build-glibc-bootstrap.sh (PATCHELF pointed at the
#      Docker-built static patchelf)
#      -> app/src/main/assets/glibc-bootstrap-<arch>.tar.xz.
#
# Usage:
#   toolchain/build-bootstrap.sh [--arch arm64|x86_64] [--prefix PREFIX]
#       [--suite SUITE] [--out-dir DIR] [--image NAME]
#       [--glibc-version VER] [--skip-docker] [--skip-debian]
#
# Defaults:
#   --arch arm64 --prefix /data/data/com.housewife.terminal/files/usr --suite bookworm
#   --out-dir build/toolchain --image housewife-glibc-toolchain
#   --glibc-version 2.40 (must match toolchain/Dockerfile GLIBC_VERSION)
#
# --skip-docker reuses a previous OUT (OUT/glibc-<arch>.tar, OUT/patchelf,
# OUT/grun must exist). --skip-debian reuses build/glibc-staging/<arch>.
# Requires: docker (unless --skip-docker), curl, xz, dpkg-deb, awk.
set -eu

cd "$(dirname "$0")/.."

ARCH="arm64"
PREFIX="/data/data/com.housewife.terminal/files/usr"
SUITE="bookworm"
OUT_DIR="build/toolchain"
IMAGE="housewife-glibc-toolchain"
GLIBC_VERSION="2.40"
SKIP_DOCKER=0
SKIP_DEBIAN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --arch=*) ARCH="${1#--arch=}"; shift ;;
        --arch) ARCH="$2"; shift 2 ;;
        --prefix=*) PREFIX="${1#--prefix=}"; shift ;;
        --prefix) PREFIX="$2"; shift 2 ;;
        --suite=*) SUITE="${1#--suite=}"; shift ;;
        --suite) SUITE="$2"; shift 2 ;;
        --out-dir=*) OUT_DIR="${1#--out-dir=}"; shift ;;
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        --image=*) IMAGE="${1#--image=}"; shift ;;
        --image) IMAGE="$2"; shift 2 ;;
        --glibc-version=*) GLIBC_VERSION="${1#--glibc-version=}"; shift ;;
        --glibc-version) GLIBC_VERSION="$2"; shift 2 ;;
        --skip-docker) SKIP_DOCKER=1; shift ;;
        --skip-debian) SKIP_DEBIAN=1; shift ;;
        --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "build-bootstrap.sh: unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$ARCH" in
    aarch64|arm64)
        ARCH="arm64"; DOCKER_ARCH="aarch64"; TRIPLET="aarch64-linux-gnu"
        STAGING="build/glibc-staging/arm64/glibc"
        CACHE_DIR="build/deb-cache/arm64"
        ;;
    x86_64|amd64)
        ARCH="x86_64"; DOCKER_ARCH="x86_64"; TRIPLET="x86_64-linux-gnu"
        STAGING="build/glibc-staging/x86_64/glibc"
        CACHE_DIR="build/deb-cache/x86_64"
        ;;
    *) echo "build-bootstrap.sh: unsupported --arch=$ARCH" >&2; exit 1 ;;
esac

mkdir -p "$OUT_DIR"

if [ "$SKIP_DOCKER" -eq 0 ]; then
    command -v docker >/dev/null 2>&1 || {
        echo "build-bootstrap.sh: docker not found (or pass --skip-docker)" >&2; exit 1; }
    [ -f "app/src/main/cpp/grun.c" ] || {
        echo "build-bootstrap.sh: app/src/main/cpp/grun.c missing for grun validation build" >&2; exit 1; }
    echo "building Docker toolchain image $IMAGE (glibc $GLIBC_VERSION, $TRIPLET)..."
    docker build -t "$IMAGE" \
        --build-arg "ARCH=$DOCKER_ARCH" \
        --build-arg "PREFIX=$PREFIX" \
        --build-arg "GLIBC_VERSION=$GLIBC_VERSION" \
        toolchain/
    echo "running toolchain container (out: $OUT_DIR)..."
    docker run --rm \
        -v "$PWD/$OUT_DIR:/out" \
        -v "$PWD/app/src/main/cpp/grun.c:/src/app-src-grun.c:ro" \
        "$IMAGE"
else
    for f in "glibc-$DOCKER_ARCH.tar" patchelf grun; do
        [ -f "$OUT_DIR/$f" ] || {
            echo "build-bootstrap.sh: --skip-docker but $OUT_DIR/$f missing" >&2; exit 1; }
    done
    echo "reusing Docker outputs in $OUT_DIR (--skip-docker)"
fi

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

# The Docker glibc tree mirrors the absolute prefix:
# <tmp>/<PREFIX>/glibc (install_root + --prefix).
UNPACK="$(mktemp -d)"
trap 'rm -rf "$UNPACK"' EXIT INT TERM
echo "unpacking Docker glibc tree..."
tar -xf "$OUT_DIR/glibc-$DOCKER_ARCH.tar" -C "$UNPACK"
GLIBC_TREE="$UNPACK$PREFIX/glibc"
[ -d "$GLIBC_TREE" ] || {
    echo "build-bootstrap.sh: unpacked tree has no $PREFIX/glibc" >&2; exit 1; }

echo "packing glibc-bootstrap-$ARCH.tar.xz..."
PATCHELF="$PWD/$OUT_DIR/patchelf" \
    sh scripts/build-glibc-bootstrap.sh --arch "$ARCH" \
        --prefix "$PREFIX" \
        --glibc-tree "$GLIBC_TREE" \
        --debian-staging "$STAGING"

echo "done: app/src/main/assets/glibc-bootstrap-$ARCH.tar.xz"
