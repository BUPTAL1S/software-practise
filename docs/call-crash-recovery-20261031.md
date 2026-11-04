# Windows 通话闪退与信令回程修复（2026-10-31）

适用范围：Windows 源码客户端、PJSIP 2.17、Kamailio 5.8。后台接口、账号和数据库没有变更。
本轮修改了客户端原生调用和 Kamailio 路由配置；Windows 的 `callwindow.dll` 也必须随源码一起更新。

## 已复现的问题与修复

1. 摄像头头像控制消息使用独立线程发送。`SipService.sendMessage()` 未注册 pjlib 线程，
   `IncomingCallWorkerTest#cameraControlMessageCanUseAFreshThread` 在修复前触发历史日志中的同一断言：
   `Calling pjlib from unknown/external thread`，进程退出码 3。
   现在消息入口先注册原生线程；注册失败抛出明确异常，不再吞掉失败后继续调用 JNI。

2. JavaFX 清理任务积压时，关闭引擎只删除最近一通电话，旧 Call 留在队列中。
   `shutdownDrainsCallsEvenWhenFxCleanupIsDelayed` 连续建立三通音频会话、阻塞 UI 清理、
   关闭引擎再释放 UI，旧版稳定触发 `pjsua_call.c` 的 `call_id < max_calls` 断言（退出码 3）。
   现在显式持有所有 Call，关闭引擎前逐一清理；延后的重复删除成为空操作。
   关闭阶段拒绝新的拨号、消息、接听和来电包装，媒体回调不会再次创建视频窗口。

3. Windows 原生窗口嵌入函数返回 void，遇到无效 HWND 直接返回，Java 仍认为成功并缓存。
   `WindowLayoutNativeTest#invalidWindowsAreNotCachedAsSuccessfullyAttached` 旧版失败。
   现在 Windows JNI 返回实际结果，失败保留重试能力。标题通过有终止符的 UTF-16 副本查找，
   只查找当前进程窗口，避免越界读取或误找到另一个客户端的同名窗口。
   保留旧 JNI 入口供旧 Java 调用方使用；macOS 接口不变。

4. Kamailio 只保存 Contact，没有记录实际注册连接的回程地址。
   隔离测试让两个 TCP 客户端注册不可达 Contact：旧版注册 200 成功，但被叫收不到 INVITE。
   现在通过 `received_avp` / `fix_nated_register()` 保存实际来源，
   INVITE/应答携带 Contact alias，对话内 ACK/BYE 使用真实连接回程。

5. Docker 来源网关不能单独用于认定宿主机客户端。
   现在结合 Contact 主机识别；Record-Route 的被叫侧识别使用 Contact 而非 received 下一跳。
   MCU 回答只针对已标记的本机呼叫改成回环地址，模拟校园成员保留服务器媒体地址。

6. 挂断、拒接和通话结束后的预览停止统一排到摄像头串行任务队列，避免界面线程同步等待
   原生媒体锁。关闭协调器后不再向已关闭的队列提交预览停止任务；最终预览清理由 SIP shutdown 负责。
   媒体重新协商时不再隐藏可能已被 PJSIP 销毁的旧视频窗口。

## 回归

需要 JDK 17 和本机 PJSIP DLL。先退出占用摄像头的正式客户端。

```powershell
mvn -o -f client/pom.xml '-Dtest=IncomingCallWorkerTest,WindowLayoutNativeTest,CallPreviewLifecycleTest,CallConnectedNotificationTest,CallWindowAndLifecycleTest,NativeVideoPlacementTest,RoomJoinFeedbackTest,LogoutFlowTest,DirectorySyncTest,ChatDateSeparatorTest,CameraPreviewLifecycleTest#embeddedPreviewCanStopAndRestartTwentyTimes+cameraCanRestartOnUiThreadWithRemoteVideoStillEmbedded' -Dsip.nativeVideoTest=true test
python scripts/test-sip-routing.py
```

客户端首轮 29 项通过，0 失败、0 跳过；包含真实视频接听与呼出、摄像头状态消息、
延后清理、Unicode 标题，以及真实摄像头独立启停 20 次、视频会话内启停 20 次。
挂断队列调整后另跑通话、界面及硬件摄像头回归。

信令脚本需要 Python 3.10+、Docker 和已有 Kamailio 镜像，只在临时容器注册 `probe-*`。
它验证本机/模拟成员/混合方向四种对话的 INVITE/200/ACK、被叫 BYE/200，
一对一 SDP 原样透传，以及模拟 MCU 两侧的 SDP 地址改写。退出时删除临时容器；
诊断文件保存在 `client/target/sip-routing-*`。不访问正式后台或正式用户。

修复前后记录（本地生成，不提交日志）：

- `client/target/camera-control-red.log`、`message-worker-false.log`。
- `client/target/delayed-cleanup-red.log`、`cleanup-worker-false.log`。
- `client/target/window-layout-red.log`。
- `client/target/sip-routing-red.log`、`sip-routing-final.log`。
- `client/target/native-safety-final.log`、`call-teardown-final.log`。

## 生效和验证边界

更新 Java 源码和跟踪的 Windows 窗口 DLL 后重新编译启动客户端。
Kamailio 配置须重启服务生效；内存注册表会清空，客户端需要重新注册。
本轮无需修改或重新编译 `pjsua2.dll`，不需要重启后台和 FreeSWITCH。

模拟 SIP/MCU 对端不等同于第二台物理 Windows 的真实音视频收发。
本轮未验证成员电脑的摄像头驱动、Windows 防火墙、校园网 UDP 可达性或长时间双方画面。
一对一仍是直接 RTP；当前工作没有增加媒体中继。不能用上述通过结果宣称所有成员环境已验收。

实现依据：[Kamailio 5.8 nathelper 官方说明](https://www.kamailio.org/docs/modules/5.8.x/modules/nathelper.html)。
