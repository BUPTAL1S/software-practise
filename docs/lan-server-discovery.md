# 局域网服务器地址自动发现

客户端在打开登录页时后台搜索，找到服务后同时填写 SIP 与 HTTP 地址。点击登录/注册会再次确认地址，避免使用刚刚失效的缓存。搜索不发送账号或密码。用户明确不要求维持 IP 变化时正在进行的通话。

## 主机端

1. 启动本次编译的新版 Spring Boot 后台。默认监听 UDP **39771**，接收广播与组播 `239.255.71.80`，查询回复采用单播。HTTP 实际端口可以不是 8080。
2. Windows 主机使用 `server/start-kamailio.ps1` 启动信令与 MCU。脚本成功后自动启动隐藏的 `watch-lan-ip.ps1`，每 5 秒检查地址，连续两次检查一致后刷新 Kamailio/FreeSWITCH 的对外地址。服务刷新失败会重试，不把失败地址标记为就绪。`-NoWatch` 可禁用监听。
3. 保留原有 HTTP、SIP、RTP 防火墙配置。若发现报文被 Windows 防火墙拦截，可在管理员 PowerShell 运行 `server/enable-discovery-firewall.ps1`，仅放行本地子网来源的 UDP 39771。脚本不修改 HTTP、SIP、媒体端口规则。

```powershell
pwsh -File server/start-kamailio.ps1
# 在 admin 目录运行编译后的后台，沿用该目录原数据库与附件路径
java -jar target/sipim-admin-0.1.0-SNAPSHOT.jar
```

后台参数：

- `SIPIM_DISCOVERY_ENABLED=false` 关闭发现通道，手动连接继续可用。
- `SIPIM_DISCOVERY_PORT` 默认 39771。普通客户端固定查询这个端口，改动时两端必须一致。
- `SIPIM_SIP_PORT` 默认 5060，发现回复会向客户端告知该端口。
- `SIPIM_DISCOVERY_ID_FILE` 默认 `./data/.discovery-id`，首次生成 UUID，重启和换 IP 后保持不变。搬动后台时需要一起保留，不能把它打入所有客户端包。

监听器使用文件锁避免同一仓库启动多个实例。PID 记录在 `server/.lan-ip-watch.pid`，日志为 `.lan-ip-watch.log` 与 `.lan-ip-watch.err.log`。需要停止时先核对该 PID 的命令行确实是本仓库的 `watch-lan-ip.ps1`，再停止对应进程。关闭 Docker 前宜先关闭监听器，避免后续地址变更触发重试。

## 客户端与 EXE

- 首次发现一个有效服务时自动填写地址。成功登录后保存服务 UUID，下次 IP 变化时优先寻找同一服务。
- 同一服务通过不同网卡响应时按 UUID 去重。发现多个不同服务时提示手动选择，不随意连接。
- 展开“连接设置”可以点击“重新搜索”。手动编辑地址会取消自动发现，正在进行的旧搜索不能覆盖手动输入。手动/自动选择在成功登录后保存。
- 手动连接旧版后台时取消“自动发现服务器”。命令行显式传入 `--server`、`--host`、`--http` 或端口覆盖时也尊重手动配置。
- 登录前验证失败会保留原字段并提示重试。不会把账号密码发送到随机找到的其他 HTTP 服务，也不自动回退到不相关的 localhost 端口。
- 需要向组员发新版 EXE。旧 EXE 不会自行获得新功能。JavaFX、JNI、摄像头与 SIP/媒体处理流程没有因发现功能新增原生依赖。

发现回复必须具有协议版本、此次随机 nonce 和服务 UUID，并通过 `/api/discovery?nonce=...` HTTP 元数据核对。UUID/nonce 用于识别与关联查询，**不构成密码学身份认证**。本功能用于现有受信任局域网环境，不替代账号鉴权或 HTTPS。

## 网络范围

广播/组播可能被校园网 VLAN、无线客户端隔离或防火墙阻断。“同一校园网”不保证可发现。未发现时仍可手动填写已知可达地址。跨网段自动查询需要后续提供固定域名或固定地址目录，本次按用户选择先实现局域网发现，没有向外部服务发布 IP。

Windows 地址监听更新现有部署配置，可能重建 FreeSWITCH 或重启 Kamailio。它不实现通话中无缝切换，也不修改 SIP 呼叫业务路由。Bash 启动脚本仍在执行时探测地址，本次监听器仅针对实际 Windows 主机。

## 验证

```powershell
mvn -f client/pom.xml '-Dtest=ServerDiscoveryTest,LoginDiscoveryTest,LoginViewTest,ConnectionDefaultsTest,ChatDateSeparatorTest,DirectorySyncTest,LogoutFlowTest' test
mvn -f admin/pom.xml test
pwsh -File scripts/test-lan-startup.ps1
```

客户端网络测试使用回环地址模拟旧 IP 失效、新 IP/端口、同一 UUID、错误 nonce、无关服务、多服务和超大响应。界面测试覆盖异步不阻塞、手动输入保护、关闭页面丢弃旧回调、登录前重新确认与手动兜底。后台使用随机 HTTP/UDP 端口与独立测试库。

本机包验证可使用客户端包内 Java 调用 `lab.sip.client.ServerDiscovery`，只查询，不加载 JNI、不登录：

```powershell
runtime/bin/java.exe -cp app/sip-client.jar lab.sip.client.ServerDiscovery
```

Java API 依据：[MulticastSocket](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/MulticastSocket.html)、[DatagramSocket](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html)。
