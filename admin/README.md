# admin

单体 Spring Boot 3.3 + Java 17。表和接口按 [docs/分工-后台.md](../docs/分工-后台.md) 实现。

## 怎么跑

需要 JDK 17。在本目录：

```bash
mvn spring-boot:run
```

- 健康检查：`GET http://localhost:8080/api/health`
- 用户列表：`GET http://localhost:8080/api/users`
- 库文件：`admin/sipim.db`（已 gitignore）
- 首次启动会插入测试用户 `1001`–`1008`（`sip_id` 同名，与 Kamailio 静态账号对齐）。
- HTTP 密码与用户名相同，例如 `1001` / `1001`。库里存的是 BCrypt，不是明文。
- 这套密码只用于下面的登录接口。Kamailio 的 SIP 密码仍是任意非空，两边不同步。
- 若本机已经有旧的 `admin/sipim.db`（`100`–`107`，或密码还是 `dev-1001`），删掉该文件后重启才会重灌。

## 登录（阶段 2）

客户端阶段 4 再对接。现阶段只要按下面的字段调用。

`POST http://localhost:8080/api/login`

```json
{"username":"1001","password":"1001"}
```

成功 `200`：

```json
{"token":"<64位十六进制>","username":"1001","sipId":"1001"}
```

缺字段、空白或不是 JSON：`400`

```json
{"error":"username and password are required"}
```

用户不存在或密码错误：`401`（两种情况同一句）

```json
{"error":"unauthorized"}
```

`GET http://localhost:8080/api/me`，请求头 `Authorization: Bearer <token>`。成功返回 `username` 和 `sipId`，不返回 token。没有头、格式不是 `Bearer <token>`、或 token 不认识，都是 `401`。客户端现阶段不必调用这个接口，它只用来确认 token 还有效。

token 只放在进程内存里。同一用户再次登录会再发一个 token，旧的仍然有效。重启后台后全部失效，需要重新登录。

## 文件上传与下载（阶段 3）

文件二进制存 `uploads/` 目录，索引存数据库 `files` 表。

### 上传文件

`POST http://localhost:8080/api/files`
`Content-Type: multipart/form-data`

请求头：
- `Authorization: Bearer <token>`（可选）：若携带此头，必须为有效 token（将解析对应用户为 `from`）；若 token 格式错误或已失效返回 `401 {"error":"unauthorized"}`。不带此头则以表单参数 `from` 为准。

请求字段：
- `file`：二进制文件内容（必填）
- `from`：发送者用户名（可选，默认 `anonymous`）
- `to`：接收方用户名或房间号（可选，默认 `broadcast`）

成功 `200`：
```json
{
  "id": 1,
  "name": "example.png",
  "size": 10240,
  "url": "/api/files/1",
  "from": "1001",
  "to": "1002"
}
```

缺文件或文件为空：`400`
```json
{"error":"file is required"}
```

### 下载文件

`GET http://localhost:8080/api/files/{id}`

- 成功 `200`：二进制流，带 `Content-Disposition: attachment; filename="<原文件名>"` 与对应 `Content-Type`。
- 文件不存在：`404 Not Found`。

## 通话记录与统计（阶段 4）

客户端通话结束时上报 CDR，后台持久化到 SQLite `cdr` 表，并提供记录查询与统计接口。

### 1. 通话记录上报

`POST http://localhost:8080/api/cdr`
`Content-Type: application/json`

请求头：
- `Authorization: Bearer <token>`（可选）：若携带，校验有效性；若 token 无效返回 `401 {"error":"unauthorized"}`。当未传 `caller` 时将自动以 token 关联用户为主叫。

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

成功 `200`：
```json
{
  "id": 1,
  "call_id": "45a82e9b-381a-4c28-98e3@192.168.1.100",
  "caller": "1001",
  "callee_or_room": "1002",
  "started_at": "2026-10-13T10:00:00Z",
  "duration_sec": 45
}
```

- 缺主叫或被叫：`400 {"error":"caller and callee_or_room are required"}`
- 时长为负数：`400 {"error":"duration_sec must be non-negative"}`
- 去重机制：
  1. **SIP Call-ID 精确去重**：若携带 `call_id`，双端针对同一 SIP 对话上报自动 100% 匹配合并并更新为更大有效时长；
  2. **精确端点与时序防误吞**：未携带 `call_id` 时，严格匹配相同主叫与被叫（区分 A->B 与 B->A 回拨，不混淆），且仅在开始时间相差 ±3 秒内、且新呼叫未晚于上一通结束时间（`startedAt <= previousEnd`）时才判定为双端重复上报。同一对人挂断后几秒内再次呼叫（连续通话）将准确记录为两条独立 CDR，彻底避免被误吞；
  3. **单房间并发峰值联动**：当向房间（如 `room-101`）上报时，仅计算该房间自身的实际并发重叠峰值并更新该房间，杜绝跨房间数值相互污染。

### 2. 通话记录列表（拉历史）

`GET http://localhost:8080/api/cdr?caller=1001&limit=50`

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

## 四张表

| 表 | 字段（最少） |
|---|---|
| users | id, username, password_hash, sip_id |
| rooms | id, name, sip_uri (`room-<id>`), max_members=8, member_count |
| cdr | id, caller, callee_or_room, started_at, duration_sec |
| files | id, from_user, to_user_or_room, path, size, created_at |

文件存 `admin/uploads/`（已 gitignore）。

## 依赖

- Spring Web
- Spring Data JPA + SQLite（文件 `sipim.db`，不入库）
- `spring-security-crypto`：只做 BCrypt，不启用 Spring Security 过滤链
- ESL：阶段 4 再加，本阶段不接 FreeSWITCH
