#!/usr/bin/env bash
# ==============================================================================
# Java SIP 课设阶段 6 绿色便携包一键构建与打包脚本
# 交付目标：免配置环境（无 JDK、无 Maven、无环境变量），解压即用
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST_DIR="$REPO/dist"
GROUP_NAME="${GROUP_ID:-第01组}"

echo "===================================================================="
echo "  正在构建 Java SIP 阶段 6 绿色便携包 (形态B / 免配置开箱即用)"
echo "  发布标识: ${GROUP_NAME}-实践阶段二"
echo "===================================================================="

# 1. 寻找 OpenJDK 17 与 jlink
JLINK_BIN=""
JAVA_BIN=""
JDEPS_BIN=""
if [[ -x "/opt/homebrew/opt/openjdk@17/bin/jlink" ]]; then
  JLINK_BIN="/opt/homebrew/opt/openjdk@17/bin/jlink"
  JAVA_BIN="/opt/homebrew/opt/openjdk@17/bin/java"
  JDEPS_BIN="/opt/homebrew/opt/openjdk@17/bin/jdeps"
elif [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/jlink" ]]; then
  JLINK_BIN="$JAVA_HOME/bin/jlink"
  JAVA_BIN="$JAVA_HOME/bin/java"
  JDEPS_BIN="$JAVA_HOME/bin/jdeps"
elif command -v jlink >/dev/null 2>&1; then
  JLINK_BIN="$(command -v jlink)"
  JAVA_BIN="$(command -v java)"
  JDEPS_BIN="$(command -v jdeps || true)"
else
  echo "[ERROR] 未能找到 jlink 工具，请确保已安装 JDK 17+。" >&2
  exit 1
fi

echo "[1/7] 检测到 JDK 工具: $JLINK_BIN"

# 2. 检查或编译客户端跨平台 Fat JAR
CLIENT_JAR="$REPO/client/target/sip-client-0.1.0-all.jar"
if [[ ! -f "$CLIENT_JAR" ]] || [[ "${1:-}" == "--rebuild" ]]; then
  echo "[2/7] 正在编译客户端跨平台 Fat JAR (内含 JavaFX 跨平台图形运行时)..."
  (cd "$REPO/client" && mvn clean package -Ppackage-windows -DskipTests)
else
  echo "[2/7] 客户端 Fat JAR 已就绪: $CLIENT_JAR"
fi

# 3. 检查或编译服务端 Spring Boot 可执行 JAR
ADMIN_JAR="$REPO/admin/target/sipim-admin-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$ADMIN_JAR" ]] || [[ "${1:-}" == "--rebuild" ]]; then
  echo "[3/7] 正在编译服务端 Spring Boot 可执行 JAR..."
  (cd "$REPO/admin" && mvn clean package -DskipTests)
else
  echo "[3/7] 服务端 JAR 已就绪: $ADMIN_JAR"
fi

# 4. jdeps 动态分析依赖模块
echo "[4/7] 执行 jdeps 动态分析客户端与运行时模块依赖..."
DETECTED_MODULES=""
if [[ -x "$JDEPS_BIN" ]]; then
  DETECTED_MODULES="$("$JDEPS_BIN" --print-module-deps --ignore-missing-deps -q "$CLIENT_JAR" 2>/dev/null || true)"
  echo "  -> jdeps 分析得到基础依赖模块: ${DETECTED_MODULES:-java.base,java.desktop}"
fi

# 补充 GUI、网络通信、JFR性能剖析、桌面特性及轻量数据库模块
FINAL_MODULES="java.base,java.desktop,java.logging,java.net.http,java.prefs,java.sql,jdk.jfr,jdk.unsupported,jdk.unsupported.desktop"
if [[ -n "$DETECTED_MODULES" ]]; then
  IFS=',' read -ra MOD_ARR <<< "$DETECTED_MODULES"
  for m in "${MOD_ARR[@]}"; do
    m_clean="$(echo "$m" | tr -d ' ')"
    if [[ "$FINAL_MODULES" != *"$m_clean"* ]]; then
      FINAL_MODULES="$FINAL_MODULES,$m_clean"
    fi
  done
fi
echo "  -> jlink 最终组装模块列表: $FINAL_MODULES"

# 5. 组装 macOS arm64 绿色便携包
echo "[5/7] 正在组装 macOS arm64 便携包..."
MAC_DIR="$DIST_DIR/JavaSIP-Client-macOS-arm64"
rm -rf "$MAC_DIR"
mkdir -p "$MAC_DIR/lib" "$MAC_DIR/native"

# 5.1 使用 jlink 导出轻量精简 JRE (~41MB)
echo "  -> 生成嵌入式精简 JRE..."
"$JLINK_BIN" \
  --add-modules "$FINAL_MODULES" \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=2 \
  --output "$MAC_DIR/jre"

# 5.2 拷贝客户端 JAR
cp "$CLIENT_JAR" "$MAC_DIR/lib/sip-client.jar"

# 5.3 拷贝 macOS 原生动态库
if [[ -d "$REPO/native/macos-arm64" ]]; then
  cp -R "$REPO/native/macos-arm64/"* "$MAC_DIR/native/" 2>/dev/null || true
fi

# 5.4 写入 macOS 默认一键启动脚本
cat > "$MAC_DIR/启动客户端.command" << 'EOF'
#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true
export DYLD_LIBRARY_PATH="$DIR/native:${DYLD_LIBRARY_PATH:-}"

echo "===================================================================="
echo "  Java SIP 客户端 (macOS 便携版 - 免安装环境)"
echo "===================================================================="

JAVA_EXEC="$DIR/jre/bin/java"
if [[ ! -x "$JAVA_EXEC" ]]; then
  JAVA_EXEC="java"
fi

ARGS="$*"
if [[ $# -eq 0 ]]; then
  echo "[提示] 直接双击启动：默认连接本机或上次保存的服务端。"
  echo "[提示] 若要连接其他电脑（电脑 A），可双击运行【启动客户端-指定服务器IP.command】"
fi

exec "$JAVA_EXEC" \
  -Dsip.native.dir="$DIR/native" \
  -jar "$DIR/lib/sip-client.jar" \
  $ARGS
EOF
chmod +x "$MAC_DIR/启动客户端.command"

# 5.5 写入交互式指定服务器 IP 启动脚本
cat > "$MAC_DIR/启动客户端-指定服务器IP.command" << 'EOF'
#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true
export DYLD_LIBRARY_PATH="$DIR/native:${DYLD_LIBRARY_PATH:-}"

echo "===================================================================="
echo "  Java SIP 客户端 - 双机验收连接配置 (macOS 便携版)"
echo "===================================================================="
echo "当前模式：电脑 B（被叫方 / 老师电脑）连接 电脑 A（主机）"
echo ""
read -r -p "请输入主机（电脑 A）的 IP 地址 [回车默认 127.0.0.1]: " HOST_IP
HOST_IP="${HOST_IP:-127.0.0.1}"

read -r -p "请输入当前客户端用户编号 [回车默认 1002]: " USER_ID
USER_ID="${USER_ID:-1002}"

read -r -p "请输入当前客户端本地 SIP 端口 [回车默认 5062]: " SIP_PORT
SIP_PORT="${SIP_PORT:-5062}"

JAVA_EXEC="$DIR/jre/bin/java"
if [[ ! -x "$JAVA_EXEC" ]]; then
  JAVA_EXEC="java"
fi

echo ""
echo "[INFO] 正在启动客户端并连接主机: $HOST_IP (用户: $USER_ID, 端口: $SIP_PORT)..."
exec "$JAVA_EXEC" \
  -Dsip.native.dir="$DIR/native" \
  -jar "$DIR/lib/sip-client.jar" \
  --server="$HOST_IP" \
  --user="$USER_ID" \
  --port="$SIP_PORT" \
  --peer="1001"
EOF
chmod +x "$MAC_DIR/启动客户端-指定服务器IP.command"

# 5.6 写入 macOS 使用说明
cat > "$MAC_DIR/使用说明-macOS.txt" << 'EOF'
================================================================================
  Java SIP 桌面客户端 (macOS 便携绿色包) - 阶段 6 验收说明
================================================================================

【特性】
1. 完全绿色便携：内嵌定制精简 JRE 17 运行时，目标机器【无需安装 JDK、无需 Maven、无需配置环境变量】；
2. 解压即用：直接双击脚本即可拉起图形界面，包含完整 PJSIP 2.17 与 H.264 硬件/软件编解码支持；
3. 原生前置镜像：内建 libmacmirror.dylib，前置摄像头视频实时翻转，还原自然镜像体验。

【使用方式】
1. 单机演示 / 本机测试：
   - 直接双击【启动客户端.command】；
   - 默认以 1001 账号连接本机 127.0.0.1。

2. 双机协同验收（电脑 B / 老师电脑 / 被叫方）：
   - 双击【启动客户端-指定服务器IP.command】；
   - 在弹出的终端提示中输入主机电脑（电脑 A）的局域网 IP（例如 10.21.253.218）；
   - 用户编号默认 1002，本地端口默认 5062；
   - 点击界面上的【注册/上线】即可完成上线，并可与主机（1001）进行一对一视频通话、文字聊天、文件传输，或呼叫 sip:room-101@lab 加入多人 MCU 会议室。

【首次打开系统提示“无法打开”解决办法】
macOS 首次运行便携脚本时若提示安全隔离拦截：
- 右键点击【启动客户端.command】 -> 选择【打开】 -> 在弹窗中点击【打开】；
- 脚本已内置自动清除隔离属性指令 (xattr -dr com.apple.quarantine)。
EOF
cp "$MAC_DIR/启动客户端.command" "$MAC_DIR/start-client.command"
cp "$MAC_DIR/启动客户端-指定服务器IP.command" "$MAC_DIR/start-client-with-ip.command"
cp "$MAC_DIR/使用说明-macOS.txt" "$MAC_DIR/README-macOS.txt"
chmod +x "$MAC_DIR/start-client.command" "$MAC_DIR/start-client-with-ip.command"

# 6. 组装 Windows x64 便携包 (完整内嵌 JRE 17 + 原生 pjsua2.dll 与 MinGW 运行时)
echo "[6/7] 正在组装 Windows x64 便携包 (含内嵌 JRE 与原生 pjsua2.dll)..."
WIN_DIR="$DIST_DIR/JavaSIP-Client-Windows-x64"
rm -rf "$WIN_DIR"
mkdir -p "$WIN_DIR/lib" "$WIN_DIR/native" "$WIN_DIR/jre"

# 6.1 拷贝跨平台 Fat JAR
cp "$CLIENT_JAR" "$WIN_DIR/lib/sip-client.jar"

# 6.2 拷贝 Windows 原生库 (含交叉编译的 pjsua2.dll, winmirror.dll 以及 MinGW 运行时)
if [[ -d "$REPO/native/windows-x64" ]]; then
  cp -R "$REPO/native/windows-x64/"* "$WIN_DIR/native/" 2>/dev/null || true
fi

# 6.3 提取嵌入式 Windows x64 JRE (42MB zip 解压后约 110MB)
if [[ -f "$REPO/scripts/cache/win-jre.zip" ]]; then
  echo "  -> 解压嵌入式 Windows x64 JRE..."
  mkdir -p "$WIN_DIR/jre_tmp"
  unzip -q "$REPO/scripts/cache/win-jre.zip" -d "$WIN_DIR/jre_tmp"
  if compgen -G "$WIN_DIR/jre_tmp/jdk*" > /dev/null; then
    mv "$WIN_DIR/jre_tmp"/jdk*/* "$WIN_DIR/jre/" 2>/dev/null || true
  else
    mv "$WIN_DIR/jre_tmp"/* "$WIN_DIR/jre/" 2>/dev/null || true
  fi
  rm -rf "$WIN_DIR/jre_tmp"
fi

# 6.4 写入 Windows 默认启动批处理
cat > "$WIN_DIR/启动客户端.bat" << 'EOF'
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "PATH=%~dp0native;%PATH%"

echo ====================================================================
echo   Java SIP 客户端 (Windows 便携版 - 免安装环境)
echo ====================================================================

set "JAVA_CMD="
if exist "%~dp0jre\bin\java.exe" (
    set "JAVA_CMD=%~dp0jre\bin\java.exe"
) else (
    where java >nul 2>nul
    if !ERRORLEVEL! equ 0 (
        set "JAVA_CMD=java"
    ) else if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\java.exe" (
            set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
        )
    )
)

if "%JAVA_CMD%"=="" (
    echo [ERROR] 未检测到 Java 运行时。
    echo 请确保本目录包含完整的 jre\ 运行环境，或在本机安装 Java 17。
    pause
    exit /b 1
)

set "ARGS=%*"
if "%ARGS%"=="" (
    echo [提示] 正在使用默认配置启动客户端 (1001)...
    echo [提示] 若需要连接其他主机，请运行【启动客户端-指定服务器IP.bat】
)

"%JAVA_CMD%" -Dsip.native.dir="%~dp0native" -jar "%~dp0lib\sip-client.jar" %ARGS%
if errorlevel 1 (
    echo.
    echo [ERROR] 客户端退出异常，代码: %ERRORLEVEL%
    echo 排查建议：检查 native\ 目录下 pjsua2.dll 及依赖库是否完备。
    pause
)
EOF

# 6.5 写入 Windows 交互式指定服务器 IP 启动批处理
cat > "$WIN_DIR/启动客户端-指定服务器IP.bat" << 'EOF'
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "PATH=%~dp0native;%PATH%"

echo ====================================================================
echo   Java SIP 客户端 - 双机验收连接配置 (Windows 便携版)
echo ====================================================================
echo 当前模式：电脑 B（被叫方 / 老师电脑）连接 电脑 A（主机）
echo.

set /p HOST_IP="请输入主机（电脑 A）的 IP 地址 [回车默认 127.0.0.1]: "
if "%HOST_IP%"=="" set "HOST_IP=127.0.0.1"

set /p USER_ID="请输入当前客户端用户编号 [回车默认 1002]: "
if "%USER_ID%"=="" set "USER_ID=1002"

set /p SIP_PORT="请输入当前客户端本地 SIP 端口 [回车默认 5062]: "
if "%SIP_PORT%"=="" set "SIP_PORT=5062"

set "JAVA_CMD="
if exist "%~dp0jre\bin\java.exe" (
    set "JAVA_CMD=%~dp0jre\bin\java.exe"
) else (
    where java >nul 2>nul
    if !ERRORLEVEL! equ 0 (
        set "JAVA_CMD=java"
    ) else if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\java.exe" (
            set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
        )
    )
)

if "%JAVA_CMD%"=="" (
    echo [ERROR] 未检测到 Java 运行时，请确保 jre\ 目录存在。
    pause
    exit /b 1
)

echo.
echo [INFO] 正在启动客户端并连接主机: %HOST_IP% (用户: %USER_ID%, 端口: %SIP_PORT%)...
"%JAVA_CMD%" -Dsip.native.dir="%~dp0native" -jar "%~dp0lib\sip-client.jar" --server="%HOST_IP%" --user="%USER_ID%" --port="%SIP_PORT%" --peer="1001"
if errorlevel 1 (
    echo.
    echo [ERROR] 客户端退出异常，代码: %ERRORLEVEL%
    pause
)
EOF

# 6.6 写入 Windows 使用说明
cat > "$WIN_DIR/使用说明-Windows.txt" << 'EOF'
================================================================================
  Java SIP 桌面客户端 (Windows 便携绿色包) - 阶段 6 验收说明
================================================================================

【特性】
1. 完全绿色独立：内嵌 Windows x64 JRE 17 运行环境与原生 pjsua2.dll，目标机无需安装 JDK，无需 Maven；
2. 双机协同通信：支持直连主机 Docker (Kamailio + FreeSWITCH) 与 Spring Boot 后台；
3. 动态原生驱动：包含完整 MinGW 运行时支持库与视频前置镜像翻转动态库 (winmirror.dll)。

【使用方式】
1. 单机演示：
   - 双击【启动客户端.bat】；
   - 默认以 1001 账号连接本机 127.0.0.1。

2. 双机验收（电脑 B / 老师电脑）：
   - 双击【启动客户端-指定服务器IP.bat】；
   - 输入主机电脑 A 的局域网 IP（例如 10.21.253.218）；
   - 用户编号默认 1002，本地端口默认 5062；
   - 在客户端界面点击【注册/上线】，即可与主机（1001）互相拨打高清视频电话，或进入 MCU 会议室。

【内嵌组件清单】
- jre\ : OpenJDK 17.0.20.1 64 位 Windows 嵌入式运行时
- native\ : pjsua2.dll (PJSIP 2.17 x64 原生 JNI 栈), winmirror.dll, libstdc++-6.dll, libgcc_s_seh-1.dll, libwinpthread-1.dll
- lib\ : sip-client.jar (内含 JavaFX Windows/macOS 跨平台图形渲染库)
EOF
cp "$WIN_DIR/启动客户端.bat" "$WIN_DIR/start-client.bat"
cp "$WIN_DIR/启动客户端-指定服务器IP.bat" "$WIN_DIR/start-client-with-ip.bat"
cp "$WIN_DIR/使用说明-Windows.txt" "$WIN_DIR/README-Windows.txt"

# 7. 组装完整主机一体化服务包 (电脑 A 运行，内建客户端 1001 与全栈一键拉起)
echo "[7/7] 正在组装完整主机服务包 (JavaSIP-Host-Server)..."
HOST_DIR="$DIST_DIR/JavaSIP-Host-Server"
rm -rf "$HOST_DIR"
mkdir -p "$HOST_DIR/lib" "$HOST_DIR/native" "$HOST_DIR/server"

# 7.1 拷贝服务端与客户端 JAR
cp "$ADMIN_JAR" "$HOST_DIR/lib/sipim-admin.jar"
cp "$CLIENT_JAR" "$HOST_DIR/lib/sip-client.jar"

# 7.2 拷贝客户端原生动态库与内嵌 JRE
if [[ -d "$REPO/native/macos-arm64" ]]; then
  cp -R "$REPO/native/macos-arm64/"* "$HOST_DIR/native/" 2>/dev/null || true
fi
cp -R "$MAC_DIR/jre" "$HOST_DIR/jre"

# 7.3 拷贝 server 容器配置
cp -R "$REPO/server/"* "$HOST_DIR/server/" 2>/dev/null || true

# 7.4 写入主机一键全栈启动脚本 (macOS: 容器 + 后台 + 客户端 1001 一键秒级就绪)
cat > "$HOST_DIR/一键启动-全栈服务(主机+客户端1001).command" << 'EOF'
#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "===================================================================="
echo "  Java SIP 课设全栈一体化服务 (电脑 A: 容器 + 后台 + 客户端 1001)"
echo "===================================================================="

# 1. 探测校园网/局域网 IP
LAN_IP=""
if [[ -x "$DIR/server/detect-lan-ip.sh" ]]; then
  LAN_IP="$("$DIR/server/detect-lan-ip.sh" 2>/dev/null || true)"
fi
if [[ -z "$LAN_IP" ]]; then
  LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")"
fi
echo "[INFO] 探测到本机局域网 IP: $LAN_IP"

# 2. 检查 Docker 运行环境
echo "[INFO] 正在检查 Docker 容器环境..."
if ! command -v docker >/dev/null 2>&1; then
  echo "[ERROR] 未检测到 docker 命令行工具，请先安装 Docker Desktop！"
  read -r -p "按回车键退出..."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "[ERROR] Docker 引擎未运行！请先打开 Docker Desktop 应用程序并等待就绪。"
  read -r -p "按回车键退出..."
  exit 1
fi

# 3. 启动 Kamailio 信令服务器与 FreeSWITCH 会议室容器 (不吞噬错误)
echo "[INFO] 正在启动 Kamailio SIP 服务器与 FreeSWITCH MCU 混流容器..."
(cd "$DIR/server" && {
  if [[ -x "./start-kamailio.sh" ]]; then
    ./start-kamailio.sh || docker compose --profile mcu up -d
  else
    docker compose --profile mcu up -d
  fi
})

# 4. 启动 Spring Boot 后台服务 (高精度秒表记录就绪耗时)
START_TIME_MS=$(python3 -c "import time; print(int(time.time() * 1000))" 2>/dev/null || date +%s)
JAVA_EXEC="$DIR/jre/bin/java"
if [[ ! -x "$JAVA_EXEC" ]]; then
  JAVA_EXEC="java"
fi

SPRING_PORT="8080"
if lsof -i :8080 >/dev/null 2>&1; then
  if curl -s -m 1 "http://127.0.0.1:8080/api/health" 2>/dev/null | grep -q '"status"'; then
    echo "[INFO] 检测到 Spring Boot 后台已在端口 8080 正常运行。"
  else
    echo "[WARN] 端口 8080 被其他非 SIP 进程占用，自动切换至专用备用端口 8088..."
    SPRING_PORT="8088"
  fi
fi

if ! curl -s -m 1 "http://127.0.0.1:$SPRING_PORT/api/health" 2>/dev/null | grep -q '"status"'; then
  echo "[INFO] 正在秒级启动预编译 Spring Boot 后台服务 (端口: $SPRING_PORT)..."
  mkdir -p "$DIR/logs"
  nohup "$JAVA_EXEC" -jar "$DIR/lib/sipim-admin.jar" --server.port="$SPRING_PORT" > "$DIR/logs/admin.log" 2>&1 &
  echo $! > "$DIR/.admin.pid"

  echo -n "[INFO] 等待后台服务就绪"
  READY=false
  for i in {1..30}; do
    if curl -s -m 1 "http://127.0.0.1:$SPRING_PORT/api/health" 2>/dev/null | grep -q '"status"'; then
      READY=true
      break
    fi
    printf "."
    sleep 0.5
  done

  if [[ "$READY" == "true" ]]; then
    END_TIME_MS=$(python3 -c "import time; print(int(time.time() * 1000))" 2>/dev/null || date +%s)
    DIFF_MS=$((END_TIME_MS - START_TIME_MS))
    SEC=$((DIFF_MS / 1000))
    MSEC=$(( (DIFF_MS % 1000) / 10 ))
    echo " -> 成功就绪！[PERF 启动耗时: ${SEC}.${MSEC} 秒]"
  else
    echo " -> [WARN] 等待超时，请检查 logs/admin.log"
  fi
fi

echo ""
echo "===================================================================="
echo "  主机全栈服务已就绪！"
echo "  - 本机局域网 IP: $LAN_IP"
echo "  - SIP 信令端口: 5060 (TCP/UDP)"
echo "  - FreeSWITCH MCU: 5080 (TCP/UDP), ESL: 8021"
echo "  - 后台管理页面: http://$LAN_IP:$SPRING_PORT"
echo "--------------------------------------------------------------------"
echo "  验收提示："
echo "  电脑 B（分机/老师电脑）解压 JavaSIP-Client 便携包后，"
echo "  在【启动客户端-指定服务器IP】中输入主机 IP [$LAN_IP] 即可直接注册！"
echo "===================================================================="
echo ""

# 5. 自动拉起电脑 A 本机客户端 (1001)
if [[ -f "$DIR/lib/sip-client.jar" ]]; then
  echo "[INFO] 正在启动电脑 A 本机客户端 (用户 1001, 端口 5061)..."
  xattr -dr com.apple.quarantine "$DIR" 2>/dev/null || true
  export DYLD_LIBRARY_PATH="$DIR/native:${DYLD_LIBRARY_PATH:-}"
  exec "$JAVA_EXEC" \
    -Dsip.native.dir="$DIR/native" \
    -jar "$DIR/lib/sip-client.jar" \
    --server="127.0.0.1" \
    --user="1001" \
    --port="5061" \
    --http="http://127.0.0.1:$SPRING_PORT" \
    --peer="1002"
else
  read -r -p "主机全栈服务正在运行，按回车键保持窗口运行..."
fi
EOF
chmod +x "$HOST_DIR/一键启动-全栈服务(主机+客户端1001).command"

# 7.5 写入主机仅后台启动脚本 (macOS)
cat > "$HOST_DIR/一键启动-仅主机服务.command" << 'EOF'
#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "===================================================================="
echo "  Java SIP 课设主机服务一体化启动程序 (电脑 A - 仅后台服务)"
echo "===================================================================="

LAN_IP=""
if [[ -x "$DIR/server/detect-lan-ip.sh" ]]; then
  LAN_IP="$("$DIR/server/detect-lan-ip.sh" 2>/dev/null || true)"
fi
if [[ -z "$LAN_IP" ]]; then
  LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")"
fi
echo "[INFO] 探测到本机局域网 IP: $LAN_IP"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "[ERROR] Docker 未运行，请先启动 Docker Desktop！"
  read -r -p "按回车键退出..."
  exit 1
fi

echo "[INFO] 正在启动 Kamailio 与 FreeSWITCH Docker 容器..."
(cd "$DIR/server" && {
  if [[ -x "./start-kamailio.sh" ]]; then
    ./start-kamailio.sh || docker compose --profile mcu up -d
  else
    docker compose --profile mcu up -d
  fi
})

JAVA_EXEC="$DIR/jre/bin/java"
if [[ ! -x "$JAVA_EXEC" ]]; then
  JAVA_EXEC="java"
fi

SPRING_PORT="8080"
if lsof -i :8080 >/dev/null 2>&1; then
  if curl -s -m 1 "http://127.0.0.1:8080/api/health" 2>/dev/null | grep -q '"status"'; then
    echo "[INFO] 检测到 Spring Boot 后台已在端口 8080 正常运行。"
  else
    SPRING_PORT="8088"
  fi
fi

if ! curl -s -m 1 "http://127.0.0.1:$SPRING_PORT/api/health" 2>/dev/null | grep -q '"status"'; then
  echo "[INFO] 启动 Spring Boot 后台服务 (端口: $SPRING_PORT)..."
  mkdir -p "$DIR/logs"
  nohup "$JAVA_EXEC" -jar "$DIR/lib/sipim-admin.jar" --server.port="$SPRING_PORT" > "$DIR/logs/admin.log" 2>&1 &
  echo $! > "$DIR/.admin.pid"
fi

echo ""
echo "===================================================================="
echo "  主机服务已全部就绪！"
echo "  - 本机局域网 IP: $LAN_IP"
echo "  - 后台管理页面: http://$LAN_IP:$SPRING_PORT"
echo "===================================================================="
read -r -p "主机后台已在运行，按回车键保持窗口运行..."
EOF
chmod +x "$HOST_DIR/一键启动-仅主机服务.command"

# 7.6 写入主机一键停止脚本 (macOS)
cat > "$HOST_DIR/停止-主机服务.command" << 'EOF'
#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "===================================================================="
echo "  正在停止 Java SIP 主机服务..."
echo "===================================================================="

if [[ -f "$DIR/.admin.pid" ]]; then
  PID=$(cat "$DIR/.admin.pid" 2>/dev/null || true)
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    echo "[INFO] 停止 Spring Boot 进程 (PID: $PID)..."
    kill "$PID" 2>/dev/null || true
    rm -f "$DIR/.admin.pid"
  fi
fi

for P in 8080 8088; do
  PIDS=$(lsof -ti :"$P" 2>/dev/null || true)
  if [[ -n "$PIDS" ]]; then
    for PID in $PIDS; do
      CMD=$(ps -p "$PID" -o comm= 2>/dev/null || true)
      if [[ "$CMD" == *"java"* ]]; then
        kill "$PID" 2>/dev/null || true
      fi
    done
  fi
done

if command -v docker >/dev/null 2>&1; then
  echo "[INFO] 停止 Docker 容器 (Kamailio + FreeSWITCH)..."
  (cd "$DIR/server" && docker compose --profile mcu down >/dev/null 2>&1 || true)
fi

echo "[INFO] 主机服务已全部停止。"
EOF
chmod +x "$HOST_DIR/停止-主机服务.command"

# 7.7 写入主机全栈启动批处理 (Windows)
cat > "$HOST_DIR/一键启动-全栈服务(主机+客户端1001).bat" << 'EOF'
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "PATH=%~dp0native;%PATH%"

echo ====================================================================
echo   Java SIP 课设全栈一体化服务 (Windows 电脑 A: 容器 + 后台 + 客户端 1001)
echo ====================================================================

rem 1. 探测本机真实局域网 IP
set "LAN_IP=127.0.0.1"
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4 Address" /c:"IPv4 地址"') do (
    set "TMP_IP=%%a"
    set "TMP_IP=!TMP_IP: =!"
    if not "!TMP_IP!"=="" if not "!TMP_IP:~0,3!"=="127" (
        set "LAN_IP=!TMP_IP!"
        goto :found_lan_ip
    )
)
:found_lan_ip
echo [INFO] 探测到本机局域网 IP: %LAN_IP%

rem 2. 检查 Docker 状态
where docker >nul 2>nul
if %ERRORLEVEL% equ 0 (
    docker info >nul 2>nul
    if !ERRORLEVEL! equ 0 (
        echo [INFO] 正在启动 Kamailio 与 FreeSWITCH Docker 容器...
        if exist "%~dp0server\start-kamailio.ps1" (
            powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\start-kamailio.ps1"
        ) else (
            cd /d "%~dp0server"
            docker compose --profile mcu up -d
            cd /d "%~dp0"
        )
    ) else (
        echo [ERROR] Docker 引擎未运行！请先打开 Docker Desktop 并等待就绪。
        pause
        exit /b 1
    )
) else (
    echo [ERROR] 未检测到 Docker 命令行工具，请先安装 Docker Desktop。
    pause
    exit /b 1
)

rem 3. 寻找 Java 运行时
set "JAVA_CMD="
if exist "%~dp0jre\bin\java.exe" (
    set "JAVA_CMD=%~dp0jre\bin\java.exe"
) else (
    where java >nul 2>nul
    if !ERRORLEVEL! equ 0 set "JAVA_CMD=java"
)
if "%JAVA_CMD%"=="" (
    echo [ERROR] 未检测到 Java 运行环境，请确保本目录包含 jre\ 或本机已安装 Java 17。
    pause
    exit /b 1
)

rem 4. 检查并拉起 Spring Boot 后台 Jar
set "SPRING_PORT=8080"
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>nul
if not errorlevel 1 (
    curl -s -m 1 http://127.0.0.1:8080/api/health | findstr "status" >nul 2>nul
    if not errorlevel 1 (
        echo [INFO] 检测到 Spring Boot 后台已在端口 8080 正常运行。
    ) else (
        echo [WARN] 端口 8080 被占用，切换至备用端口 8088...
        set "SPRING_PORT=8088"
    )
)

if not "%SPRING_PORT%"=="8080" (
    netstat -ano | findstr ":8088" | findstr "LISTENING" >nul 2>nul
    if errorlevel 1 goto :start_boot
) else (
    netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>nul
    if errorlevel 1 goto :start_boot
)
goto :boot_ready

:start_boot
echo [INFO] 正在启动 Spring Boot 后台服务 (端口: %SPRING_PORT%)...
if not exist "%~dp0logs" mkdir "%~dp0logs"
powershell -NoProfile -Command "$proc = Start-Process '%JAVA_CMD%' -ArgumentList '-jar \"%~dp0lib\sipim-admin.jar\" --server.port=%SPRING_PORT%' -PassThru -WindowStyle Hidden; $proc.Id | Set-Content -Path '%~dp0.admin.pid' -Encoding ascii"
echo [INFO] 等待后台就绪...
timeout /t 3 /nobreak >nul

:boot_ready
echo.
echo ====================================================================
echo   主机服务已启动！
echo   - 本机局域网 IP: %LAN_IP%
echo   - 后台管理地址: http://%LAN_IP%:%SPRING_PORT%
echo   - 电脑 B 输入此 IP [%LAN_IP%] 即可直接连入！
echo ====================================================================

rem 5. 自动拉起电脑 A 客户端 1001
if exist "%~dp0lib\sip-client.jar" (
    echo [INFO] 正在拉起电脑 A 本机客户端 (用户 1001)...
    "%JAVA_CMD%" -Dsip.native.dir="%~dp0native" -jar "%~dp0lib\sip-client.jar" --server="127.0.0.1" --user="1001" --port="5061" --http="http://127.0.0.1:%SPRING_PORT%" --peer="1002"
) else (
    pause
)
EOF

# 7.8 写入主机停止批处理 (Windows)
cat > "$HOST_DIR/停止-主机服务.bat" << 'EOF'
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo [INFO] 正在停止 Spring Boot 后台服务...
if exist "%~dp0.admin.pid" (
    set /p PID=<"%~dp0.admin.pid"
    if defined PID taskkill /F /PID !PID! >nul 2>nul
    del "%~dp0.admin.pid" >nul 2>nul
)

echo [INFO] 正在停止 Docker 容器...
where docker >nul 2>nul
if %ERRORLEVEL% equ 0 (
    cd /d "%~dp0server"
    docker compose --profile mcu down >nul 2>nul
)
echo [INFO] 主机服务已停止。
pause
EOF

# 7.9 写入主机使用说明
cat > "$HOST_DIR/主机使用说明.txt" << 'EOF'
================================================================================
  Java SIP 协同主机一体化服务 (电脑 A 运行包) - 阶段 6 说明
================================================================================

【特性】
1. 一体化全栈交付：电脑 A 无需解压两份包，主机包直接内嵌客户端 1001，双击即可拉起整套系统；
2. 架构拓扑：电脑 A 承担信令服务器 (Kamailio 5060)、MCU 混流引擎 (FreeSWITCH 5080/8021) 与 Spring Boot 管理中枢；
3. 开箱即用：内置定制精简 JRE 17 与客户端原生动态库，免安装 JDK、免配置环境变量。

【使用步骤】
1. 双击运行【一键启动-全栈服务(主机+客户端1001).command】（Windows 运行 .bat）；
2. 脚本将自动检查 Docker 状态、拉起容器、秒级启动后台服务，并自动打开电脑 A 的 1001 客户端界面；
3. 查看终端中打印的本机局域网 IP（例如 10.21.253.218）；
4. 电脑 B（分机/老师电脑）解压客户端包后，在【启动客户端-指定服务器IP】中输入该 IP 即可立即连入！

【停止服务】
- 双击运行【停止-主机服务.command】即可一次性安全关闭后台进程与 Docker 容器。
EOF
cp "$HOST_DIR/一键启动-全栈服务(主机+客户端1001).command" "$HOST_DIR/start-host-fullstack.command"
cp "$HOST_DIR/一键启动-仅主机服务.command" "$HOST_DIR/start-host-only.command"
cp "$HOST_DIR/停止-主机服务.command" "$HOST_DIR/stop-host.command"
cp "$HOST_DIR/一键启动-全栈服务(主机+客户端1001).bat" "$HOST_DIR/start-host-fullstack.bat"
cp "$HOST_DIR/停止-主机服务.bat" "$HOST_DIR/stop-host.bat"
cp "$HOST_DIR/主机使用说明.txt" "$HOST_DIR/README-Host.txt"
chmod +x "$HOST_DIR/start-host-fullstack.command" "$HOST_DIR/start-host-only.command" "$HOST_DIR/stop-host.command"

# 8. 打包成 ZIP 发布归档 (带真实组号规范命名)
echo "[8/8] 正在压缩打包 ZIP 交付文件..."
(cd "$DIST_DIR" && zip -q -r "JavaSIP-Client-macOS-arm64.zip" "JavaSIP-Client-macOS-arm64")
(cd "$DIST_DIR" && zip -q -r "JavaSIP-Client-Windows-x64.zip" "JavaSIP-Client-Windows-x64")
(cd "$DIST_DIR" && zip -q -r "JavaSIP-Host-Server.zip" "JavaSIP-Host-Server")

# 规范命名拷贝 (符合课设文档【第01组-实践阶段二】约定)
cp "$DIST_DIR/JavaSIP-Client-macOS-arm64.zip" "$DIST_DIR/${GROUP_NAME}-实践阶段二-macOS-arm64.zip"
cp "$DIST_DIR/JavaSIP-Client-Windows-x64.zip" "$DIST_DIR/${GROUP_NAME}-实践阶段二-Windows-x64.zip"
cp "$DIST_DIR/JavaSIP-Host-Server.zip" "$DIST_DIR/${GROUP_NAME}-实践阶段二-完整主机服务.zip"

echo "===================================================================="
echo "  绿色便携包构建完成！输出目录: $DIST_DIR"
ls -lh "$DIST_DIR"/*.zip
echo "===================================================================="
