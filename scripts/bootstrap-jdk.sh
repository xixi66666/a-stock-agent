#!/usr/bin/env sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tools_dir="$project_root/.tools"
jdk_dir="$tools_dir/jdk-21"

if [ -x "$jdk_dir/bin/java" ]; then
  printf '%s\n' "$jdk_dir"
  exit 0
fi

os=$(uname -s)
arch=$(uname -m)
case "$os:$arch" in
  Linux:x86_64) key=linux.x64 ;;
  Darwin:x86_64) key=mac.x64 ;;
  Darwin:arm64) key=mac.arm64 ;;
  *) printf 'Unsupported platform: %s %s\n' "$os" "$arch" >&2; exit 1 ;;
esac

properties="$project_root/scripts/toolchain.properties"
url=$(awk -F= -v key="$key.url" '$1 == key {sub($1 "=", ""); print}' "$properties")
expected=$(awk -F= -v key="$key.sha256" '$1 == key {print $2}' "$properties")
downloads="$tools_dir/downloads"
archive="$downloads/temurin-jdk-21.tar.gz"
extract="$tools_dir/jdk-extract"
mkdir -p "$downloads"

if [ ! -f "$archive" ]; then
  curl --fail --location "$url" --output "$archive"
fi
actual=$(shasum -a 256 "$archive" | awk '{print $1}')
[ "$actual" = "$expected" ] || { printf 'JDK checksum mismatch\n' >&2; exit 1; }
rm -rf "$extract"
mkdir -p "$extract"
tar -xzf "$archive" -C "$extract"
extracted=$(find "$extract" -mindepth 1 -maxdepth 1 -type d | head -n 1)
[ -x "$extracted/bin/java" ] || { printf 'Downloaded archive is not a JDK\n' >&2; exit 1; }
mv "$extracted" "$jdk_dir"
rm -rf "$extract"
printf '%s\n' "$jdk_dir"
