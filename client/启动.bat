@echo off
setlocal
cd /d "%~dp0"
set "REPO=%~dp0.."
set "PATH=%REPO%\native\windows-x64;%PATH%"
if "%~1"=="" (
  mvn -q javafx:run
) else (
  mvn -q javafx:run -Djavafx.args="%*"
)
if errorlevel 1 (
  echo 启动失败。请确认已安装 JDK 17、Maven，并已按 native\windows-x64\BUILD.md 编好 pjsua2.dll
  pause
)
