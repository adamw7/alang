#!/bin/sh
# Installs the ALang toolchain from the latest GitHub release:
#
#   curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh
#
# On Linux (x64, arm64) and macOS (arm64) it installs the native binary, which needs no Java. Anywhere else it
# installs the release archive with the jar, which needs Java 21 or newer. Either way the download is checked
# against the release's SHA256SUMS, unpacked into ALANG_HOME and linked as ALANG_BIN_DIR/alang.
#
# Settings, as environment variables:
#   ALANG_VERSION   a release tag such as 0.7.0 (default: the latest release)
#   ALANG_HOME      where the toolchain goes (default: ~/.alang)
#   ALANG_BIN_DIR   where the `alang` link goes, a directory on PATH (default: ~/.local/bin)
#   ALANG_JAR=1     install the jar archive even where a native binary exists
#   ALANG_DOWNLOAD_URL  where the releases are (default: https://github.com/adamw7/alang/releases)
set -eu

REPO="adamw7/alang"
BASE="${ALANG_DOWNLOAD_URL:-https://github.com/$REPO/releases}"
HOME_DIR="${ALANG_HOME:-$HOME/.alang}"
BIN_DIR="${ALANG_BIN_DIR:-$HOME/.local/bin}"

say() { printf 'alang: %s\n' "$*" >&2; }
fail() { say "$*"; exit 1; }

if command -v curl > /dev/null 2>&1; then
  fetch() { curl -fsSL --retry 3 -o "$2" "$1"; }
elif command -v wget > /dev/null 2>&1; then
  fetch() { wget -q -O "$2" "$1"; }
else
  fail "needs curl or wget"
fi

sha256() {
  if command -v sha256sum > /dev/null 2>&1; then sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum > /dev/null 2>&1; then shasum -a 256 "$1" | cut -d ' ' -f 1
  else fail "needs sha256sum or shasum to check the download"
  fi
}

# The platform, as the release names its native archives.
os="$(uname -s)"
arch="$(uname -m)"
case "$os" in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  *) os="" ;;
esac
case "$arch" in
  x86_64 | amd64) arch=x64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) arch="" ;;
esac
platform=""
case "$os-$arch" in
  linux-x64 | linux-arm64 | macos-arm64) platform="$os-$arch" ;;
esac
# The Linux binaries need glibc 2.35 or newer; with another C library, or an older one, use the jar.
if [ "$os" = linux ] && [ -n "$platform" ]; then
  glibc="$(getconf GNU_LIBC_VERSION 2> /dev/null | cut -d ' ' -f 2 || true)"
  major="${glibc%%.*}"
  minor="${glibc#*.}"
  minor="${minor%%.*}"
  case "$major.$minor" in
    *[!0-9.]* | . | *..*) platform="" ;;
    *) if [ "$major" -lt 2 ] || { [ "$major" -eq 2 ] && [ "$minor" -lt 35 ]; }; then platform=""; fi ;;
  esac
  [ -n "$platform" ] || say "no glibc 2.35 or newer here (found: ${glibc:-none}); installing the jar, which needs Java"
fi
[ "${ALANG_JAR:-}" = 1 ] && platform=""

if [ -n "${ALANG_VERSION:-}" ]; then
  url="$BASE/download/$ALANG_VERSION"
else
  url="$BASE/latest/download"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT INT TERM

fetch "$url/SHA256SUMS" "$tmp/SHA256SUMS" || fail "cannot download $url/SHA256SUMS"
if [ -n "$platform" ]; then
  file="alang-$platform.tar.gz"
  grep -q " $file\$" "$tmp/SHA256SUMS" || { say "the release has no $file; installing the jar, which needs Java"; platform=""; }
fi
if [ -z "$platform" ]; then
  # The jar's archive is named by its version: alang-0.7.0.tar.gz.
  file="$(sed -n 's/^[0-9a-f]*  *\(alang-[0-9][^ ]*\.tar\.gz\)$/\1/p' "$tmp/SHA256SUMS" | head -n 1)"
  [ -n "$file" ] || fail "the release lists no archive in SHA256SUMS"
  command -v java > /dev/null 2>&1 || say "warning: java is not on PATH; the toolchain needs Java 21 or newer"
fi

say "downloading $file"
fetch "$url/$file" "$tmp/$file" || fail "cannot download $url/$file"
want="$(grep " $file\$" "$tmp/SHA256SUMS" | cut -d ' ' -f 1)"
got="$(sha256 "$tmp/$file")"
[ "$want" = "$got" ] || fail "$file does not match its checksum in SHA256SUMS (expected $want, got $got)"

mkdir -p "$tmp/unpacked"
tar -xzf "$tmp/$file" -C "$tmp/unpacked"
top="$(ls "$tmp/unpacked")"
[ -x "$tmp/unpacked/$top/bin/alang" ] || fail "$file has no bin/alang"

# Replaces an earlier install whole, so no file of an older version is left behind; a directory that holds
# anything else is left alone.
if [ -e "$HOME_DIR" ] && ! { [ -f "$HOME_DIR/AGENTS.md" ] && [ -e "$HOME_DIR/bin/alang" ]; }; then
  fail "$HOME_DIR exists and is not an ALang install; set ALANG_HOME to another directory"
fi
mkdir -p "$(dirname "$HOME_DIR")"
rm -rf "$HOME_DIR.new"
mv "$tmp/unpacked/$top" "$HOME_DIR.new"
rm -rf "$HOME_DIR"
mv "$HOME_DIR.new" "$HOME_DIR"
mkdir -p "$BIN_DIR"
ln -sf "$HOME_DIR/bin/alang" "$BIN_DIR/alang"

say "installed $("$BIN_DIR/alang" version 2> /dev/null || echo "$file") in $HOME_DIR, linked as $BIN_DIR/alang"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) say "add $BIN_DIR to your PATH: export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
say "agents start with $HOME_DIR/AGENTS.md"
