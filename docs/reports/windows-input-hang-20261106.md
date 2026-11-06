# Windows 视频挂断后输入失效：2026-11-06

## 现象与现场证据

1002 使用 Windows 便携客户端 `5d91dc8f8f37`，与协作者 1001 进行语音通话、
开启摄像头，挂断后主界面持续无法点击；后续来电仍能显示窗口，但也不能点击。
此次没有使用 computer use，没有在本机启动或更改信令/MCU 服务。

故障进程 PID 4880 自 10:04:18 运行。10:08–10:18 保存的四次 Java 线程快照没有发现
Java 层死锁，挂断/预览工作线程已空闲，没有找到该进程的 `hs_err_pid` 或 Windows
应用崩溃事件。JavaFX 的只读诊断任务在 10:18:28 入队后立即执行：主窗口
`disableCount=0`、`modality=NONE`，场景根和主要按钮没有禁用，视图仍有效。
这些结果不能证明所有原生线程正常，但可排除本次的 JVM 已退出、JavaFX 队列阻塞和
整窗禁用。一次沙箱内进程查询看不到实际用户会话，后续已在实际会话确认进程仍在，
没有将该查询误判继续作为“程序退出”的证据。

原生线程 8656 与 JavaFX 线程 14760 的 `GetGUIThreadInfo` 都报告主窗口
`0x5094e` 为 active/focus；原生栈为 `NtWaitForSingleObject → WaitForSingleObjectEx`
→ `pj_sem_wait_for` → `sdl_dev.c:job_thread`。SDK 自带符号对应 DLL 偏移
`0x2786ae` / `0x172f52`。源码显示 `sdl_ev_thread` 在流列表为空时无限等待
`sf->sem`，不再向 SDL owner thread 投递 `handle_event`。

跨线程父子窗口会共享输入队列，一个线程不再取输入消息时可影响另一线程的输入，
而应用任务仍能运行。机制参见微软的
[共享输入队列说明](https://devblogs.microsoft.com/oldnewthing/20130607-00/?p=4143/)。

## 现场对照

10:24:37–10:26:37，在原故障进程内短暂启动一个隐藏 Colorbar 预览，使 SDL 恢复处理
消息。没有重启客户端，没有开启物理摄像头，没有发起 SIP 通话或发送媒体。
用户在这段时间明确反馈“恢复了，能正常点击”。合成预览随后自动停止并释放。
这是定位对照，不是产品中的保活方式；当前进程加载的旧 DLL 仍可能再次触发问题。

## 修复与回归

新增 `video-idle-events-2.17.patch`：Windows 的 SDL 事件线程在无视频流时也按现有
50 ms 间隔处理事件。macOS/其他平台的空闲逻辑不变。Windows 原生构建链应用第四份
补丁；打包链验证 `SDL idle event policy: pump-without-streams` 标记，阻止旧 DLL
混入新包。没有改动 UI、SIP 协议或服务端配置。

本地回归 `SdlIdleEventIntegrationTest` 通过 test-only JNI 观察同进程 SDL 线程是否
消费私有 `WM_APP` 消息，不模拟或拦截用户键鼠输入。测试使用隐藏 Colorbar，显式连接
本地视频端口使会议时钟处理异步移除，并确认 HWND 真正销毁后才检查空闲消息。
仅停止隐藏预览但不确认销毁会留下待释放端口，造成错误的通过结果，本测试已覆盖该条件。

| 检查 | 结果 |
|---|---|
| 旧 DLL `fb3c77d2…636b4` | 活动流检查通过；第 1 轮窗口销毁后的空闲检查失败 |
| 新 DLL `afefbe37…bb9f2` | 5 轮活动与空闲消息检查均通过，0 个跳过 |
| 新 DLL Endpoint 销毁和测试子进程退出 | 通过 |
| 2 秒空闲 CPU 采样 | 进程 CPU 增量 0 ms；受计时粒度限制，不等于零开销 |
| 四份 SDL 补丁从锁定上游逐个应用 | 通过，与构建源文件逐字一致 |

新 `pjsua2.dll` SHA-256：
`afefbe37341d24af375b659de65a24d4bce06e195efec954559907fbfbfbb9f2`。
上游基线为 PJPROJECT `5a457451fa2712ba18e12b01738e8ff3af2b26fd`。

原始现场和对照日志保存在执行机器的
`D:\work\.codex-execution\joint-test-20261106\hang-4880`，不将进程快照、账号数据或
二进制提交仓库。正式验收仍需在新包上重做双方挂断、语音升级视频后挂断、后续来电与
重新呼叫；本次本地回归及旧进程恢复，不替代新包双机验收。
