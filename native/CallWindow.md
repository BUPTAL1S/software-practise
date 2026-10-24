# Native call-window helpers

The JavaFX call window uses two PJSIP native video windows:

- remote video window (PJSIP `VideoWindow`)
- local camera preview (`VideoPreview`) used as the PiP

`WindowLayout` loads a tiny JNI library to position the preview window over the
remote window's lower-right corner and make it borderless/always-on-top.

## Windows x64

```bat
set JAVA_HOME=C:\path\to\zulu-17
native\windows-x64\build-callwindow.bat
```

Produces `native/windows-x64/callwindow.dll`.

## macOS arm64

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./native/macos-arm64/build-callwindow.sh
```

Produces `native/macos-arm64/libcallwindow.dylib`.

If the helper library is absent, the client still runs; the local preview stays
a normal native window instead of being positioned as a PiP.
