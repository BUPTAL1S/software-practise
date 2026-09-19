# 在 Windows x64 上编译 pjsua2.dll

本机是 Windows 时按此做。不要把 `.dll` 提交进 git。PJSIP **必须 2.17**，与 Mac 同一 tag。阶段 1 只开音频。

官方说明：https://docs.pjsip.org/en/latest/pjsua2/building.html

## 准备

- JDK 17（`JAVA_HOME` 指向它）
- [SWIG](https://www.swig.org/download.html) 加到 PATH
- MSYS2 + MinGW-w64（官方推荐 Windows 按 Unix 方式编）

## 步骤

在 MSYS2 MinGW64 终端：

```bash
pacman -S --needed git make mingw-w64-x86_64-gcc swig
git clone --branch 2.17 --depth 1 https://github.com/pjsip/pjproject.git "$HOME/src/pjproject"
cd "$HOME/src/pjproject"
cat > user.mak << 'EOF'
export CFLAGS += -fPIC
EOF
./configure --disable-video --disable-libwebrtc
make dep
make
cd pjsip-apps/src/swig/java
make
```

把生成的 `pjsua2.dll`（以及同目录依赖的 `libgcc` / `libstdc++` / `libwinpthread` 等，若有）拷到本目录：

```text
native/windows-x64/pjsua2.dll
```

SWIG 生成的 Java 若与仓库 `client/src/main/java/org/pjsip/pjsua2/` 不一致，用 2.17 重新生成后覆盖那一份（Win/Mac 共用，不要各改各的）。

## 启动

仓库根目录下 `native/windows-x64` 已有 dll 后，在 `client/` 双击 `启动.bat`，或：

```bat
cd client
mvn javafx:run
```

`NativeLoader` 用绝对路径 `System.load` 加载 `pjsua2.dll`。若仍报缺依赖，把 MinGW 的 `bin` 加到 PATH，或把缺的 dll 和 `pjsua2.dll` 放在同一目录。
