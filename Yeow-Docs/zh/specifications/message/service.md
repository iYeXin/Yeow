# Service 通道

服务注册、请求、订阅和发布。

> **权限**：`service:registerNative`（spawn 原生子进程）**默认拒绝**，插件必须在 `yeow.json` 的 `permissions` 中声明。其余 service 节点（`register`/`request`/`subscribe`/`publish`/`response`/`awaitReady`/`registerNativeTerminate`）默认允许。未声明调用返回 `Permission denied: service:registerNative`。

## 概述

`service` 通道实现了插件间通信（Plugin Service）和原生能力扩展（Native Service）。两种服务在消费者视角完全一致。

## 操作列表

### `register` — 注册 Plugin Service

- **请求**：`{ "t": "register", "refName": "<name>", "onRequest": "<cbId>", "public": <bool> }`
- **返回**：`{ "serviceId": "<id>", "token": "<tok>" }` \| `{ "err": "<msg>", "serviceId": "<id>" }`

| 字段        | 必填 | 说明                                                                               |
| ----------- | ---- | ---------------------------------------------------------------------------------- |
| `refName`   | 是   | 服务引用名                                                                         |
| `onRequest` | 是   | 处理请求的回调 ID（需 `persistent: true`）                                         |
| `public`    | 是   | `true` 为公有（serviceId = refName），`false` 为私有（serviceId = refName_random） |

返回的 `token` 用于 `publish` 时鉴权，**仅首次注册返回**。

**重复注册**：若 `public: true` 且同名服务已存在，返回 `{ "err": "Service already registered: <id>", "serviceId": "<id>" }`。`onRequest` 不生效；调用方应使用返回的 `serviceId` 以调用者身份接入既有服务（request / subscribe），token 不会对外返回。

当有请求到达时，通过 `cb` 通道向 `onRequest` 投递：

```json
{
  "_svc": "request",
  "requestId": "svcreq_1",
  "consumer": "<consumerPlugin>",
  "path": "/api/hello",
  "body": "{\"key\":\"value\"}"
}
```

服务方需通过 `response` 操作回复。

### `registerNative` — 注册 Native Service

- **请求**：`{ "t": "registerNative", "refName": "<name>", "platforms": {"windows": <PlatformConfig>}, "public": <bool> }`

`PlatformConfig` 可以是：
- **字符串**：`"native/win/app.exe"` — 单文件，向后兼容
- **对象 (file)**：`{ "file": "native/win/app.exe" }` — 显式单文件
- **对象 (dir+entry)**：`{ "dir": "native/win/", "entry": "start.ps1" }` — 提取整个目录到临时空间，运行入口文件
- **返回**：`{ "serviceId": "<id>" }` \| `{ "err": "<msg>" }` \| `{ "err": "<msg>", "serviceId": "<id>" }`

行为：
1. 根据当前平台从 `platforms` 选取对应二进制路径
2. 从插件 JAR 的 `assets/` 中解压二进制到临时目录
3. `spawn(binary, nativePort, serviceId)` 启动子进程
4. 等待子进程连接 TCP 并发送就绪消息

**重复注册**：若 `public: true` 且同名服务已存在，返回 `{ "err": "Service already registered: <id>", "serviceId": "<id>" }`，不会重复 spawn 进程。调用方用 `serviceId` 以调用者身份接入既有服务。

### `request` — 请求服务

- **请求**：`{ "t": "request", "serviceId": "<id>", "path": "<path>", "body": <obj>, "requestId": "<reqId>" }`
- **返回**：`null`（异步）

`requestId` 同时作为回调 ID。服务处理完毕后通过该 ID 投递结果：

```json
{ "t": "cb", "p": "<requestId>", "r": <result> }
```

若 `result` 包含 `err` 字段，表示请求失败。

**Plugin Service** 的处理方式：
- ServiceManager 定位服务所在插件线程
- 通过该线程的 `onRequestCb` 投递请求
- 服务方通过 `response` 操作回复

**Native Service** 的处理方式：
- ServiceManager 通过 TCP 向子进程发送请求
- 子进程处理后通过 TCP 返回响应
- ServiceManager 转换响应格式并投递到消费者

**挂起请求**：服务在请求挂起期间终止（连接断开 / 进程退出 / 卸载 / 运行时关闭）时，运行时拒绝该服务的所有挂起请求：`respond(requestId, consumer, { "err": "Native service <id> terminated (<reason>)" })`，消费者 Promise reject。

### `registerNativeTerminate` — 注册终止钩子（服务持有者）

- **请求**：`{ "t": "registerNativeTerminate", "serviceId": "<id>", "cb": "<cbId>" }`
- **返回**：`"true"`

仅服务的属主插件可注册，重复调用覆盖旧回调。服务终止时通过 `cb` 通道投递（**只触发一次**）：

```json
{ "t": "cb", "p": "<cbId>", "r": { "serviceId": "<id>", "reason": "<reason>", "exitCode": <int?>, "output": "<text?>" } }
```

`reason` 取值：`disconnected`（TCP 断开）/ `exited`（进程退出）/ `unregistered`（卸载）/ `shutdown`（运行时关闭）。多个终止事件同时发生时（如进程退出伴随连接断开）只投递一次。

### `awaitReady` — 等待原生服务就绪

- **请求**：`{ "t": "awaitReady", "serviceId": "<id>", "cb": "<cbId>" }`
- **返回**：`null`（异步）

`cbId` 对应一个临时回调。就绪时通过该回调投递 `{ "ok": true }`，失败时投递 `{ "err": "<msg>" }`。

仅用于 Native Service。调用时机：

| 调用时的状态        | 行为                                                       |
| ------------------- | ---------------------------------------------------------- |
| 已就绪              | 立即 `respond(cbId, { ok: true })`                         |
| 等待就绪（进程存续） | 注册到等待队列，收到 `ready` TCP 消息后 resolve            |
| 进程已退出          | 立即 `respond(cbId, { err: "Native service xxx exited..." })` |
| 服务被卸载          | purgePluginServices / shutdown 时 reject                   |

在 `handleNativeSocket` 收到 ready 消息（`nativeSocket` 绑定完毕后）或监控线程检测到进程退出时，消费等待队列。

### `response` — 回复请求（服务方）

- **请求**：`{ "t": "response", "requestId": "<reqId>", "body": <result> }`
- **返回**：`null`

仅用于 Plugin Service。服务方收到请求后通过此操作回复消费者。

### `subscribe` — 订阅事件

- **请求**：`{ "t": "subscribe", "serviceId": "<id>", "eventPath": "<path>", "cb": "<cbId>" }`
- **返回**：`"true"`

`cb` 以 `persistent: true` 注册。事件发布时通过该回调投递：

```json
{ "t": "cb", "p": "<cbId>", "r": { "serviceId": "<id>", "eventPath": "<path>", "body": <obj> } }
```

### `unsubscribe` — 取消订阅

- **请求**：`{ "t": "unsubscribe", "serviceId": "<id>", "eventPath": "<path>" }`
- **返回**：`"true"`

插件 unload / hot-reload 时 Runtime 自动取消该插件所有订阅。

### `publish` — 发布事件（服务方）

- **请求**：`{ "t": "publish", "token": "<tok>", "eventPath": "<path>", "body": <obj> }`
- **返回**：`"true"`

Runtime 验证 `token` 有效性后，将事件投递到所有匹配 `eventPath` 的订阅者。

`token` 是服务方的私有凭证，仅首次注册时返回。**事件发布是服务方的内部职责**——外部调用者不应直接 `publish`，而应通过 `request` 触发服务，由服务方按业务逻辑决定是否发布。实现上，重复注册同一 public 服务不会返回 token。

Native Service 通过 TCP 发送的 `publish` 消息无需 `token`（Runtime 自动关联服务对应的 token）。

---

## 生命周期

- 插件 unload / hot-reload → Runtime 调用 `purgePluginServices(name)` 清理该插件所有注册（服务、订阅、挂起请求），并拒绝该插件的挂起 `awaitReady` 与消费者挂起请求
- Plugin Service 随插件线程销毁自然失效
- Native Service 子进程被 `destroyForcibly()` 终止
- 服务终止（断开/退出/卸载/关闭）时触发属主插件的 `registerNativeTerminate` 钩子（一次），并拒绝该服务所有挂起请求
