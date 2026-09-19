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
- Spring Data JPA + MySQL（compose 服务 `mysql`）
- ESL：`org.freeswitch.esl.client` 一系，只订房间成员增减

第 1 周先能连上 MySQL。Kamailio 先用静态账号，不必第一周就和 users 表同步。
