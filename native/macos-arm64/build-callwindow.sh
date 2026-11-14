#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/opt/homebrew/opt/openjdk@17" ]]; then
    JAVA_HOME="/opt/homebrew/opt/openjdk@17"
  elif [[ -x "/usr/libexec/java_home" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -d "$JAVA_HOME/include" ]]; then
  echo "[ERROR] JAVA_HOME is required and must contain include/ directory." >&2
  exit 1
fi

clang -dynamiclib -O2 -fPIC \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  CallWindow.m \
  -framework Cocoa \
  -o libcallwindow.dylib

codesign -s - -f libcallwindow.dylib

echo "Built and signed $(pwd)/libcallwindow.dylib"
