@echo off
rem ============================================================================
rem 在 Windows 上编译 winmirror.dll (x64)
rem 支持 MSYS2 MinGW-w64 (gcc) 或 Visual Studio (cl.exe)
rem ============================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

if "%JAVA_HOME%"=="" (
    echo [ERROR] 请先设置 JAVA_HOME 环境变量指向 JDK 根目录 (如 C:\Program Files\Eclipse Adoptium\jdk-17)
    exit /b 1
)

where gcc >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo [INFO] 检测到 GCC，使用 MinGW-w64 编译 winmirror.dll...
    gcc -shared -O2 -s -static-libgcc ^
        -I"%JAVA_HOME%\include" ^
        -I"%JAVA_HOME%\include\win32" ^
        NativeMirrorWin.c ^
        -o winmirror.dll ^
        -luser32
    if %ERRORLEVEL% equ 0 (
        echo [SUCCESS] winmirror.dll 编译成功！
        exit /b 0
    )
)

where cl >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo [INFO] 检测到 MSVC，使用 cl.exe 编译 winmirror.dll...
    cl /LD /O2 /W3 ^
        /I"%JAVA_HOME%\include" ^
        /I"%JAVA_HOME%\include\win32" ^
        NativeMirrorWin.c ^
        /Fe:winmirror.dll ^
        user32.lib
    if %ERRORLEVEL% equ 0 (
        del *.obj *.exp *.lib 2>nul
        echo [SUCCESS] winmirror.dll 编译成功！
        exit /b 0
    )
)

echo [ERROR] 未找到 gcc 或 cl.exe 编译器。请在 MSYS2 MinGW64 终端或 VS Native Tools Command Prompt 中运行。
exit /b 1
