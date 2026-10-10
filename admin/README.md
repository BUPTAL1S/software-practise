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

这次没有用户自助注册、文件上传、CDR、房间创建、ESL。

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
