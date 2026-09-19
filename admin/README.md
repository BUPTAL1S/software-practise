# admin

单体 Spring Boot 3.3 + Java 17。表和接口按 [docs/分工-后台.md](../docs/分工-后台.md) 实现。

## 怎么跑（阶段 1）

需要 JDK 17。在本目录：

```bash
mvn spring-boot:run
```

- 健康检查：`GET http://localhost:8080/api/health`
- 用户列表：`GET http://localhost:8080/api/users`
- 库文件：`admin/sipim.db`（已 gitignore）
- 首次启动会插入测试用户 `1001`–`1008`（`sip_id` 同名，与 Kamailio 静态账号对齐）。阶段 1 仍不校验 SIP 密码。
- 若本机已经有旧的 `admin/sipim.db`（里面是 `100`–`107`），删掉该文件后重启才会重灌。

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
- ESL：阶段 4 再加，本阶段不接 FreeSWITCH

阶段 1：空工程能跑，用户表能插入测试数据。登录接口留给阶段 2。
