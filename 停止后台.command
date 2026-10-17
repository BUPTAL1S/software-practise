#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

echo "[INFO] 正在查找 Spring Boot 后台服务进程..."
STOPPED=0

if [[ -f "admin/.admin.pid" ]]; then
  PID=$(cat "admin/.admin.pid" 2>/dev/null || true)
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    echo "[INFO] 终止记录的后台进程 (PID: $PID)..."
    kill "$PID" 2>/dev/null || true
    rm -f "admin/.admin.pid"
    STOPPED=1
  fi
fi

# 检查后台端口上是否有残留的 java 进程 (Spring Boot)
for P in 8080 8088 8081 8082 8083; do
  PIDS=$(lsof -ti :"$P" 2>/dev/null || true)
  if [[ -n "$PIDS" ]]; then
    for PID in $PIDS; do
      CMD=$(ps -p "$PID" -o comm= 2>/dev/null || true)
      if [[ "$CMD" == *"java"* ]]; then
        echo "[INFO] 终止端口 $P 上的 Java 服务 (PID: $PID)..."
        kill "$PID" 2>/dev/null || true
        STOPPED=1
      fi
    done
  fi
done

if [[ $STOPPED -eq 1 ]]; then
  echo "[INFO] Spring Boot 后台服务已停止。"
else
  echo "[INFO] 未发现正在运行的 Spring Boot 后台服务。"
fi
