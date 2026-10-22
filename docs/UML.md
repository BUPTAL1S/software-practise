# UML 视图

图用 Mermaid 画出 UML 里常用的几种视图：用例、活动、时序、状态、类、组件、部署。范围与 [需求分析.md](需求分析.md) 一致。阶段 1–5 的交互都已接到现有代码。房间没有群聊这条路径。

## 用例

```mermaid
flowchart LR
  user[通话用户]
  peer[对端用户]
  viewer[查询者]
  ucRegister[注册]
  ucCall[一对一呼叫]
  ucText[发送文字]
  ucFile[发送文件]
  ucCdr[查看通话记录]
  ucStats[查看统计页]
  ucRoom[加入房间]
  user --> ucRegister
  user --> ucCall
  user --> ucText
  user --> ucFile
  user --> ucCdr
  peer --> ucRegister
  peer --> ucCall
  peer --> ucText
  viewer --> ucStats
  user --> ucRoom
```

`加入房间` 与一对一呼叫都已可演示。向房间发文字不是用例，Kamailio 对 `room-*` 的 MESSAGE 回 405。

## 活动：发起一次视频呼叫

```mermaid
flowchart TD
  startNode[启动引擎]
  port{5060是否被占用}
  use5062[改用5062]
  reg[REGISTER]
  regOk{收到200}
  dial[视频呼叫]
  online{被叫已注册}
  ring[被叫振铃并接听]
  media[媒体直连]
  bye[挂断并上报CDR]
  fail404[呼叫失败404]
  failReg[注册失败]
  startNode --> port
  port -->|是| use5062
  port -->|否| reg
  use5062 --> reg
  reg --> regOk
  regOk -->|是| dial
  regOk -->|否| failReg
  dial --> online
  online -->|是| ring
  online -->|否| fail404
  ring --> media
  media --> bye
```

## 时序：注册

```mermaid
sequenceDiagram
  participant User as 通话用户
  participant Client as 客户端
  participant Proxy as Kamailio
  participant Api as 后台
  User->>Client: 注册
  Client->>Proxy: REGISTER sip:id@lab
  Proxy-->>Client: 200
  Client->>Api: POST /api/login
  Api-->>Client: token
```

后台不可达时，SIP 注册仍然成功，界面保持未登录。

## 时序：一对一呼叫与挂断

```mermaid
sequenceDiagram
  participant A as 主叫
  participant Proxy as Kamailio
  participant B as 被叫
  participant Api as 后台
  A->>Proxy: INVITE sip:被叫@lab
  Proxy->>B: INVITE
  B-->>Proxy: 180
  Proxy-->>A: 180
  B-->>Proxy: 200
  Proxy-->>A: 200
  A->>B: RTP直连
  A->>Proxy: BYE
  Proxy->>B: BYE
  A->>Api: POST /api/cdr
  B->>Api: POST /api/cdr
```

两条 CDR 用同一个 Call-ID 合并。视频 SDP 在 INVITE 和 200 里原样通过代理。

## 时序：文字未送达

```mermaid
sequenceDiagram
  participant A as 主叫
  participant Proxy as Kamailio
  A->>Proxy: MESSAGE sip:1002@lab
  Note over Proxy: usrloc中无1002
  Proxy-->>A: 404 Not Found
```

## 时序：文件

```mermaid
sequenceDiagram
  participant A as 发送方
  participant Api as 后台
  participant Proxy as Kamailio
  participant B as 接收方
  A->>Api: POST /api/files
  Api-->>A: id与url
  A->>Proxy: MESSAGE含FILE元数据
  Proxy->>B: MESSAGE
  B->>Api: GET /api/files/id
```

## 时序：加入房间

```mermaid
sequenceDiagram
  participant Client as 客户端
  participant Proxy as Kamailio
  participant Mcu as FreeSWITCH
  participant Api as 后台
  Client->>Proxy: INVITE sip:room-101@lab
  Note over Proxy: 用户部分匹配 room-，不查位置表
  Proxy->>Mcu: INVITE，本机 SDP 改成网关地址
  Mcu->>Mcu: room-101@video-mcu-3x3
  Mcu-->>Proxy: 200，c=校园网IP，UDP 21000-21099
  Proxy-->>Client: 本机改成 127.0.0.1，其他电脑保持校园网 IP
  Mcu-->>Api: conference::maintenance
  Note over Api: 按 Conference-Name 写这一间的人数
```

视频应答是 `97 H264/90000`，`profile-level-id=42e01e`，`packetization-mode=1`。同一条 `room-*` 前缀上的 MESSAGE 在 Kamailio 结束，响应是 `405 Room has no chat`，不会进 FreeSWITCH。一对一 INVITE 不走这条时序，SDP 也不改。满员时拨号计划仍可能先回 200，随后会议室结束这一路。

## 状态：一通呼叫

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Calling: 发起INVITE
  Idle --> Ringing: 收到来电
  Calling --> InCall: 收到200
  Ringing --> InCall: 接听
  Calling --> Idle: 404或取消
  Ringing --> Idle: 拒绝或对端取消
  InCall --> Idle: 本端或对端BYE
```

计时只在 `InCall`（CONFIRMED）期间累加。振铃失败的时长为 0。

## 状态：一条文字

```mermaid
stateDiagram-v2
  [*] --> Outgoing
  Outgoing --> Delivered: 200
  Outgoing --> Failed: 404或超时
```

相同正文按发送顺序匹配回执，先发出的先确认。

## 类：业务里要记住的对象

```mermaid
classDiagram
  class User {
    id
    username
    passwordHash
    sipId
  }
  class FileIndex {
    id
    fromUser
    toUserOrRoom
    path
    size
  }
  class Cdr {
    callId
    caller
    calleeOrRoom
    startedAt
    durationSec
  }
  class Room {
    name
    sipUri
    maxMembers
    memberCount
  }
  class Binding {
    aor
    contact
    transport
  }
  User --> Binding : 注册后才有
  User --> FileIndex : 发送
  User --> Cdr : 作为主叫
  Room --> Cdr : 作为被叫或房间
```

`User`、`FileIndex`、`Cdr`、`Room` 在 SQLite。`Binding` 只在 Kamailio 内存里，不写入业务库。`Room.memberCount` 由 ESL 按会议名写回，是这一间的当前人数。

## 组件

```mermaid
flowchart TB
  subgraph clientApp [客户端]
    ui[MainApp]
    sip[SipService]
    http[HttpFileClient]
  end
  subgraph signal [信令]
    kamailio[Kamailio]
    fs[FreeSWITCH]
  end
  subgraph back [后台]
    login[登录]
    files[文件]
    cdr[通话记录]
    rooms[房间人数]
    page[统计页]
  end
  ui --> sip
  ui --> http
  sip --> kamailio
  http --> files
  sip --> cdr
  ui --> login
  ui --> rooms
  page --> cdr
  page --> rooms
  kamailio -->|room- INVITE| fs
  fs -->|ESL| rooms
```

一对一媒体不经过 FreeSWITCH。统计页只读查询接口。

## 部署

```mermaid
flowchart LR
  subgraph winHost [Windows宿主机]
    winClient[本机客户端]
    docker[Docker]
    boot[SpringBoot]
    browser[浏览器]
  end
  subgraph containers [容器]
    kamailio[Kamailio_5060]
    fs[FreeSWITCH_5080_8021_RTP21000]
  end
  subgraph otherPc [另一台电脑]
    macClient[对端客户端]
  end
  winClient -->|127.0.0.1_5060_TCP| kamailio
  macClient -->|校园网IP_5060_TCP| kamailio
  winClient -->|一对一RTP| macClient
  winClient -->|房间媒体| fs
  macClient -->|房间媒体| fs
  winClient --> boot
  browser --> boot
  docker --> kamailio
  docker --> fs
```

一对一媒体在两台客户端之间直连，不进容器。房间媒体进入 FreeSWITCH，每人拿回一路混流。本机与对端看到的 SIP 服务器地址不同，域都是 `lab`。

## 开发视图

三个开发角色对应三个目录，依赖方向只允许沿已公布的接口。

```mermaid
flowchart LR
  clientDir[client]
  serverDir[server]
  adminDir[admin]
  clientDir -->|SIP与MESSAGE| serverDir
  clientDir -->|HTTP| adminDir
  adminDir -->|ESL| serverDir
```

客户端不改 Kamailio 配置。后台不发 SIP。信令不写管理页面。
