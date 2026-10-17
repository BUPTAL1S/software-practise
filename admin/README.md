# admin

单体 Spring Boot 3.3 + Java 17。表和接口按 [docs/分工-后台.md](../docs/分工-后台.md) 实现。

## 怎么跑

需要 JDK 17。在本目录：

```bash
mvn spring-boot:run
```

- **管理与统计大盘（Web）**：打开浏览器访问 `http://localhost:8080/`（或 `/index.html`、`/admin.html`）
  - 实时指标：当前在线用户、总通话次数、总时长、平均时长、房间并发峰值人数
  - 数据面板：通话记录 (CDR)、会议房间 (Rooms)、注册用户 (Users)、文件传输 (Files)
  - 快捷操作：创建房间、快速注册用户、按主被叫筛选通话、文件直接下载
- 健康检查：`GET http://localhost:8080/api/health`
- 统计页：`GET http://localhost:8080/`
- 用户列表：`GET http://localhost:8080/api/users`
- 库文件：`admin/sipim.db`（已 gitignore）
- 首次启动会插入测试用户 `1001`–`1008`（`sip_id` 同名，与 Kamailio 静态账号对齐），并初始化研讨室 `room-101` 与 `room-102`。
- HTTP 密码与用户名相同，例如 `1001` / `1001`。库里存的是 BCrypt，不是明文。
- 这套密码用于 HTTP 登录接口。Kamailio 的 SIP 密码仍是任意非空。
- 若本机已经有旧的 `admin/sipim.db`，删掉该文件后重启即可重新灌入种子数据。

## 用户认证与注册（阶段 2）

### 1. 登录
`POST http://localhost:8080/api/login`

```json
{"username":"1001","password":"1001"}
```

成功 `200`：
```json
{"token":"<64位十六进制>","username":"1001","sipId":"1001"}
```

- 缺字段或空白：`400 {"error":"username and password are required"}`
- 用户不存在或密码错误：`401 {"error":"unauthorized"}`

### 2. 用户注册
`POST http://localhost:8080/api/register`（或 `POST http://localhost:8080/api/users`）

```json
{
  "username": "1009",
  "password": "password123",
  "sip_id": "1009"
}
```
（`sip_id` 可选，缺省与 `username` 相同）

成功 `201 Created`：
```json
{
  "id": 9,
  "username": "1009",
  "sipId": "1009",
  "token": "<64位十六进制>"
}
```

- 缺用户名或密码：`400 {"error":"username and password are required"}`
- 用户名已存在：`409 {"error":"username already exists"}`
- SIP ID 已存在：`409 {"error":"sipId already exists"}`

### 3. 当前用户与令牌验证
`GET http://localhost:8080/api/me`，请求头 `Authorization: Bearer <token>`。
- 成功 `200`：`{"username":"1001","sipId":"1001"}`
- 无效或缺头：`401 {"error":"unauthorized"}`

### 4. 当前在线用户
`GET http://localhost:8080/api/users/online`

成功 `200`：
```json
{"count":1,"users":["1002"]}
```

`count` 是当前进程里仍持有登录 token 的不同用户数。同一个人再次登录不会加一。后台重启后 token 清空，人数回到 0。这不是 Kamailio 的 SIP 注册人数。

## 房间管理（阶段 2 / 阶段 4 / 阶段 5 联动）

### 1. 获取房间列表
`GET http://localhost:8080/api/rooms`

成功 `200`：
```json
[
  {
    "id": 1,
    "name": "公共研讨室 101",
    "sip_uri": "room-101",
    "sipUri": "room-101",
    "max_members": 8,
    "maxMembers": 8,
    "member_count": 0,
    "memberCount": 0
  }
]
```

### 2. 获取单个房间详情
`GET http://localhost:8080/api/rooms/{id}`（支持数字 ID 或 SIP URI 如 `room-101`）
- 成功 `200`：返回房间对象；不存在返回 `404`。

### 3. 创建会议房间
`POST http://localhost:8080/api/rooms`

```json
{
  "name": "技术研讨室 103",
  "max_members": 8,
  "sip_uri": "room-103"
}
```
（`sip_uri` 可选，缺省自动分配 `room-<id>`；`max_members` 缺省 8）

成功 `201 Created`：
```json
{
  "id": 3,
  "name": "技术研讨室 103",
  "sip_uri": "room-3",
  "sipUri": "room-3",
  "max_members": 8,
  "maxMembers": 8,
  "member_count": 0,
  "memberCount": 0
}
```

- 自定义 SIP URI 冲突：`409 {"error":"room sip_uri already exists"}`

## 文件上传与下载（阶段 3）

字段与客户端 `HttpFileClient` 一致。文件二进制存运行目录下的 `uploads/`（从本目录启动时就是 `admin/uploads/`，已 gitignore），索引存 `files` 表。通话记录和统计见下方阶段 4，本阶段文件接口不含 CDR。

`POST http://localhost:8080/api/files`，`multipart/form-data`。

- 字段 `file`（必填）、`from`（发送方用户名）、`to`（接收方用户名或房间）
- 有登录时带 `Authorization: Bearer <token>`；没有 token 时仍用表单里的 `from`
- `from` 为空且 token 合法时，记成该用户；两者都没有时用 `anonymous`
- `to` 为空时用 `broadcast`
- 表单写了 `from` 时以表单为准，即使也带了别人的 token

成功 `200`，JSON 必有 `id`、`url`（`/api/files/{id}`）、`name`、`size`：

```json
{
  "id": 1,
  "name": "hello.txt",
  "size": 12,
  "url": "/api/files/1",
  "from": "1001",
  "to": "1002"
}
```

缺文件或空文件：`400`

```json
{"error":"file is required"}
```

带了 `Authorization` 但 token 无效：`401`

```json
{"error":"unauthorized"}
```

`GET http://localhost:8080/api/files?from=1001&to=1002&limit=50` 返回按 ID 倒序的文件索引。`GET http://localhost:8080/api/files/{id}` 返回文件字节，`Content-Disposition` 里是原始文件名。id 不存在或磁盘文件丢了：`404`。

```bash
# 上传
curl.exe -s -F "file=@hello.txt" -F "from=1001" -F "to=1002" http://localhost:8080/api/files

# 先登录再上传（不传 from 时记成 1001）
TOKEN=$(curl.exe -s -H "Content-Type: application/json" -d "{\"username\":\"1001\",\"password\":\"1001\"}" http://localhost:8080/api/login | python -c "import sys,json; print(json.load(sys.stdin)['token'])")
curl.exe -s -H "Authorization: Bearer $TOKEN" -F "file=@hello.txt" -F "to=1002" http://localhost:8080/api/files

# 下载（把 1 换成返回的 id）
curl.exe -OJ http://localhost:8080/api/files/1
```

## 通话记录与统计（阶段 4）

客户端通话结束时上报 CDR，后台持久化到 SQLite `cdr` 表，并提供记录查询与统计接口。浏览器打开 `http://localhost:8080/` 可看当前在线用户、通话次数、总时长、平均时长、房间人数峰值，以及 CDR 和用户列表。页面只读已有 GET 接口，不要求登录。房间当前在会人数由阶段 5 的 ESL 写入。

### 1. 通话记录上报

`POST http://localhost:8080/api/cdr`
`Content-Type: application/json`

请求体：
```json
{
  "call_id": "45a82e9b-381a-4c28-98e3@192.168.1.100",
  "caller": "1001",
  "callee_or_room": "1002",
  "started_at": "2026-10-13T10:00:00Z",
  "duration_sec": 45
}
```
（兼容驼峰字段 `callId`, `calleeOrRoom`, `startedAt`, `durationSec`；`call_id` 可选；`started_at` 为空时默认当前系统时间）

成功 `200`：返回包含自增 `id` 的完整 CDR 记录。

- 缺主叫或被叫：`400 {"error":"caller and callee_or_room are required"}`
- 时长为负数：`400 {"error":"duration_sec must be non-negative"}`
- 去重机制：
  1. **SIP Call-ID 精确去重**：若携带 `call_id`，双端针对同一 SIP 对话上报自动 100% 匹配合并并更新为更大有效时长；
  2. **精确端点与时序防误吞**：未携带 `call_id` 时，严格匹配相同主叫与被叫（区分 A->B 与 B->A 回拨，不混淆），且仅在开始时间相差 ±3 秒内、且新呼叫未晚于上一通结束时间（`startedAt <= previousEnd`）时才判定为双端重复上报。同一对人挂断后几秒内再次呼叫（连续通话）将准确记录为两条独立 CDR，彻底避免被误吞；
  3. **单房间并发峰值联动**：当向房间（如 `room-101` 或 `sip:room-101@lab`）上报时，仅计算该房间自身的实际并发重叠峰值并更新该房间，杜绝跨房间数值相互污染。

### 2. 通话记录列表（拉历史）

`GET http://localhost:8080/api/cdr?caller=1001&callee=room-101&limit=50`

- 查询参数（均可选）：
  - `caller`：按主叫用户名过滤
  - `callee`：按被叫或房间号过滤
  - `limit`：最大返回条数（默认 100）
- 成功 `200`：按 ID 倒序排列的 CDR 记录数组。

### 3. 通话与房间统计

`GET http://localhost:8080/api/cdr/stats`（或 `GET http://localhost:8080/api/stats`）

成功 `200`：
```json
{
  "totalCalls": 12,
  "totalDurationSec": 540,
  "averageDurationSec": 45.0,
  "peakRoomMembers": 6
}
```
- `totalCalls`：总通话次数
- `totalDurationSec`：总通话时长（秒）
- `averageDurationSec`：平均单次通话时长（秒）
- `peakRoomMembers`：房间最大同时在会人数峰值（基于通话时段 `[startedAt, startedAt + durationSec]` 的真实时间区间重叠扫描算法计算，反映瞬时并发参会人数，而非历史上不同账号的累计总数；结合各房间的当前在会人数 `rooms.memberCount` 取最大值）

```bash
# 上报一条通话
curl.exe -s -H "Content-Type: application/json" -d "{\"caller\":\"1001\",\"callee_or_room\":\"1002\",\"started_at\":\"2026-10-13T10:00:00Z\",\"duration_sec\":45}" http://localhost:8080/api/cdr

# 列表与统计
curl.exe -s "http://localhost:8080/api/cdr?caller=1001&limit=50"
curl.exe -s http://localhost:8080/api/cdr/stats
```

## 房间 MCU 与 ESL 事件监听（阶段 5）

后台用纯 Java 连接 FreeSWITCH ESL（默认 `127.0.0.1:8021`，密码 `ClueCon`，与 `server/freeswitch/autoload_configs/event_socket.conf.xml` 一致）。`8021` 只绑在跑 FreeSWITCH 的那台机器的回环地址上，校园网其他电脑连不进这个端口，所以不要把 `ESL_HOST` 改成同学的校园网 IP。后台必须和 FreeSWITCH 在同一台机器上跑。容器没起来时按 5 秒重试，不挡住启动。

- 订阅 `conference::maintenance`。入会、离会时把 `Conference-Size` 写入该房间的 `member_count`，这是当前在会人数。
- 历史峰值由 CDR 扫线现算，不写回 `member_count`。
- 后台不发起呼叫，房间由客户端 INVITE 加入。

```yaml
freeswitch:
  esl:
    enabled: true
    host: 127.0.0.1
    port: 8021
    password: ClueCon
    reconnect-interval-sec: 5
```

## 四张表

| 表 | 字段（最少） | 说明 |
|---|---|---|
| users | id, username, password_hash, sip_id | 存储用户及 BCrypt 密码散列 |
| rooms | id, name, sip_uri (`room-<id>`), max_members=8, member_count | 存储研讨/会议室与 ESL 写入的当前在会人数 |
| cdr | id, call_id, caller, callee_or_room, started_at, duration_sec | 通话记录与时长 |
| files | id, from_user, to_user_or_room, path, size, created_at | 文件索引元数据 |

文件存 `admin/uploads/`（已 gitignore）。

## 依赖

- Spring Web
- Spring Data JPA + SQLite（文件 `sipim.db`，不入库）
- `spring-security-crypto`：只做 BCrypt，不启用 Spring Security 过滤链
- FreeSWITCH ESL：纯 Java Socket，不另加客户端库
