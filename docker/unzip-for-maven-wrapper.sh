#!/bin/sh
# Minimal `unzip` stand-in, installed in the image build stage only.
#
# The Eclipse Temurin JDK image ships no `unzip`. Without it the Maven Wrapper
# silently switches to the `.tar.gz` distribution, whose checksum is not the
# `.zip` SHA-256 pinned in `.mvn/wrapper/maven-wrapper.properties`, and the
# wrapper aborts with "Failed to validate Maven distribution SHA-256". Installing
# a distribution package would make the image build depend on an operating-system
# archive; `jar` is already part of the JDK and reads the same ZIP container, so
# the build stays reachable-only-from-Maven-Central and the wrapper keeps
# verifying the frozen checksum it was given.
#
# This handles exactly the invocation the wrapper makes: `unzip [-q] ARCHIVE -d DIR`.
set -eu

target=.
archive=

while [ "$#" -gt 0 ]; do
  case "$1" in
    -d)
      target=$2
      shift 2
      ;;
    -*)
      shift
      ;;
    *)
      archive=$(readlink -f "$1")
      shift
      ;;
  esac
done

[ -n "$archive" ] || {
  echo "unzip-for-maven-wrapper: no archive given" >&2
  exit 2
}

mkdir -p "$target"
(cd "$target" && jar --extract --file "$archive")

# `jar` cannot restore the POSIX mode bits a ZIP entry carries. The launcher
# scripts are the only extracted entries that need one.
find "$target" -type d -name bin -exec sh -c 'chmod 0755 "$1"/*' _ {} \;
