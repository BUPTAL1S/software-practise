@echo off
setlocal EnableExtensions
cd /d "%~dp0"

call :ensure_jdk
if errorlevel 1 goto :fail
call :ensure_mvn
if errorlevel 1 goto :fail

echo 使用 JDK: %JAVA_HOME%
echo 请先运行 server\start-kamailio.ps1，后台才会连上 ESL 127.0.0.1:8021。
echo 管理页:   http://localhost:8080/  同 /admin.html
echo 健康检查: http://localhost:8080/api/health
echo 预置房间: room-101、room-102。登录密码与用户名相同，例如 1001 / 1001。
mvn spring-boot:run
if errorlevel 1 goto :fail
exit /b 0

:fail
echo 启动失败。请确认已安装 JDK 17 和 Maven，且 8080 端口空闲。
exit /b 1

:java_is_17
if not defined JAVA_HOME exit /b 1
if not exist "%JAVA_HOME%\bin\java.exe" exit /b 1
pushd "%JAVA_HOME%\bin"
.\java.exe -version 2>&1 | findstr /C:"version ""17." >nul
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%

:ensure_jdk
call :java_is_17
if not errorlevel 1 exit /b 0
if exist "%LOCALAPPDATA%\Programs\zulu-17\bin\java.exe" (
  set "JAVA_HOME=%LOCALAPPDATA%\Programs\zulu-17"
  set "PATH=%LOCALAPPDATA%\Programs\zulu-17\bin;%PATH%"
  exit /b 0
)
for /d %%D in ("%ProgramFiles%\Zulu\zulu-17*") do (
  if exist "%%~D\bin\java.exe" (
    set "JAVA_HOME=%%~D"
    set "PATH=%%~D\bin;%PATH%"
    exit /b 0
  )
)
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do (
  if exist "%%~D\bin\java.exe" (
    set "JAVA_HOME=%%~D"
    set "PATH=%%~D\bin;%PATH%"
    exit /b 0
  )
)
for /d %%D in ("%ProgramFiles%\Java\jdk-17*") do (
  if exist "%%~D\bin\java.exe" (
    set "JAVA_HOME=%%~D"
    set "PATH=%%~D\bin;%PATH%"
    exit /b 0
  )
)
echo 未找到 JDK 17。当前 JAVA_HOME=%JAVA_HOME%
exit /b 1

:ensure_mvn
where mvn >nul 2>&1
if not errorlevel 1 exit /b 0
if exist "%LOCALAPPDATA%\Programs\apache-maven\bin\mvn.cmd" (
  set "PATH=%LOCALAPPDATA%\Programs\apache-maven\bin;%PATH%"
  exit /b 0
)
echo 未找到 Maven。
exit /b 1
