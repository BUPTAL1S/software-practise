@echo off
setlocal
if "%JAVA_HOME%"=="" (
  echo JAVA_HOME is not set
  exit /b 1
)
gcc -shared -O2 -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" "%~dp0CallWindowWin.c" -o "%~dp0callwindow.dll" -luser32 -lgdi32
if errorlevel 1 exit /b 1
echo Built %~dp0callwindow.dll
