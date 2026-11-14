@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "REPO=%~dp0.."
set "PATH=%REPO%\native\windows-x64;%PATH%"

echo ============================================================================
echo   Java SIP 客户端启动程序 (Windows)
echo   模式：纯客户端模式（无需本地 Docker / 无需本地后台服务）
echo ============================================================================

set "REMOTE_SERVER="
set "EXTRA_ARGS=%*"

rem 如果传入了参数且第一个参数类似 IP 地址
if not "%~1"=="" (
    set "FIRST_ARG=%~1"
    rem 简单判断是否不以 -- 开头
    echo !FIRST_ARG! | findstr "^--" >nul
    if errorlevel 1 (
        set "REMOTE_SERVER=!FIRST_ARG!"
        rem 移除第一个参数后保存其余参数
        for /f "tokens=1* delims= " %%a in ("%*") do set "EXTRA_ARGS=%%b"
    )
)

rem 如果未传 IP 参数，提示用户输入目标服务器 IP
if "%REMOTE_SERVER%"=="" (
    set "SAVED_IP=10.21.247.82"
    if exist "%REPO%\client\.local-prefs" (
        for /f "tokens=2 delims==" %%i in ('findstr "^sip.server=" "%REPO%\client\.local-prefs" 2^>nul') do (
            set "TRIM_IP=%%i"
            set "TRIM_IP=!TRIM_IP: =!"
            if not "!TRIM_IP!"=="" if not "!TRIM_IP!"=="127.0.0.1" if not "!TRIM_IP!"=="localhost" set "SAVED_IP=!TRIM_IP!"
        )
    )
    echo 提示：直接回车将连接目标服务器 [!SAVED_IP!]
    echo       或输入对方主机 IP（可带端口如 10.21.x.x:8080）并按回车：
    echo.
    set "INPUT_IP="
    set /p "INPUT_IP=目标主机 IP [!SAVED_IP!]: "
    if "!INPUT_IP!"=="" set "INPUT_IP=!SAVED_IP!"
    set "REMOTE_SERVER=!INPUT_IP!"
)

rem 组装启动参数
set "FINAL_ARGS="
if not "%REMOTE_SERVER%"=="" (
    for /f "tokens=1,2 delims=:" %%a in ("%REMOTE_SERVER%") do (
        set "SERVER_HOST=%%a"
        set "HTTP_PORT=%%b"
    )
    if "!HTTP_PORT!"=="" set "HTTP_PORT=8080"
    
    echo !EXTRA_ARGS! | findstr /i "\-\-server=" >nul
    if errorlevel 1 set "FINAL_ARGS=--server=!SERVER_HOST!"
    
    echo !EXTRA_ARGS! | findstr /i "\-\-http=" >nul
    if errorlevel 1 set "FINAL_ARGS=!FINAL_ARGS! --http=http://!SERVER_HOST!:!HTTP_PORT!"
)

if not "%EXTRA_ARGS%"=="" set "FINAL_ARGS=!FINAL_ARGS! %EXTRA_ARGS%"

echo !FINAL_ARGS! | findstr /i "\-\-port=" >nul
if errorlevel 1 set "FINAL_ARGS=!FINAL_ARGS! --port=5062"

echo.
echo [INFO] 正在拉起 JavaFX 客户端...
if not "!FINAL_ARGS!"=="" echo [INFO] 连接参数: !FINAL_ARGS!
echo ============================================================================

cd /d "%REPO%\client"
where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn -q javafx:run -Djavafx.args="!FINAL_ARGS!"
) else (
    if exist "%REPO%\client\target\sip-client-0.1.0-all.jar" (
        java -Dsip.native.dir="%REPO%\native\windows-x64" -jar "%REPO%\client\target\sip-client-0.1.0-all.jar" !FINAL_ARGS!
    ) else (
        echo [ERROR] 未找到 mvn 命令且 target\sip-client-0.1.0-all.jar 不存在。
        pause
    )
)
