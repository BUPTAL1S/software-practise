# 视频流畅度调整与验收

本次仅执行离线编译、窗口布局单元测试和配置解析，未启动客户端、后台或容器。
没有双机 RTP 统计或实测帧率，以下参数是保守默认值，不是带宽自适应或已完成的视频验收。

## 改动

- 客户端活动视频编码器发送目标设为 640×360、15fps、500kbps，峰值 800kbps，最大 payload 1200 字节。
  不禁用其他编码器，不覆盖 H.264 fmtp；原生后端或协商可能调整这些目标。
- H.264 优先以匹配现有 MCU。设置失败逐编码器记录日志，其他编码器继续处理。
- 使用独立发送线程进行 RTP 节奏控制，避免发送限速阻塞摄像头采集线程。
- 配置 RTCP NACK PLI 能力和开流时 3 个间隔 1 秒的关键帧。PLI 是请求关键帧，
  不代表已实现 RTP 重传、FEC 或拥塞控制；需要对端协商支持。
- 会议室混流改为 960×540、15fps、1mb，保持 3×3 和 8 人上限。
  单格约 320×180；这是用清晰度和帧率换取较低的编码/解码负载。
  部分 PJSIP OpenH264 构建默认最大接收宽度为 1200，原先 1280 宽画布存在兼容风险；
  尚未确认仓库 DLL 的具体编译上限。
- 会议室本地推流启动提前到远端窗口检查之前，避免没有远端窗口时跳过发送启动。
- 相同窗口句柄、区域和 DPI 下跳过重复 JNI 嵌入；窗口缩放、DPI、视频流或 PiP 变化仍重新布局。
  detach/关闭清除缓存；Java 层调用失败会重试。原生接口返回 void，无法确认 OS 是否实际完成嵌入。
- 摄像头启停参数对象在 finally 中释放，避免反复切换造成原生对象积累。

## 生效范围

客户端需要重新构建并由所有成员更新。会议室 XML 在下次创建容器进程时复制到容器配置，
只有在用户准备验收时再重启相关服务；仅热加载旧容器中的文件可能不会读取宿主机最新配置。
本次没有修改或重新编译 callwindow.dll。

## 离线检查

在仓库根目录执行（不会启动应用，也不加载 PJSIP 原生库）：

```text
mvn -o -f client/pom.xml -Dtest=NativeVideoPlacementTest test
```

4 个测试覆盖：连续 120 次相同布局只嵌入一次；尺寸/DPI/句柄/PiP 变化触发布局；
detach 后相同句柄仍会重新嵌入；调用失败不缓存成功状态。

## 后续双机验收

1. 两台 Windows 更新客户端，确认日志出现实际应用的编码参数。先测 1:1 双向视频 5 分钟，
   再测 3 人和 8 人会议。分别记录通话双方与服务端的 CPU、收发码率、帧率、丢包和抖动。
2. 检查每个成员日志中的本地推流启动结果；界面出现窗口不等于 RTP 已经收发。
3. 检查 SDP 的视频 c= 地址、m= 端口、H.264 fmtp 和 rtcp-fb；用抓包验证音频与视频 RTP
   分别有双向数据。服务端 21000–21099/UDP 映射与客户端防火墙都要可达。
4. 关/开摄像头、移动到不同 DPI 显示器、缩放窗口、仅语音接听和反复挂断各 20 次，
   检查是否黑屏、出现额外原生窗口、断言或持续内存增长。
5. 若没有任何视频 RTP，优先排查地址、防火墙和 NAT；降低码率不能修复不可达。
   若 RTP 持续到达但无画面，检查解码错误、关键帧请求/响应、分辨率和编码协商。
   若 CPU 饱和再降画布/帧率；若丢包明显，先排查无线网络和带宽。

## 依据

- [PJSIP 视频编码参数、发送限速与默认接收上限](https://docs.pjsip.org/en/latest/specific-guides/video/codec_params.html)
- [FreeSWITCH 会议画布、帧率及编码带宽](https://developer.signalwire.com/freeswitch/applications/conferencing/)

## 摄像头切换未响应修复（2026-10-28 后续验证）

独立硬件复现中，将真实 PJSIP 摄像头预览嵌入 JavaFX 后，在界面线程执行原来的
`stopPreview()`，线程停在 `VideoWindow.Show(false)`，12 秒内无法完成。仅增加
“解除嵌入”这一步，相同复现约 4 秒内正常退出。

修复在 `SipService.stopPreview()` 内统一先解除嵌入，再隐藏/停止/销毁预览。
摄像头按钮在停止预览前清除通话窗口中的 PiP 句柄，避免后续布局再次使用已销毁的窗口。
远端视频窗口由界面先解除嵌入；通话关闭阶段只丢弃 Java 包装，不再查询或隐藏可能正在销毁的原生窗口。
窗口占位文案集中按接通状态更新，接通后显示“等待对方视频画面”，不会再显示等待接听邀请。

同时修复了测试捕获到的退出互锁：销毁 SIP 引擎时不持有服务对象锁，允许工作线程完成
最后的 BYE 响应回调，避免销毁线程与回调相互等待。

硬件回归需显式启用，使用有摄像头的 Windows 与 JDK 17：

```text
mvn -o -f client/pom.xml -Dsip.nativeVideoTest=true -Dtest=CameraPreviewLifecycleTest,VideoCallPresentationTest,CallConnectedNotificationTest,NativeVideoPlacementTest test
```

已通过 9 项检查，包含真实摄像头嵌入预览启停 20 次、与本机模拟 SIP 对端建立视频会话后
切换摄像头 20 次、接通/头像切换后的文案、接通通知和布局回归。
模拟对端用于协商与应答，不发送实际远端视频，因此不代表双机收发视频画面或 MCU 已验收。

## 会议摄像头重开卡死、人数残留修复（2026-10-28）

用户再次报告会议中重开摄像头卡死后，捕获到真实客户端的 JavaFX 线程停在
`VideoPreview.start()`。上面的旧硬件测试在后台线程启动预览，没有覆盖真实按钮的
界面线程路径；仅解除嵌入不足以修复重开问题。

- 新增 `CallPreviewLifecycle`，串行执行预览创建和推流启停，JavaFX 继续处理原生窗口消息。
  切换前解除远端和 PiP 的嵌入，完成后重新获取句柄并嵌入；操作期间禁用重复摄像头操作。
- 关闭时先 `STOP_TRANSMIT`，再释放预览。通话和预览共享采集设备，顺序相反仍会出现等待。
- 界面状态刷新、PiP 定时器和会议表格渲染在原生操作期间跳过 SIP 查询，避免再次等待原生锁。
  操作完成后统一刷新；挂断和退出可排到原生操作之后执行。
- MCU 头像请求在独立串行任务中执行，等待后台实际结果，保持关闭/开启请求顺序，失败明确记录。
- 人数原来只靠 ESL 增量事件更新；后台断开、重启或漏收离会通知后，数据库可能保留旧人数。
  现在 ESL 连接成功时、以及连接期间每 5 秒读取 `conference xml_list`，按真实会议快照校正人数。
  仅应用完整有效快照，连接失败或数据不完整时保留原数，不会误清仍在开会的人数。
- ESL API 响应按 UTF-8 字节数读取，避免中文昵称使快照读取超时。

人数回归涵盖旧人数 5 自动恢复到 0、漏收离会通知恢复、中文昵称，以及错误响应不误清人数：

```text
mvn -o -f admin/pom.xml -Dtest=EslRoomCountRecoveryTest,EslEventListenerTest test
```

完整客户端的硬件回归使用专用 1098 账号及唯一 `room-camera-app-*` 房间，
通过实际摄像头按钮切换，保持会议列表刷新，并检查界面响应、预览句柄和接通状态。
需本机摄像头、Kamailio/FreeSWITCH、后台 8088 已运行，单次约两分钟：

```text
mvn -o -f client/pom.xml -Dsip.nativeVideoTest=true -Dsip.liveMcuTest=true -Dtest=CameraPreviewLifecycleTest#mainApplicationCanToggleCameraWithConferenceDirectoryRefreshing test
```

另有 `cameraCanToggleTwentyTimesInsideRealConference` 覆盖独立通话窗口接入实际 MCU 后，
反复切换摄像头和服务端头像。两者均使用真实摄像头，不等同于多台电脑画面流畅度验收。
