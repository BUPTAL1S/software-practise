# 用到的开源项目

本仓库只放课设代码和配置说明，不 vendor 上游全仓，不加巨型 submodule。怎么在本地编 JNI、改 cfg，见 [如何改开源组件.md](如何改开源组件.md)。

## 采用

| 用途 | 项目 | 版本 | 本仓库位置 |
|---|---|---|---|
| 客户端 SIP + 音视频 | [pjsip/pjproject](https://github.com/pjsip/pjproject) | **2.17**（[tag](https://github.com/pjsip/pjproject/releases/tag/2.17)） | 源码不入库；预编译放到 `native/<平台>/`（git 忽略） |
| SIP 代理 | [kamailio/kamailio](https://github.com/kamailio/kamailio) | **5.8.3**，镜像 `ghcr.io/kamailio/kamailio-ci:5.8.3-alpine`（与 compose 一致） | `server/kamailio/` 只放我们的 cfg |
| 房间 MCU | FreeSWITCH `mod_conference` | **1.10.12**，镜像 `safarov/freeswitch:1.10.12`。阶段 4 只开 ESL；阶段 5 再把 `room-*` 转进来 | `server/freeswitch/` 只放会议相关说明 |
| ESL | Maven `org.freeswitch.esl.client:esl-client-netty4`（或 compose 文档指定的坐标） | 跟后台 POM 钉死一版 | 只在 `admin` 依赖里引用 |
| 后台 | Spring Boot **3.3.x** + Java **17** | POM 锁定 | `admin/` |
| UI | JavaFX（与 JDK 17 匹配的 OpenJFX） | POM 锁定 | `client/` |
| 库 | SQLite（JPA） | 跟 Spring Boot 走 | `admin/` 下 `sipim.db`（gitignore） |

PJSIP Java 绑定：用发行源码里 `pjsip-apps/src/swig/java` 按官方文档生成 JNI，Win / Mac 各编一次。不要用 Android 示例当 PC 主工程。

## 明确不用

| 项目 | 原因 |
|---|---|
| OpenCallHub | 呼叫中心，不是 IM 后台 |
| oalles/sip-platform | RedisJSON + Gears，过重且停更 |
| Jitsi Desktop / JVB | 课设走 SIP+MCU，不上 SFU |
| Liblinphone | 与 PJSIP 重复 |
| mjSIP / JAIN-SIP 作主引擎 | 无媒体；报告里可对比，代码不进主路径 |
| miniSIPServer | 已确认 Kamailio；其会议室上限 5，不够 6–8 人 |

## 镜像（阶段 1 已钉）

- Kamailio：`ghcr.io/kamailio/kamailio-ci:5.8.3-alpine`
- FreeSWITCH：`safarov/freeswitch:1.10.12`
