# 2026-10-30 媒体与后台离线优化报告

## 范围和版本

用户反馈一对一与会议均帧率低、清晰度低，要求全面检查媒体性能，同时优化后台和未完成功能，尽量不改前端。
本轮不做双机反复通话及入会验收；同学服务器故障后停止远端联调，不修改其 Docker。
代码基于 PR49 合并后的 main `ca65897c34aa074c34fb9630e28dbf67ef929ca1`，实现分支 `admin/phase6-readiness-20261030`，工作副本 `D:/work/.codex-execution/software-practise-phase6-20261030`。
原仓库准备分支及未提交材料保持原状，PR49 审查和探针已一并归档。

后台核心提交 `0277f84`、ESL 关闭补充 `31f1d7a`、媒体核心 `00487de`，媒体索引补充 `ddb00f0`。Windows 构建和文档在后续交付提交中；便携包 manifest 单独记录构建时提交和 dirty 状态。

## 已完成的改动

| 部分 | 问题及结果 |
| --- | --- |
| 视频档位 | 原发送目标固定 640×360/15fps/0.5Mbps。默认改为 848×480/25fps/1.2Mbps、最高 1.8Mbps；motion 和 legacy 可选。保持 H264/97、42e01e、接收 fmtp 与解码尺寸配置，所有尺寸/帧率受 Level 3.0 与 I420 边界检查 |
| 音频配置 | 独立 RTP 接收队列及一个工作线程；20ms 分帧，采集/播放各 60ms，抖动缓冲 60/20/120/240ms（初始/最小/最大预取/上限）。保留原生时钟、编解码器和回声消除；legacy 保留旧音频默认 |
| 原生能力查询 | 5 秒不可变快照，启动/停止/预览前失效；统一锁顺序并释放原生向量、设备信息对象。10000 次有效期内访问测试只加载一次 |
| 日志 | 可选文件日志通过 256 条有界队列写盘，单条限制 32768 字符；磁盘阻塞不阻塞生产者，饱和记丢弃。BYE 观察器先于界面转发执行 |
| 媒体诊断 | 接通后默认每 5 秒采样活动音视频流，记录 RTP 码率、接收丢包、原生 jitter/RTT 均值和 jitter buffer。挂断取消，Call 释放与采样互斥，首样本/计数器复位不伪造速率 |
| ESL | Content-Length 按 UTF-8 字节读取，处理连续帧和分片；帧内超时重连，空闲超时保持连接。认证/订阅拒绝不报 connected，EOF 同样退避；只订阅使用的会议维护事件，关闭时安全捕获 socket |
| 房间统计 | 房间列表先批量读取会议话单，再按房间分组计算并发峰值；不逐房间重复查历史。保留起止时刻相等时先计入会的原规则，忽略零/负时长 |
| 原三条客户端失败 | vmute mock 对齐 success 字段；头像测试只访问随机端口假服务，不探测和修改真实账号；摄像头预览提示由纯 UI 缓存更新，避免重入原生锁。FX 异常回传，第二次开关等待异步任务结束 |
| 测试隔离 | 截图写入 client/target/test-snapshots，不再写开发者个人目录或正式文档目录；浏览器轮询测试先等待标签独立请求结束，再统计轮询，避免把取消请求的交接误计成轮询重叠 |
| Windows 交付 | 本机专用构建入口，检查 JDK 平台、JavaFX Windows 内容、必需 DLL，重建 JAR 并打入完整运行模块。修复 versioned OpenH264 预加载；旧 Bash 构建限制平台且缺运行时即失败；新增包内 JRE 隔离验证和哈希清单 |

前端生产改动仅为 MainApp 与 CallUiCoordinator 的摄像头状态同步，未改布局、样式、管理网页或业务交互结构。没有改变 HTTP 字段、SIP 账号、房间 URI、SDP 或 ESL 密码。
RTP 发送线程、PLI 和开流关键帧是已有策略，本轮保留，不重复算作新功能；没有新增 FEC、RTP 重传、拥塞控制或码率自适应。

## 最终回归结果

| 验证 | 总数 | 通过 | 失败 / 错误 | 跳过 | 证据 |
| --- | ---: | ---: | ---: | ---: | --- |
| 后台完整 Maven | 93 | 92 | 0 / 0 | 1 | backend-optimization-final.log，18:34:38 完成；跳过真实 MCU 项 |
| 客户端完整 Maven | 91 | 82 | 0 / 0 | 9 | client-optimization-final.log，18:46:23 完成；硬件及显式原生开关关闭 |
| 管理页浏览器契约 | 10 | 10 | 0 / 0 | 0 | admin-browser-optimization.log；包含一个父测试和九个子测试，headless Edge、假 HTTP |
| 原生参数显式验证 | 1 | 1 | 0 / 0 | 0 | NativeMediaPolicyTest 显式开启；同次运行另有 10 项普通单元测试，合计 11 通过；与完整客户端计数有重叠 |
| Windows 包内 JRE | 1 次验证流程 | 通过 | 0 | 0 | 见交付目录 validation-*/result.json；JNI、健康、登录、用户/房间/统计读回 |

关键新增回归包含：七项 ESL 帧读、三项 ESL 真实 socket 生命周期、四项批量统计、四项媒体档位、四项能力缓存、两项 RTP 数学、两项异步日志。
200 个房间、10000 条合成话单用例确认查询会议历史仅一次；这是查询次数验证，不是数据库吞吐或 HTTP 延迟基准。
异步日志用被锁住的 Writer 验证：容量 4 时连续 10000 次 offer 返回，保留 4 条、丢弃 9996 条，再释放 Writer 并确认写出丢弃标记。

Java 为 Microsoft JDK 17.0.20.1，Maven 3.9.9，PJSIP 2.17。JavaFX/PJSIP 的原生日志会让 Surefire 提示 native-stream channel warning；以上进程退出为 0，测试 XML 无失败/错误，该提示不代表媒体质量验证。
完整原始日志保存在 `D:/work/.codex-execution/`，必要摘要及哈希在本目录的同名 JSON/CSV/LOG。

## 纯编码基准

CPU 为 Intel Core Ultra 9 290HX Plus。使用本机 MSYS2 OpenH264，DLL SHA256 为 `f6be16f2ad0a557433d60b4c444a27c36cc4e853609f4e1ea520f51711b01dd0`。
单线程、Baseline Level 3.0、中等复杂度、1200 字节 NAL 上限、实时相机模式，沿用 PJSIP 的启用帧跳过策略。
每档先 30 帧预热，再输入 180 帧移动合成亮度纹理，色度固定；生成图像不计入 EncodeFrame 时间。
P95 为排序后 nearest-rank 第 171 项；码率以输出字节 / 输入帧对应的目标时间计算。

| 档位 | 平均编码 ms | P95 ms | 编码 / 跳过（共 180） | 合成输出 kbps | 目标帧预算 |
| --- | ---: | ---: | --- | ---: | --- |
| legacy | 1.965 | 3.544 | 165 / 15 | 481.589 | 66.67ms |
| balanced | 3.311 | 6.446 | 157 / 23 | 1162.618 | 40.00ms |
| motion | 2.015 | 3.565 | 165 / 15 | 877.181 | 33.33ms |

三个档位的纯编码耗时都在本机目标帧预算内；这台机器的该合成负载没有表现为纯编码 CPU 不足。
编码器仍因码率控制跳帧，balanced 为 23/180；这不是网络丢包，也不能把目标 25fps 或该运行速度写成实测通话帧率。
基准仅一次，未控制 CPU 频率与其他进程负载；不包含采集、缩放、网络、MCU、解码、显示、画质评分或音频延迟，不能外推同学电脑。
不因这项结果盲目改原生线程数或重编 DLL。可复验入口 `scripts/benchmark-media.ps1`，CSV 随报告归档。

## Windows 运行验证与原生材料

验证清除了开发 JDK/MSYS2 的 PATH，显式使用包内 `jre/bin/java.exe`。后台只监听回环的系统分配端口，ESL 关闭，使用新建 SQLite 和上传目录；读回 8 用户、2 房间、0 话单，token 未写入报告。
验证结束只停止本轮进程句柄对应、启动时间相符的进程；不触碰本机 5060 的旧 Kamailio、其他系统 8088 或同学服务。
没有验证最终 JavaFX 桌面启动、硬件采集或真实信令/RTP。包的提交与 SHA256 以随包 manifest 和最终交付 JSON 为准，不能用旧包覆盖新源代码的证据。

现有 `pjsua2.dll`：25416627 字节，SHA256 `e19935eb972d3bbe55dd29fe30820184ff66c0ddfd7c4b8f6a54857ae9c2610a`，未重编、未提交二进制。
既有释放补丁归档至 `native/windows-x64/patches/video-hangup-2.17.patch`，SHA256 `49e0fef86d1da9c9840160381253014e5fb84b23df27e78d0326e68e6401a7f6`。
在本机源码基线 `5a457451fa2712ba18e12b01738e8ff3af2b26fd` 的工作树执行反向 dry-run 成功，未从干净源码完整重建，故不声称二进制构建已复现。

## 会议端限制与后续工作

当前 FreeSWITCH XML 仍为 960×540、15fps、1mb、3×3，名义单格约 320×180；这是会议低帧率和低分辨率的明确配置约束。
不能仅提高客户端目标就宣称会议达到 25/30fps。实际协商还可能降低分辨率和速率，未获取同学服务器 CPU、网络及输出流数据，故不能断言全部卡顿根因。

待同学服务器恢复，由 MCU 负责人核对真实混流帧率、CPU/容器资源、编码带宽和布局策略，再结合客户端媒体日志决定是否提高画布/帧率或调整布局；遵守现有接收能力及 SDP 限制。
本轮没有修改 MCU、重启服务或跨角色协议。双机通话、反复入会、8/9 人边界与 macOS 包验证等，等用户另行安排；不作为本轮待执行任务继续跑。

## 复验入口和依据

```powershell
mvn -o -f admin/pom.xml test
mvn -o -f client/pom.xml test
mvn -o -f client/pom.xml -Dtest=NativeMediaPolicyTest -Dsip.nativePolicyTest=true test
$env:CHROME_PATH='C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
node --test admin/src/test/js/admin-workbench.test.cjs
pwsh -File scripts/benchmark-media.ps1
```

Windows 构建与校验见 [交付说明](../windows-delivery.md)，媒体属性和日志定义见 [媒体说明](../video-performance.md)。
依据为本机 2.17 的 `pjmedia/src/pjmedia/vid_codec_util.c`、`pjmedia/src/pjmedia-codec/openh264.cpp`，以及 [PJSIP 编码参数](https://docs.pjsip.org/en/latest/specific-guides/video/codec_params.html)、[PJSUA2 媒体 API](https://docs.pjsip.org/en/latest/api/generated/pjsip/group/group__PJSUA2__MED.html)、[FreeSWITCH ESL](https://developer.signalwire.com/freeswitch/FreeSWITCH-Explained/Event-System/Event-Socket-Library/)。
