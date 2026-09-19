# 用到的开源项目

本仓库只放课设代码和配置说明，不 vendor 上游全仓，不加巨型 submodule。

## 采用

| 用途 | 项目 | 版本 | 本仓库位置 |
|---|---|---|---|
| 客户端 SIP + 音视频 | [pjsip/pjproject](https://github.com/pjsip/pjproject) | **2.17**（[tag](https://github.com/pjsip/pjproject/releases/tag/2.17)） | 源码不入库；预编译放到 `native/<平台>/`（git 忽略） |
| SIP 代理 | [kamailio/kamailio](https://github.com/kamailio/kamailio) | 与 `server/docker-compose.yml` 镜像 tag 一致，优先 `5.8` 线 | `server/kamailio/` 只放我们的 cfg 片段 |
| 房间 MCU | FreeSWITCH `mod_conference` | 与 compose 镜像 tag 一致，优先 `1.10` 线 | `server/freeswitch/` 只放会议相关说明/片段 |
| ESL | Maven `org.freeswitch.esl.client:esl-client-netty4`（或 compose 文档指定的坐标） | 跟后台 POM 钉死一版 | 只在 `admin` 依赖里引用 |
| 后台 | Spring Boot **3.3.x** + Java **17** | POM 锁定 | `admin/` |
| UI | JavaFX（与 JDK 17 匹配的 OpenJFX） | POM 锁定 | `client/` |
| 库 | MySQL **8.0** | compose 镜像 | compose 服务 `mysql` |

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

## 镜像占位

compose 里的 tag 在第 1 周由信令角色选定后，回写本表「与 compose 一致」那一行，避免三个人各拉各的 latest。
