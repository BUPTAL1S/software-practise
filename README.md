# software-practise

分组课设：Java PC 即时通信（SIP 音视频 + 房间 + 后台）。

- 整体规划与三人分工：[docs/整体规划.md](docs/整体规划.md)
- 开源依赖（钉版本，不拷全仓）：[docs/deps.md](docs/deps.md)

## 结构

```
client/    JavaFX + PJSUA2
native/    各平台 pjsua2 预编译（不入库）
server/    docker compose：Kamailio + FreeSWITCH + MySQL
admin/     Spring Boot 单体
docs/      规划与分工
```

## 怎么跑（第 1 周目标）

```bash
cd server && docker compose up -d
```

客户端与后台工程尚未落代码，先读 docs，再按角色开工。SIP 域：`lab`。一对一走 Kamailio；房间 `sip:room-<id>@lab` 进 FreeSWITCH，上限 8 人。

## 协作

仓库：https://github.com/GaiYa0/software-practise  
在仓库 Settings → Collaborators 里加另外两名同学。不要提交 `.dll` / `.dylib`。
