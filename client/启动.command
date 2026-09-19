#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd .. && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
export DYLD_LIBRARY_PATH="$REPO/native/macos-arm64:${DYLD_LIBRARY_PATH:-}"
if [[ $# -gt 0 ]]; then
  exec mvn -q javafx:run -Djavafx.args="$*"
fi
exec mvn -q javafx:run
