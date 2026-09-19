# admin

单体 Spring Boot 3.3 + Java 17。表和接口按 [docs/分工-后台.md](../docs/分工-后台.md) 实现，此处不预置空包。

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
- ESL：`org.freeswitch.esl.client` 一系，只订房间成员增减

阶段 1 先能读写 SQLite。Kamailio 先用静态账号，不必一开始就和 users 表同步。
