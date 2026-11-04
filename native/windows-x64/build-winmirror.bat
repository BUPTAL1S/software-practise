@echo off
setlocal enabledelayedexpansion
if "%JAVA_HOME%"=="" (
  echo ERROR: Set JAVA_HOME to a Windows x64 JDK.
  exit /b 1
)
if not defined SDL_INCLUDE_DIR set "SDL_INCLUDE_DIR=C:\msys64\mingw64\include"
if not exist "%SDL_INCLUDE_DIR%\SDL2\SDL.h" (
  echo ERROR: Set SDL_INCLUDE_DIR to the directory containing SDL2\SDL.h.
  exit /b 1
)
where gcc >nul 2>nul
if errorlevel 1 (
  echo ERROR: Run with MinGW-w64 gcc on PATH.
  exit /b 1
)
gcc -shared -O2 -s -static-libgcc -I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32" -I"%SDL_INCLUDE_DIR%" "%~dp0NativeMirrorWin.c" -o "%~dp0winmirror.dll" -luser32
if errorlevel 1 exit /b 1
echo Built %~dp0winmirror.dll
