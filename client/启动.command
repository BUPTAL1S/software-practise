#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -d "/opt/homebrew/opt/openjdk@17" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
elif [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
fi
export PATH="$JAVA_HOME/bin:$PATH"
export DYLD_LIBRARY_PATH="$REPO/native/macos-arm64:${DYLD_LIBRARY_PATH:-}"

# 1. 检查并按需启动 SIP 信令与 MCU 容器 (Kamailio + FreeSWITCH)
if ! nc -z 127.0.0.1 5060 >/dev/null 2>&1; then
  echo "[INFO] 检测到 SIP 服务未在端口 5060 运行，正在拉起 Docker 容器 (Kamailio + FreeSWITCH)..."
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    (cd "$REPO/server" && ./start-kamailio.sh >/dev/null 2>&1 || docker compose --profile mcu up -d >/dev/null 2>&1)
    for i in {1..15}; do
      if nc -z 127.0.0.1 5060 >/dev/null 2>&1; then
        echo "[INFO] SIP 服务已在端口 5060 启动就绪。"
        break
      fi
      sleep 1
    done
  else
    echo "[WARN] 未检测到 Docker 守护进程，若需 SIP 注册与音视频通话，请先启动 Docker Desktop。"
  fi
else
  echo "[INFO] 检测到 SIP 服务 (Kamailio) 已在端口 5060 正常运行。"
fi

# 2. 检查 Spring Boot 是否已在某端口健康运行
SPRING_PORT=""
for P in 8088 8081 8082 8083 8080; do
  if curl -s -m 1 "http://127.0.0.1:$P/api/health" 2>/dev/null | grep -q '"status"'; then
    SPRING_PORT="$P"
    echo "[INFO] 检测到 Spring Boot 后台已在端口 $P 运行并就绪。"
    break
  fi
done

# 3. 若未运行，自动寻找未被占用的端口并后台拉起 Spring Boot
if [[ -z "$SPRING_PORT" ]]; then
  TARGET_PORT=""
  for P in 8088 8081 8082 8083 8084 8085; do
    if ! lsof -i :"$P" >/dev/null 2>&1; then
      TARGET_PORT="$P"
      break
    fi
  done

  if [[ -z "$TARGET_PORT" ]]; then
    echo "[ERROR] 未能找到可用端口（8088-8085 均被占用），请检查端口占用。"
    exit 1
  fi

  ADMIN_DIR="$REPO/admin"
  mkdir -p "$ADMIN_DIR/logs"
  ADMIN_LOG="$ADMIN_DIR/logs/admin.log"
  echo "[INFO] 正在后台启动 Spring Boot 服务 (端口: $TARGET_PORT)..."
  (
    cd "$ADMIN_DIR"
    export SERVER_PORT="$TARGET_PORT"
    nohup mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$TARGET_PORT" > "$ADMIN_LOG" 2>&1 &
    echo $! > "$ADMIN_DIR/.admin.pid"
  )

  echo -n "[INFO] 等待后台服务就绪 (http://127.0.0.1:$TARGET_PORT/api/health)"
  MAX_WAIT=35
  WAITED=0
  READY=0
  while [[ $WAITED -lt $MAX_WAIT ]]; do
    if curl -s -m 1 "http://127.0.0.1:$TARGET_PORT/api/health" 2>/dev/null | grep -q '"status"'; then
      READY=1
      break
    fi
    sleep 1
    WAITED=$((WAITED + 1))
    printf "."
  done
  echo ""

  if [[ $READY -eq 1 ]]; then
    echo "[INFO] Spring Boot 后台已就绪 (耗时 ${WAITED} 秒)。"
    SPRING_PORT="$TARGET_PORT"
  else
    echo "[WARN] 等待后台服务就绪超时，客户端仍将启动并尝试自动重连。日志文件: $ADMIN_LOG"
    SPRING_PORT="$TARGET_PORT"
  fi
fi

cd "$REPO/client"
if [[ $# -gt 0 ]]; then
  ARGS="$*"
  if [[ "$ARGS" != *"--http="* ]]; then
    ARGS="--http=http://localhost:$SPRING_PORT $ARGS"
  fi
  if [[ "$ARGS" != *"--port="* ]]; then
    ARGS="--port=5062 $ARGS"
  fi
else
  ARGS="--http=http://localhost:$SPRING_PORT --port=5062"
fi

echo "[INFO] 正在拉起 JavaFX 客户端 (参数: $ARGS)..."
exec mvn -q javafx:run -Djavafx.args="$ARGS"
