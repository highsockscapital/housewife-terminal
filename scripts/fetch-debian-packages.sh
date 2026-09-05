#!/bin/sh
# fetch-debian-packages.sh — ingest Debian userland into a $PREFIX/glibc staging tree.
#
# Hybrid Debian ingestion with build-time patching: .debs are fetched from the
# Debian archive, extracted, and relocated under a glibc staging directory.
# ELF INTERP/RPATH rewriting happens later via scripts/patch-elf.sh during
# scripts/build-glibc-bootstrap.sh — never on-device.
#
# Usage:
#   scripts/fetch-debian-packages.sh [--arch arm64|x86_64] [--mirror URL]
#       [--suite SUITE] [--components "main contrib"] [--triplet TRIPLET]
#       [--packages-file FILE] [--out DIR] [--cache-dir DIR]
#
# Defaults:
#   --arch arm64 --mirror https://deb.debian.org/debian --suite stable
#   --components main --packages-file toolchain/debian-packages.txt
#   --out build/glibc-staging/arm64/glibc --cache-dir build/deb-cache/arm64
#
# Notes:
# - arm64 .debs live in the main archive (release architecture). A Debian Ports
#   mirror has the same dists/ layout, so --mirror/--suite can point at ports
#   (e.g. https://deb.debian.org/debian-ports --suite sid) without changes.
# - Only Depends + Pre-Depends are resolved transitively, never Recommends or
#   Suggests, to keep the bootstrap minimal.
# - glibc itself must NOT be in the package list: it is built from source in
#   toolchain/Dockerfile so the loader reports the on-device $PREFIX path.
#
# Requires: curl, xz, dpkg-deb, awk. Writes OUT/debian-manifest.txt
# (name version arch per resolved package) for reproducible builds.
set -eu

ARCH="arm64"
MIRROR="https://deb.debian.org/debian"
SUITE="stable"
COMPONENTS="main"
TRIPLET=""
PACKAGES_FILE="toolchain/debian-packages.txt"
OUT="build/glibc-staging/arm64/glibc"
CACHE_DIR="build/deb-cache/arm64"

while [ $# -gt 0 ]; do
    case "$1" in
        --arch=*) ARCH="${1#--arch=}"; shift ;;
        --arch) ARCH="$2"; shift 2 ;;
        --mirror=*) MIRROR="${1#--mirror=}"; shift ;;
        --mirror) MIRROR="$2"; shift 2 ;;
        --suite=*) SUITE="${1#--suite=}"; shift ;;
        --suite) SUITE="$2"; shift 2 ;;
        --components=*) COMPONENTS="${1#--components=}"; shift ;;
        --components) COMPONENTS="$2"; shift 2 ;;
        --triplet=*) TRIPLET="${1#--triplet=}"; shift ;;
        --triplet) TRIPLET="$2"; shift 2 ;;
        --packages-file=*) PACKAGES_FILE="${1#--packages-file=}"; shift ;;
        --packages-file) PACKAGES_FILE="$2"; shift 2 ;;
        --out=*) OUT="${1#--out=}"; shift ;;
        --out) OUT="$2"; shift 2 ;;
        --cache-dir=*) CACHE_DIR="${1#--cache-dir=}"; shift ;;
        --cache-dir) CACHE_DIR="$2"; shift 2 ;;
        --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "fetch-debian-packages.sh: unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$ARCH" in
    arm64|aarch64) DEBARCH="arm64"; TRIPLET="${TRIPLET:-aarch64-linux-gnu}" ;;
    amd64|x86_64) DEBARCH="amd64"; TRIPLET="${TRIPLET:-x86_64-linux-gnu}" ;;
    *) echo "fetch-debian-packages.sh: unsupported --arch=$ARCH" >&2; exit 1 ;;
esac

for tool in curl xz dpkg-deb awk; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "fetch-debian-packages.sh: required tool missing: $tool" >&2; exit 1; }
done

mkdir -p "$CACHE_DIR/index" "$CACHE_DIR/debs"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM
INDEX="$WORK/index.tsv"
: > "$INDEX"

echo "fetching Packages index: suite=$SUITE arch=$DEBARCH components=$COMPONENTS"
# shellcheck disable=SC2086
for comp in $COMPONENTS; do
    base="$MIRROR/dists/$SUITE/$comp/binary-$DEBARCH/Packages"
    dest="$CACHE_DIR/index/${comp}_Packages"
    rm -f "$dest"
    if curl -fsSL "$base.xz" -o "$dest.xz"; then
        xz -dc "$dest.xz" > "$dest"; rm -f "$dest.xz"
    elif curl -fsSL "$base.gz" -o "$dest.gz"; then
        gzip -dc "$dest.gz" > "$dest"; rm -f "$dest.gz"
    elif curl -fsSL "$base" -o "$dest"; then
        :
    else
        echo "fetch-debian-packages.sh: cannot fetch index $base[.xz|.gz|]" >&2
        exit 1
    fi
    # name \t version \t filename \t depends+pre-depends (comma-joined)
    awk 'BEGIN { RS=""; FS="\n" }
        {
            name=""; ver=""; file=""; deps="";
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^Package: /) name = substr($i, 10);
                else if ($i ~ /^Version: /) ver = substr($i, 10);
                else if ($i ~ /^Filename: /) file = substr($i, 11);
                else if ($i ~ /^Depends: /) deps = deps substr($i, 10) ", ";
                else if ($i ~ /^Pre-Depends: /) deps = deps substr($i, 14) ", ";
            }
            if (name != "" && file != "") print name "\t" ver "\t" file "\t" deps;
        }' "$dest" >> "$INDEX"
done

# index_lookup <name> [version]: print "version \t filename" of first match.
index_lookup() {
    _n="$1"; _v="${2:-}"
    awk -F'\t' -v n="$_n" -v v="$_v" \
        '$1 == n && (v == "" || $2 == v) { print $2 "\t" $3; exit }' "$INDEX"
}

# normalize_dep <token>: first alternate, no version constraint, no :any qualifier.
normalize_dep() {
    _t="$1"
    # strip leading/trailing whitespace
    _t="$(printf '%s' "$_t" | sed 's/^ *//; s/ *$//')"
    # first alternate at paren-depth 0
    _t="$(printf '%s' "$_t" | awk '{
        depth = 0; out = "";
        for (i = 1; i <= length($0); i++) {
            c = substr($0, i, 1);
            if (c == "(") depth++;
            else if (c == ")") depth--;
            if (c == "|" && depth == 0) break;
            out = out c;
        }
        print out;
    }')"
    # strip version constraint and qualifiers
    _t="$(printf '%s' "$_t" | sed 's/ *([^)]*)//; s/^ *//; s/ *$//')"
    case "$_t" in
        *:*)
            _suffix="${_t##*:}"
            if [ "$_suffix" = "any" ] || [ "$_suffix" = "$DEBARCH" ]; then
                _t="${_t%:*}"
            fi
            ;;
    esac
    printf '%s' "$_t"
}

# depends_of <name>: raw comma-joined depends field from index.
depends_of() {
    awk -F'\t' -v n="$1" '$1 == n { print $4; exit }' "$INDEX"
}

# split_top_commas: comma-split respecting paren depth, one dep per line.
split_top_commas() {
    awk '{
        depth = 0; cur = "";
        for (i = 1; i <= length($0); i++) {
            c = substr($0, i, 1);
            if (c == "(") depth++;
            else if (c == ")") depth--;
            if (c == "," && depth == 0) { print cur; cur = ""; }
            else cur = cur c;
        }
        if (cur != "") print cur;
    }'
}

RESOLVED="$WORK/resolved.tsv"   # name \t version \t filename
SEEN="$WORK/seen.txt"
QUEUE="$WORK/queue.txt"
: > "$RESOLVED"; : > "$SEEN"; : > "$QUEUE"

enqueue() {
    if ! grep -qxF "$1" "$SEEN" 2>/dev/null; then
        printf '%s\n' "$1" >> "$SEEN"
        printf '%s\n' "$1" >> "$QUEUE"
    fi
}

# seeds (support name or name=version pins)
while IFS= read -r line || [ -n "$line" ]; do
    line="$(printf '%s' "$line" | sed 's/#.*//; s/^ *//; s/ *$//')"
    [ -z "$line" ] && continue
    enqueue "$line"
done < "$PACKAGES_FILE"

echo "resolving dependencies (Depends + Pre-Depends, transitive)..."
while [ -s "$QUEUE" ]; do
    spec="$(head -n 1 "$QUEUE")"
    tail -n +2 "$QUEUE" > "$QUEUE.tmp" && mv "$QUEUE.tmp" "$QUEUE"
    case "$spec" in
        *=*) pkg="${spec%%=*}"; pin="${spec#*=}" ;;
        *) pkg="$spec"; pin="" ;;
    esac
    hit="$(index_lookup "$pkg" "$pin")"
    if [ -z "$hit" ]; then
        echo "fetch-debian-packages.sh: package not in index: $spec" >&2
        exit 1
    fi
    ver="$(printf '%s' "$hit" | cut -f1)"
    file="$(printf '%s' "$hit" | cut -f2)"
    if grep -qxF "$pkg" "$WORK/done.txt" 2>/dev/null; then
        continue
    fi
    printf '%s\n' "$pkg" >> "$WORK/done.txt"
    printf '%s\t%s\t%s\n' "$pkg" "$ver" "$file" >> "$RESOLVED"
    rawdeps="$(depends_of "$pkg")"
    [ -z "$rawdeps" ] && continue
    printf '%s' "$rawdeps" | split_top_commas | while IFS= read -r tok; do
        dep="$(normalize_dep "$tok")"
        [ -z "$dep" ] && continue
        # skip essential-virtual and libc (built from source in Docker)
        case "$dep" in
            libc6|libcrypt1) continue ;;
        esac
        if ! grep -qxF "$dep" "$SEEN" 2>/dev/null; then
            printf '%s\n' "$dep" >> "$SEEN"
            printf '%s\n' "$dep" >> "$QUEUE"
        fi
    done
done

count="$(wc -l < "$RESOLVED" | tr -d ' ')"
echo "resolved $count packages"

echo "downloading .debs to $CACHE_DIR/debs..."
while IFS="$(printf '\t')" read -r pkg ver file; do
    deb="$CACHE_DIR/debs/$(basename "$file")"
    if [ ! -f "$deb" ]; then
        curl -fsSL "$MIRROR/$file" -o "$deb"
    fi
done < "$RESOLVED"

ROOT="$WORK/root"
mkdir -p "$ROOT"
echo "extracting .debs..."
while IFS="$(printf '\t')" read -r pkg ver file; do
    dpkg-deb -x "$CACHE_DIR/debs/$(basename "$file")" "$ROOT"
done < "$RESOLVED"

echo "relocating merged-/usr layout into glibc tree at $OUT..."
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/share" "$OUT/etc" "$OUT/libexec"
copy_tree() {
    # copy_tree <src...> <dest>: cp -a contents if src exists
    _dest="$1"; shift
    for _s in "$@"; do
        if [ -e "$_s" ]; then
            mkdir -p "$_dest"
            cp -a "$_s/." "$_dest/"
        fi
    done
}
# binaries (sbin folded into bin: no sbin separation in the minimal prefix)
copy_tree "$OUT/bin" "$ROOT/usr/bin" "$ROOT/bin" "$ROOT/usr/sbin" "$ROOT/sbin"
# triplet libs keep their triplet qualificaton for the loader RPATH
if [ -d "$ROOT/usr/lib/$TRIPLET" ]; then
    mkdir -p "$OUT/lib/$TRIPLET"
    cp -a "$ROOT/usr/lib/$TRIPLET/." "$OUT/lib/$TRIPLET/"
fi
if [ -d "$ROOT/lib/$TRIPLET" ]; then
    mkdir -p "$OUT/lib/$TRIPLET"
    cp -a "$ROOT/lib/$TRIPLET/." "$OUT/lib/$TRIPLET/"
fi
# non-triplet shared libs (excluding the triplet dirs already handled)
if [ -d "$ROOT/usr/lib" ]; then
    (cd "$ROOT/usr/lib" && ls -A) | while IFS= read -r entry; do
        if [ "$entry" != "$TRIPLET" ]; then
            cp -a "$ROOT/usr/lib/$entry" "$OUT/lib/"
        fi
    done
fi
copy_tree "$OUT/libexec" "$ROOT/usr/libexec"
copy_tree "$OUT/etc" "$ROOT/etc"
# shared data minus docs to keep the bootstrap small
if [ -d "$ROOT/usr/share" ]; then
    (cd "$ROOT/usr/share" && ls -A) | while IFS= read -r entry; do
        case "$entry" in
            doc|man|info|lintian|menu) continue ;;
            *) cp -a "$ROOT/usr/share/$entry" "$OUT/share/" ;;
        esac
    done
fi

# report absolute symlinks (would dangle on-device) without failing the build
abs_links="$(find "$OUT" -type l ! -lname '../*' ! -lname './*' ! -lname '[^/]*' -print 2>/dev/null || true)"
if [ -n "$abs_links" ]; then
    echo "warning: absolute symlinks shipped (verify targets exist under \$PREFIX/glibc):" >&2
    echo "$abs_links" >&2
fi

# reproducible manifest of exactly what went in
sort "$RESOLVED" | awk -F'\t' -v a="$DEBARCH" '{ print $1 " " $2 " " a }' > "$OUT/debian-manifest.txt"

echo "glibc staging ready: $OUT ($(du -sh "$OUT" | cut -f1), $(sort -u "$RESOLVED" | wc -l | tr -d ' ') packages)"
echo "manifest: $OUT/debian-manifest.txt"
