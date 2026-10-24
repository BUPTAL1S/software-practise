#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${JAVA_HOME:?JAVA_HOME is required}"
clang -dynamiclib -O2 \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  CallWindow.m \
  -framework Cocoa \
  -o libcallwindow.dylib
echo "Built $(pwd)/libcallwindow.dylib"
