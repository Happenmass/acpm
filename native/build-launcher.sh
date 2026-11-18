#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    printf '%s\n' 'Usage: native/build-launcher.sh <android-ndk-directory> <output-file>' >&2
    exit 2
fi

case "$(uname -s)" in
    Darwin) tag=darwin-x86_64 ;;
    Linux) tag=linux-x86_64 ;;
    *) printf '%s\n' 'Build on macOS or Linux.' >&2; exit 2 ;;
esac

source_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
compiler="$1/toolchains/llvm/prebuilt/$tag/bin/aarch64-linux-android26-clang"
mkdir -p "$(dirname -- "$2")"
"$compiler" -std=gnu17 -Wall -Wextra -Werror -O2 -Wl,-z,max-page-size=16384 -o "$2" "$source_dir/sandbox.c"
