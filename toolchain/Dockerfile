# glibc cross toolchain (aarch64-linux-gnu, Android 15+/API 35 host target).
# Builds the isolated $PREFIX/glibc runtime + patchelf + grun host copy.
#
# Usage:
#   docker build -t glibc-termux-toolchain toolchain/
#   docker run --rm -v "$PWD/build:/out" glibc-termux-toolchain
#
# Output (consumed by scripts/build-glibc-bootstrap.sh):
#   /out/glibc/                 # $PREFIX/glibc tree (unpatched paths)
#   /out/patchelf               # static patchelf host binary
#   /usr/local/bin/build-grun.sh# grun builder (run at container RUN time with
#                               # app source mounted; see toolchain/build-bootstrap.sh)
#
# grun itself is NOT compiled at image build time: the app source is mounted
# only at container run time. /usr/local/bin/build-grun.sh captures the exact
# Phase 8.5 flags (mirroring app/src/main/cpp/Android.mk) for that step.
#
# Notes:
# - 64-bit only: arm64-v8a (+ x86_64 via --arch=x86_64 build-arg).
# - glibc is configured with --prefix=<PREFIX>/glibc so the loader reports
#   the on-device path; scripts/patch-elf.sh then rewrites INTERP/RPATH for
#   third-party aarch64-linux-gnu binaries pulled from distro mirrors.
# - Android 15+ (API 35) requires 16 KB ELF segment alignment: LDFLAGS below
#   forces max-page-size=16384 for every glibc link step (the host patchelf
#   build is exempt — it runs on the build host, not the device).
# - Android sandbox/DNS: see toolchain/patches/*.patch (getprop DNS fallback,
#   /proc/self/exe prefix detection). Apply before building glibc.

FROM ubuntu:24.04 AS base
ARG DEBIAN_FRONTEND=noninteractive
ARG ARCH=aarch64
ARG PREFIX=/data/data/com.housewife.terminal/files/usr

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    build-essential gawk bison python3 texinfo gettext \
    gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
    gcc-x86-64-linux-gnu g++-x86-64-linux-gnu \
    git autoconf automake libtool pkg-config curl xz-utils patchelf \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# glibc version pinned for reproducible 16 KB page-size builds (2.41 carries
# the newer-GCC fallout fixes that 2.40 lacks on Ubuntu 24.04 toolchains).
ARG GLIBC_VERSION=2.41
RUN curl -fsSLO "https://ftp.gnu.org/gnu/glibc/glibc-${GLIBC_VERSION}.tar.xz" \
    && tar -xf "glibc-${GLIBC_VERSION}.tar.xz"

WORKDIR /build/glibc
# Explicit 16 KB linker flags for every glibc configure probe and link step
# (Android 15+ refuses LOAD segments aligned wider than the kernel page size).
RUN export LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    && case "$ARCH" in \
      aarch64) TARGET="aarch64-linux-gnu" ;; \
      x86_64)  TARGET="x86_64-linux-gnu" ;; \
      *) echo "unsupported ARCH=$ARCH (want aarch64|x86_64)" >&2; exit 1 ;; \
    esac \
    && echo "$TARGET" > /build/target-triplet \
    && /src/glibc-${GLIBC_VERSION}/configure \
        --host="$TARGET" \
        --prefix="${PREFIX}/glibc" \
        --enable-kernel=5.10 \
        --disable-werror \
        --with-headers="/usr/${TARGET}/include" \
        libc_cv_slibdir="${PREFIX}/glibc/lib" \
    && make -j"$(nproc)" \
    && make install_root=/out/glibc install \
    && echo "glibc ${GLIBC_VERSION} for ${TARGET} installed to /out/glibc"

# Static patchelf for the ELF patch step (runs on the build host).
WORKDIR /src/patchelf
RUN git clone --depth 1 https://github.com/NixOS/patchelf.git . \
    && ./bootstrap.sh \
    && ./configure \
    && make -j"$(nproc)" \
    && cp src/patchelf /out/patchelf

# grun builder script for container run time (image build cannot mount the
# app source). The orchestrator mounts app/src/main/cpp/grun.c at
# /src/app-src-grun.c and OUT at /out, then runs this script.
RUN printf '%s\n' '#!/bin/sh' 'set -eu' \
    'TARGET="$(cat /build/target-triplet)"' \
    '"${TARGET}-gcc" -std=c11 -Wall -Wextra -Werror -O3 -flto -fomit-frame-pointer -funroll-loops -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-O1 -Wl,--gc-sections /src/app-src-grun.c -o /out/grun' \
    > /usr/local/bin/build-grun.sh && chmod 0755 /usr/local/bin/build-grun.sh
