#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
exec "./client/启动.command" "$@"
