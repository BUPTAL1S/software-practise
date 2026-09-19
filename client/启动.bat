@echo off
setlocal
cd /d "%~dp0"
set "REPO=%~dp0.."
set "JAVA_HOME=%JAVA_HOME%"
set "PATH=%REPO%\native\windows-x64;%PATH%"
mvn -q javafx:run
if errorlevel 1 (
  echo 启动失败。请确认已安装 JDK 17、Maven，并已按 native\windows-x64\BUILD.md 编好 pjsua2.dll
  pause
)
