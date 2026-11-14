#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"

# 1. 寻找并配置 JDK 17 与 Maven 环境
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/Library/Java/JavaVirtualMachines/liberica-jdk-17-full.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/liberica-jdk-17-full.jdk/Contents/Home"
  elif [[ -d "/opt/homebrew/opt/openjdk@17" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
  fi
fi

if [[ -n "${JAVA_HOME:-}" && -d "$JAVA_HOME/bin" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

# 2. 清理 macOS 隔离标记并配置原生动态库加载路径
xattr -dr com.apple.quarantine "$REPO/native/macos-arm64" 2>/dev/null || true
export DYLD_LIBRARY_PATH="$REPO/native/macos-arm64:${DYLD_LIBRARY_PATH:-}"

echo "===================================================================="
echo "  Java SIP 客户端启动程序 (macOS)"
echo "  模式：纯客户端模式（无需本地 Docker / 无需本地后台服务）"
echo "===================================================================="

REMOTE_SERVER=""
EXTRA_ARGS=""

# 3. 解析命令行参数
if [[ $# -gt 0 ]]; then
  # 若首个参数为纯 IP 地址或 IP:PORT（例如 10.21.247.82 或 10.21.247.82:8080）
  if [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(:[0-9]+)?$ ]]; then
    REMOTE_SERVER="$1"
    shift
    EXTRA_ARGS="$*"
  else
    EXTRA_ARGS="$*"
  fi
elif [[ -t 0 ]]; then
  # 终端交互模式：从 .local-prefs 读取上次连接的远程服务器 IP 作为默认建议
  SAVED_IP=""
  if [[ -f "$REPO/client/.local-prefs" ]]; then
    SAVED_IP=$(grep -E '^sip\.server=' "$REPO/client/.local-prefs" 2>/dev/null | cut -d'=' -f2 | tr -d '\r\n ' || true)
  fi
  # 若历史保存为本地回环，则推荐常用局域网 IP 10.21.247.82
  if [[ -z "$SAVED_IP" || "$SAVED_IP" == "127.0.0.1" || "$SAVED_IP" == "localhost" ]]; then
    SAVED_IP="10.21.247.82"
  fi

  echo "提示：直接回车将连接目标服务器 [$SAVED_IP]"
  echo "      或输入对方主机 IP（可带端口如 10.21.x.x:8080）并按回车："
  echo ""
  read -r -p "目标主机 IP [$SAVED_IP]: " INPUT_IP || true
  INPUT_IP="${INPUT_IP:-$SAVED_IP}"
  if [[ -n "$INPUT_IP" ]]; then
    REMOTE_SERVER="$INPUT_IP"
  fi
fi

# 4. 组装客户端启动参数
FINAL_ARGS=""
if [[ -n "$REMOTE_SERVER" ]]; then
  SERVER_HOST="$REMOTE_SERVER"
  HTTP_PORT="8080"
  if [[ "$REMOTE_SERVER" == *":"* ]]; then
    SERVER_HOST="${REMOTE_SERVER%%:*}"
    HTTP_PORT="${REMOTE_SERVER##*:}"
  fi

  if [[ "$EXTRA_ARGS" != *"--server="* && "$EXTRA_ARGS" != *"--host="* ]]; then
    FINAL_ARGS="--server=$SERVER_HOST"
  fi
  if [[ "$EXTRA_ARGS" != *"--http="* ]]; then
    FINAL_ARGS="$FINAL_ARGS --http=http://$SERVER_HOST:$HTTP_PORT"
  fi
fi

if [[ -n "$EXTRA_ARGS" ]]; then
  FINAL_ARGS="$FINAL_ARGS $EXTRA_ARGS"
fi

# 若未指定本地 SIP 端口，默认分配 5062
if [[ "$FINAL_ARGS" != *"--port="* ]]; then
  FINAL_ARGS="$FINAL_ARGS --port=5062"
fi

FINAL_ARGS="$(echo "$FINAL_ARGS" | xargs)"

echo ""
echo "[INFO] 正在拉起 JavaFX 客户端..."
if [[ -n "$FINAL_ARGS" ]]; then
  echo "[INFO] 连接参数: $FINAL_ARGS"
fi
echo "===================================================================="

cd "$REPO/client"
if command -v mvn >/dev/null 2>&1; then
  exec mvn -q javafx:run -Djavafx.args="$FINAL_ARGS"
elif [[ -f "$REPO/client/target/sip-client-0.1.0-all.jar" ]]; then
  exec java -Dsip.native.dir="$REPO/native/macos-arm64" -jar "$REPO/client/target/sip-client-0.1.0-all.jar" $FINAL_ARGS
else
  echo "[ERROR] 未检测到 mvn 命令且 target/sip-client-0.1.0-all.jar 不存在，请先安装 Maven 或打包。"
  exit 1
fi
