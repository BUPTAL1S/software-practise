#!/usr/bin/env bash
set -euo pipefail

# 跨平台编译脚本：在 macOS 或 Linux 上使用 mingw-w64 交叉编译 winmirror.dll (x64)
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

CC="${CC:-x86_64-w64-mingw32-gcc}"
if ! command -v "$CC" >/dev/null 2>&1; then
  echo "[ERROR] 未检测到 Windows 交叉编译器 $CC"
  echo "  macOS 安装方式: brew install mingw-w64"
  echo "  Ubuntu/Debian:  sudo apt-get install mingw-w64"
  exit 1
fi

JAVA_HOME="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.home/{print $2}')}"
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME/include" ]; then
  echo "[ERROR] 无法定位有效的 JAVA_HOME: $JAVA_HOME"
  exit 1
fi

echo "[INFO] 正在编译 Windows 原生镜像模块 winmirror.dll (x64)..."
"$CC" -shared -O2 -s -static-libgcc \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/win32" \
  -I"$JAVA_HOME/include/darwin" \
  -I"$JAVA_HOME/include/linux" \
  NativeMirrorWin.c \
  -o winmirror.dll \
  -luser32

echo "[SUCCESS] winmirror.dll 编译完成: $DIR/winmirror.dll"
file winmirror.dll
