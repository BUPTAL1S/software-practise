@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "REPO=%~dp0.."
set "PATH=%REPO%\native\windows-x64;%PATH%"

echo ============================================================================
echo   Java SIP 客户端一体化启动程序 (Windows)
echo ============================================================================

rem 1. 检查 SIP 信令与 MCU 服务 (端口 5060)
netstat -ano | findstr ":5060" | findstr "LISTENING" >nul 2>nul
if errorlevel 1 (
    echo [INFO] 检测到 SIP 服务未在端口 5060 运行，正在检查 Docker 容器...
    where docker >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        if exist "%REPO%\server\start-kamailio.ps1" (
            echo [INFO] 正在拉起 Kamailio + FreeSWITCH 容器...
            powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\server\start-kamailio.ps1" >nul 2>nul
        )
    ) else (
        echo [WARN] 未检测到 Docker 命令，若需 SIP 注册与音视频通话，请确认 Docker 已启动。
    )
) else (
    echo [INFO] 检测到 SIP 服务 (Kamailio) 已在端口 5060 正常运行。
)

rem 2. 检查 Spring Boot 后台服务是否已运行 (严格检查 8088 8081 8082 8083，必须响应 {"status":"ok"})
set "SPRING_PORT="
for %%P in (8088 8081 8082 8083) do (
    netstat -ano | findstr ":%%P" | findstr "LISTENING" >nul 2>nul
    if not errorlevel 1 (
        powershell -NoProfile -Command "try { $r = (Invoke-WebRequest -Uri 'http://127.0.0.1:%%P/api/health' -TimeoutSec 1 -UseBasicParsing).Content; if ($r -match '\"status\"\s*:\s*\"ok\"') { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>nul
        if not errorlevel 1 (
            set "SPRING_PORT=%%P"
            echo [INFO] 检测到 Spring Boot 后台已在端口 %%P 运行并就绪。
            goto :after_spring_check
        )
    )
)

:after_spring_check
rem 3. 若未运行，后台启动 Spring Boot 并记录 PID 文件
if "%SPRING_PORT%"=="" (
    set "SPRING_PORT=8088"
    echo [INFO] 正在后台启动 Spring Boot 服务 (端口: 8088)...
    if not exist "%REPO%\admin\logs" mkdir "%REPO%\admin\logs"
    cd /d "%REPO%\admin"
    
    rem 启动后台进程并记录 PID
    powershell -NoProfile -Command "$proc = Start-Process cmd.exe -ArgumentList '/c mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=8088 > logs\admin.log 2>&1' -PassThru -WindowStyle Hidden; $proc.Id | Set-Content -Path '.admin.pid' -Encoding ascii"
    cd /d "%~dp0"
    
    echo [INFO] 等待后台服务就绪 (http://127.0.0.1:8088/api/health)...
    set /a "WAITED=0"
    :wait_loop
    powershell -NoProfile -Command "try { $r = (Invoke-WebRequest -Uri 'http://127.0.0.1:8088/api/health' -TimeoutSec 1 -UseBasicParsing).Content; if ($r -match '\"status\"\s*:\s*\"ok\"') { exit 0 } else { exit 1 } } catch { exit 1 }" >nul 2>nul
    if not errorlevel 1 (
        echo [INFO] Spring Boot 后台已就绪！
        rem 记录实际监听端口的 Java 进程 PID
        powershell -NoProfile -Command "$conn = Get-NetTCPConnection -LocalPort 8088 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1; if ($conn) { $conn.OwningProcess | Set-Content -Path '%REPO%\admin\.admin.pid' -Encoding ascii }" >nul 2>nul
        goto :launch_client
    )
    timeout /t 1 /nobreak >nul
    set /a "WAITED+=1"
    if !WAITED! lss 35 goto :wait_loop
    echo [WARN] 等待后台服务超时，客户端仍将启动并尝试自动重连。日志文件: admin\logs\admin.log
)

:launch_client
set "ARGS=%*"
if "%ARGS%"=="" (
    set "ARGS=--http=http://localhost:%SPRING_PORT% --port=5062"
) else (
    echo %ARGS% | findstr /i "\-\-http=" >nul || set "ARGS=--http=http://localhost:%SPRING_PORT% !ARGS!"
    echo %ARGS% | findstr /i "\-\-port=" >nul || set "ARGS=--port=5062 !ARGS!"
)

echo [INFO] 正在拉起 JavaFX 客户端 (参数: %ARGS%)...
mvn -q javafx:run -Djavafx.args="%ARGS%"
if errorlevel 1 (
    echo [ERROR] 客户端启动失败。请确认已安装 JDK 17 与 Maven，且 native\windows-x64 目录下存在相关原生库。
    pause
)
