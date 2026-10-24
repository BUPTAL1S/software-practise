# Native call-window helpers

The video call is a single JavaFX window. PJSIP still renders the remote video
and local camera preview into native windows, so `WindowLayout` loads a tiny JNI
library that:

1. finds the JavaFX call window by title;
2. reparents the PJSIP remote video window into the JavaFX video area;
3. reparents the camera preview window as the local PiP;
4. detaches both before the call window closes.

## Windows x64

```bat
set JAVA_HOME=C:\path\to\zulu-17
native\windows-x64\build-callwindow.bat
```

Produces `native/windows-x64/callwindow.dll`.

## macOS arm64 / x64

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./native/macos-arm64/build-callwindow.sh
```

Produces `native/macos-arm64/libcallwindow.dylib`. For Intel Macs use the same
source in `native/macos-x64/` and load `libcallwindow.dylib` from that directory.

If the helper library is absent, the client still runs; the remote video and
preview stay in separate native windows instead of being embedded in the single
JavaFX call window.
