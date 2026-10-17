@echo off
rem ============================================================================
rem 停止 Spring Boot 后台服务 (Windows)
rem ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo [INFO] 正在查找 Spring Boot 后台服务进程...
set "STOPPED=0"

rem 1. 优先按记录的 PID 文件终止后台进程树
if exist "admin\.admin.pid" (
    set /p ADMIN_PID=<"admin\.admin.pid"
    if not "!ADMIN_PID!"=="" (
        rem 验证进程是否仍在运行
        tasklist /FI "PID eq !ADMIN_PID!" 2>nul | findstr /i "!ADMIN_PID!" >nul 2>nul
        if not errorlevel 1 (
            echo [INFO] 终止记录的后台进程及其子进程树 (PID: !ADMIN_PID!)...
            taskkill /F /T /PID !ADMIN_PID! >nul 2>nul
            set "STOPPED=1"
        )
        del "admin\.admin.pid" 2>nul
    )
)

rem 2. 按端口检查残留服务，但严格限制仅终止 Java 进程 (java.exe / javaw.exe)
for %%P in (8088 8081 8082 8083) do (
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%%P" ^| findstr "LISTENING"') do (
        set "PID_FOUND=%%a"
        if not "!PID_FOUND!"=="" (
            for /f "tokens=1" %%b in ('tasklist /FI "PID eq !PID_FOUND!" /NH 2^>nul') do (
                set "PROC_NAME=%%b"
                if /i "!PROC_NAME!"=="java.exe" (
                    echo [INFO] 终止端口 %%P 上的 Java 服务 (PID: !PID_FOUND!)...
                    taskkill /F /T /PID !PID_FOUND! >nul 2>nul
                    set "STOPPED=1"
                ) else if /i "!PROC_NAME!"=="javaw.exe" (
                    echo [INFO] 终止端口 %%P 上的 Java 服务 (PID: !PID_FOUND!)...
                    taskkill /F /T /PID !PID_FOUND! >nul 2>nul
                    set "STOPPED=1"
                )
            )
        )
    )
)

if "!STOPPED!"=="1" (
    echo [INFO] Spring Boot 后台服务已停止。
) else (
    echo [INFO] 未发现正在运行的 Spring Boot 后台服务。
)
pause
