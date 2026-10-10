#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd .. && pwd)"
if [[ -d "/opt/homebrew/opt/openjdk@17" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
elif [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
fi
export PATH="$JAVA_HOME/bin:$PATH"
export DYLD_LIBRARY_PATH="$REPO/native/macos-arm64:${DYLD_LIBRARY_PATH:-}"
if [[ $# -gt 0 ]]; then
  exec mvn -q javafx:run -Djavafx.args="$*"
fi
exec mvn -q javafx:run
