# 视频流畅度调整与验收

2026-10-30 更新：本轮完成离线代码优化、完整回归、原生媒体参数读回、纯编码基准及 Windows 包内 JRE 启动验证。
按用户要求，不进行双机反复通话与入会验收；没有真实网络 RTP、实际摄像头帧率或端到端音频延迟数据。目标参数、合成基准和实际媒体体验分别报告。

## 当前媒体档位

| JVM 属性 `sip.media.profile` | 发送尺寸 | 目标帧率 | 平均 / 最高编码码率 | 音频缓冲调整 |
| --- | --- | ---: | --- | --- |
| balanced（默认） | 848×480 | 25fps | 1.2 / 1.8Mbps | 开 |
| motion | 640×360 | 30fps | 0.9 / 1.4Mbps | 开 |
| legacy | 640×360 | 15fps | 0.5 / 0.8Mbps | 保留旧原生默认 |

这是固定档位，不是自适应码率。当前 H.264 接收 fmtp 为 `42e01e`（Level 3.0），保持不变。
848×480 为 53×30=1590 个宏块，每秒 39750 个宏块，均在当前 SDK 的 1620 / 40500 限制内。
直接设为 1280×720/30fps 会超出该协商等级。实际编码器可能继续按对端能力降低参数。

通过 JVM 属性覆盖参数，无需改界面：`sip.video.width`、`height`、`fps`、`averageBps`、`maximumBps`、`mtu`（每项均带 `sip.video.` 前缀）。
必须是偶数宽高，并通过宏块和码率边界检查。MTU 默认 1200 字节，可设 600–1400；非法配置会整组回退 balanced 并记录警告。
例如：`java -Dsip.media.profile=legacy -jar client.jar`。Windows 新包可先 `set SIP_MEDIA_PROFILE=legacy` 再启动。

音频保留原生时钟、编解码器和回声消除，配置独立 RTP 接收队列及一个接收工作线程。
balanced/motion 使用 20ms 分帧、采集/播放各 40ms、音频抖动缓冲初始 40ms、最小 20ms、最大预取 80ms、上限 160ms。
这些是缓冲目标，不能相加后当成实测端到端延迟。可用 `sip.audio.captureMs`、`playbackMs`、`jitterInitialMs`、`jitterMinMs`、`jitterMaxPreMs`、`jitterMaxMs` 覆盖；属性完整前缀均为 `sip.audio.`。

视频接收独立配置为初始/最小预取 0ms、最大预取 80ms、上限 200ms，可用 `sip.video.jitterInitialMs`、`jitterMinMs`、`jitterMaxPreMs`、`jitterMaxMs` 覆盖（每项完整前缀均为 `sip.video.`）。最大预取至少为协商解码格式的一帧。
发送线程的 pacing 允许带宽为平均编码码率三倍与编码上限中的较大值，用于减少关键帧突发排队，不改变编码码率。

Windows 新原生库将解码最小延迟参数设为 40ms、优先最新已解码画面，并修复乱序时误移除下一帧首包的裁剪下标。
`SIP_VIDEO_LATENCY_MODE=legacy` 可恢复原生旧缓存策略；这是进程环境变量，重启客户端后生效，组帧错误修复仍保留。新启动器选择 legacy 媒体档时自动补上该变量，除非用户已指定。
24 组合成接收测试中，15fps 缓冲滞后从 200ms 降至 66.667ms，25fps 从 160–200ms 降至 80ms；这不是整通电话的延迟。
方法、乱序回归和边界见 [延迟补充报告](reports/media-latency-20261030.md)。

## 性能与诊断实现

- 视频设备/编码器能力采用 5 秒不可变快照，启动、关闭及显式预览前失效；相同快照内 10000 次查询只加载一次。失效刷新统一先取引擎锁，避免缓存锁与引擎锁顺序相反。
- 枚举获得的原生编码器向量和设备信息在 finally 中释放。
- 可选 `UI_LOG_FILE` 写入由独立后台线程执行，队列最多 256 条、单条最多 32768 字符；过载丢弃诊断日志并记丢弃数，不阻塞原生回调。
- BYE 最终响应先通知挂断观察器，再转发界面日志。
- 接通后每 5 秒读取各活动流 RTCP/JitterBuffer 统计，首样本只标 warmup；挂断和销毁前停止采样。原生 Call 释放与统计采样使用独立生命周期锁。

日志示意：`audio codec=PCMU negotiated=8000Hz interval=5.00s tx=64.0kbps rx=64.0kbps rxLoss=0.00% jitterMean=2.50ms rttMean=12.00ms jbuf=40ms discarded=0`。
tx/rx 为采样间隔内的 RTP 字节差换算，丢包率分母为收到包增量加丢包增量；jitterMean/rttMean 是原生统计累计均值，jbuf 为原生抖动缓冲平均延迟，不是当前端到端排队时间。
视频中的 `fps(target)` 是协商发送目标，RTP 包数和码率均不是解码/显示帧率。`-Dsip.media.statisticsSeconds=0` 关闭统计，可设置 2–60 秒。

纯编码基准和当前测试数见 [本轮报告](reports/media-backend-optimization-20261030.md)，运行入口为 `scripts/benchmark-media.ps1`。

## 保留的早期优化

- 早期的 640×360、15fps、500kbps、峰值 800kbps 现作为 legacy 回退档，默认参数以上表为准，最大 payload 默认 1200 字节。
  不禁用其他编码器，不覆盖 H.264 fmtp；原生后端或协商可能调整这些目标。
- H.264 优先以匹配现有 MCU。设置失败逐编码器记录日志，其他编码器继续处理。
- 使用独立发送线程进行 RTP 节奏控制，避免发送限速阻塞摄像头采集线程。
- 配置 RTCP NACK PLI 能力和开流时 3 个间隔 1 秒的关键帧。PLI 是请求关键帧，
  不代表已实现 RTP 重传、FEC 或拥塞控制；需要对端协商支持。
- 会议室混流改为 960×540、15fps、1mb，保持 3×3 和 8 人上限。
  单格约 320×180；这是用清晰度和帧率换取较低的编码/解码负载。
  部分 PJSIP OpenH264 构建默认最大接收宽度为 1200，原先 1280 宽画布存在兼容风险；
  本机 2.17 源码的 OpenH264 最大接收尺寸为 1200×800；本轮原生参数测试保持既有解码参数，未验证真实混流解码。
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

## 后续双机验收（本轮明确不执行）

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
- [PJSUA2 媒体配置与原生统计](https://docs.pjsip.org/en/latest/api/generated/pjsip/group/group__PJSUA2__MED.html)
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
