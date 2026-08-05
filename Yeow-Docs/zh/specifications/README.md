# 平台规范

> **本目录面向谁？** 面向希望**实现 Yeow 兼容运行时**的开发者（例如为其他平台编写运行时桥接）。描述了插件 JS 代码与运行时之间的**协议层**：包结构、加载流程、消息通道格式、task 类型、事件数据、Native Service 通信。

> **普通插件开发者无需阅读本目录。** 日常开发请使用 [API 参考](../api/README.md) —— yeow-api 已封装全部底层协议。

---

## 目录结构

| 子目录                                    | 内容                                                                      |
| ----------------------------------------- | ------------------------------------------------------------------------- |
| [message](message/index.md)               | 非调度器通道（timer / fs / http / assets / service / debug 等）的消息格式 |
| [task](task/index.md)                     | 调度器任务类型清单（`player.get`、`world.setBlock` 等的请求/响应格式）    |
| [event](event/index.md)                   | 事件订阅机制与各类事件的数据字段                                          |
| [native-service](native-service/index.md) | Native Service 子进程协议（TCP JSON line）                                |
| [runtime](runtime/index.md)               | 运行时机制（JS 环境、回调系统、全局变量、事件循环）                       |
| [adapter](adapter/index.md)               | 插件适配器规范（多语言 / 社区适配器实现 PluginEntity 并注册）             |

---

## 协议概览

**JS → 运行时**：唯一入口 `$_send(channel, jsonString)`，返回 JSON 字符串或 `null`。

**运行时 → JS**：统一回调消息 `{"t":"cb","p":"<callbackId>","r":<data>}`，由 JS 端 `$hm` 分发。

**生命周期消息**：`INIT` / `LOAD` / `DISABLE` / `RELOAD`，由 `$hm` 处理。

详细格式见各子目录。

---

## Yeow 插件包结构

Yeow 插件是一个 **ZIP 压缩包**（部署到 Bukkit 时为 `.jar`，部署到插件目录时为 `.yeow.zip`，本质都是 ZIP），运行时只需按 ZIP 读取，**不依赖 Java 环境**。

```
my-plugin.jar / my-plugin.yeow.zip (ZIP)
├── yeow.json              ← 插件元信息 + 权限声明（必需）
├── .yeow/
│   ├── main.js            ← esbuild 打包的插件代码（IIFE 格式，生产构建）
│   └── dev.json           ← 开发模式信息（仅 dev 构建，见下）
├── assets/                ← 打包资源（按命名空间 id 分目录）
└── plugin.yml             ← 宿主平台元信息（Bukkit 需要；`.yeow.zip` 与纯平台实现可忽略）
```

> **`.yeow.zip` 与 JAR 的行为完全一致**：运行时按同一套逻辑注册（读 `yeow.json` → 权限 → 代码 → 启动）。放入运行时数据目录（Bukkit 官方实现为 `plugins/Yeow/`）会被自动扫描加载，也可通过 `/yeow load <path>` 手动加载。同一插件名只允许一个实例，重复加载拒绝并警告。

### `yeow.json` — 插件元信息

```json
{
    "name": "my-plugin",
    "version": "1.0.0",
    "author": "",
    "description": "A Yeow plugin",
    "api": "1.18",
    "java": 21,
    "permissions": ["fs:server.*", "http:requestAsync", "service:registerNative"]
}
```

| 字段                  | 说明                                                                                     |
| --------------------- | ---------------------------------------------------------------------------------------- |
| `name`                | 插件名（运行时注入 `__plugin.name`，同一插件名只允许一个实例）                           |
| `version`             | 版本（注入 `__plugin.version`）                                                          |
| `author`              | 作者（注入 `__plugin.author`）                                                           |
| `api` / `java`        | 宿主平台要求的 API/Java 版本（其他平台可忽略）                                           |
| `permissions`  | 开发者声明的权限（敏感节点，见下文[权限模型](#权限模型)）     |
| `computedPermissions` | 构建时计算的最终生效权限（合并 + 通配归一化）；运行时读取此字段（v0 阶段不兼容旧格式包） |
| `native` | 原生服务可信性声明（构建时计算 SHA-256）：`[{ "serviceId": "...", "files": [{ "<打包后路径>": "<sha256>" }, ...], "source": "..." }]` |

### `.yeow/main.js` — 插件代码

esbuild 打包的 **IIFE**（`"use strict"; (() => {...})()`），`bundle: true`，`target: es2023`。运行时不解析模块——直接 evaluate 整个文件即可。插件代码执行时注册回调（`onInit`/`onLoad`/`onUnload`、`_registerCallback` 等），不执行游戏操作。

### `.yeow/dev.json` — 开发模式（可选）

```json
{
    "name": "my-plugin",
    "codeFile": "/abs/path/to/dist/.dev/main.js",
    "assetsDir": "/abs/path/to/dist/.dev/.assets"
}
```

仅开发构建存在。运行时若检测到 dev.json，应：
- 从 `codeFile`（文件系统绝对路径）读取插件代码，而非 JAR 内 `.yeow/main.js`
- `assets` 资源从 `assetsDir`（文件系统目录）读取，而非 JAR 内

### `assets/` — 打包资源

构建时对每个依赖项（主项目与满足条件的 npm 包）的 `assets/` 目录分配一个**唯一命名空间 id**（8 位十六进制，非内容哈希），内容**原样**复制到 `assets/<id>/` 下——**文件不哈希改名**，`assets/` 内部（含跨目录）的相对引用永远有效。

JS 侧通过 `getAssetsPath()` 获取带命名空间的路径（如 `"assets/a1b2c3d4/icon.png"`）。运行时按该路径在 JAR 的 `assets/` 下查找即可，**不要**对路径做二次变换。

---

## 加载流程

运行时加载一个 Yeow 包的标准流程：

```
1. 读取 yeow.json          → 插件元信息（name/version/author）与权限声明
2. 同名检查                → 插件名已存在 → 拒绝加载并警告（任何加载途径）
3. 读取插件代码：
     有 .yeow/dev.json → 从 dev.json.codeFile 读（开发模式）
     否则              → 从 .yeow/main.js 读（生产）
4. 创建 JS 上下文（独立，插件间隔离）
5. 注入全局函数（见下文"JS 运行时注入"）
6. evaluate(引导脚本)      → 定义 $send、_registerCallback、console、$hm 等
7. evaluate(插件代码)      → 注册 onInit/onLoad/onUnload、命令、事件
8. 同步调用 $hm('{"t":"INIT"}')   → 触发 onInit 回调（不入队，先于一切消息）
9. 启动消息循环
10. 投递 {"t":"LOAD"}        → 触发 onLoad 回调（此时游戏操作可用）
```

**加载来源**（行为一致，均走上述流程）：

- 模板 JAR 注册（宿主平台插件机制，如 Bukkit `depend`）
- 数据目录自动扫描（`plugins/Yeow/*.yeow.zip`，启动时）
- 管理命令：`load <path|url>`（临时）、`install <url>`（下载并保存为标准格式到数据目录）、`update <url>`（替换同名旧包，旧包移入 `.backup/`）

> **`.yeow.zip` 优先**：管理命令以 `.yeow.zip` 为主要对象。同一插件名同时存在模板 JAR 与 `.yeow.zip` 时会产生冲突警告（重复加载被拒绝），需手动移除其一。

**加载消息**：插件加载成功时打印加载消息，内容含插件名、版本与**权限声明**。

## 权限模型

敏感消息节点**默认拒绝**，插件必须在 `yeow.json` 的 `computedPermissions` 中声明：

| 节点（可省略）           | 覆盖消息操作                                                              |
| ------------------------ | ------------------------------------------------------------------------- |
| `fs:server.*`            | fs 通道 `server` 前缀节点（服务器根目录，如 `fs:server.readFile`）；`fs:plugin.*` 节点（插件数据目录）**免声明，默认允许** |
| `fs:outer.*`             | fs 通道 `outer` 前缀节点（任意路径，如 `fs:outer.readFile`）              |
| `http:*`                 | http 通道全部操作（`request`/`requestAsync`/`listen`/`respond`/`close`）  |
| `service:registerNative` | service 通道的 `registerNative`（spawn 子进程）                           |
| `assets:extract` / `assets:extractDir` | assets 通道的 `extract` / `extractDir`（解压到磁盘，两个独立节点） |

规则：

- **节点概念**：权限只按**消息节点**（`channel:node`）考虑。节点名中的段（如 `fs:plugin.readFile` 的 `plugin`、`task:player.get` 的 `player`）是业务/访问范围命名，**不是层级**，不参与权限匹配
- **节点匹配**：精确节点（`fs:server.readFile`）；**整组通配** `fs:server.*` 命中该前缀全部节点；**通道通配** `fs:*` 命中 fs 通道全部节点——构建时 `fs:*` 在 `computedPermissions` 中**自动展开**为 `fs:outer.*, fs:server.*`（语义等价）
- **默认允许**：上述默认拒绝节点之外的节点（如 `service:request`、`service:register`、`assets:read`、`fs:plugin.readFile`）无需声明
- **拒绝行为**：未声明调用返回错误 `Permission denied: <node>`。同步调用直接返回错误 JSON；异步调用（含 `cb`）通过回调投递 `{"err":"Permission denied: <node>"}`，JS 侧表现为 Promise reject
- **其他通道**（`task`/`timer`/`log`/`now`/`dir`/`debug`/`lifecycle`）不受权限模型约束
- 权限在插件加载时读取并**固定**（运行时不可变更），加载消息中打印声明内容——打印时 `fs:*` 会**展开为 `fs:outer.*, fs:server.*`**（仅展示，便于服主理解影响范围；权限校验仍按原值 `fs:*`）

**`computedPermissions` 语义**：插件作者与依赖包在各自的 `yeow.config.json` 的 `permissions` 中声明；构建时合并（去重 + 通配归一化，`X:*` 覆盖 `X:...`、`X:段.*` 覆盖 `X:段.<op>`；`fs:*` 展开为 `fs:outer.*, fs:server.*`）写入 `yeow.json` 的 `computedPermissions`。运行时读取该字段。运行时只校验通配/节点匹配，无需理解节点命名段含义。

**生命周期消息语义**：

| 消息      | 触发时机                 | JS 端处理                                                             |
| --------- | ------------------------ | --------------------------------------------------------------------- |
| `INIT`    | 上下文就绪后、消息循环前 | 执行 `__yeowInitCbs`（此时调度器未启动，不可同步游戏操作）            |
| `LOAD`    | 调度器就绪后             | 执行 `__yeowLoadCbs`（可同步游戏操作）                                |
| `DISABLE` | 插件被禁用               | 执行 `__yeowUnloadCbs` → 收到 `unloadDone` 后关闭线程                 |
| `RELOAD`  | 热重载                   | 执行 `__yeowUnloadCbs` → 收到 `unloadDone` 后销毁旧上下文、加载新代码 |

---

## 运行时架构

一个合格的运行时包含以下组件：

```
┌────────────────────────────────────────────────────┐
│                    运行时 Runtime                    │
│                                                    │
│  ┌──────────────┐   ┌──────────────────────────┐  │
│  │ Plugin 1     │   │ Plugin 2                 │  │
│  │ JS 上下文    │   │ JS 上下文                │  │
│  │ + 消息循环    │   │ + 消息循环               │  │
│  └──────┬───────┘   └──────┬───────────────────┘  │
│         │                  │                      │
│  ┌──────┴──────────────────┴───────────────────┐  │
│  │ 调度器（可选但推荐）                          │  │
│  │ 三级队列 HIGH/NORMAL/LOW + 时间片预算         │  │
│  └──────┬──────────────────────────────────────┘  │
│         │                                         │
│  ┌──────┴──────────────────────────────────────┐  │
│  │ 执行器（task type → 宿主平台操作）            │  │
│  │ 事件桥（事件订阅/触发/完成）                  │  │
│  │ 命令桥（命令注册/执行/补全）                  │  │
│  │ 通道实现（fs/http/assets/service/timer...）   │  │
│  └─────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────┘
```

**线程模型**（推荐）：

| 线程                 | 职责                                                      |
| -------------------- | --------------------------------------------------------- |
| 主线程               | 调度器 tick（游戏任务串行执行）、事件/命令桥              |
| JS 线程（每插件）    | 插件代码 + 消息循环，直接处理 fs/http/assets 等非游戏操作 |
| 定时器线程（每插件） | setTimeout/setInterval 到期投递回调                       |
| IO 线程              | 异步 fs/http 操作                                         |

**关键原则**：JS 逻辑与游戏操作分离——插件代码不阻塞游戏主线程；游戏任务经调度器串行化，避免竞态。

---

## JS 运行时注入

在 evaluate 插件代码之前，运行时必须在 `globalThis` 注入以下内容（详见 [runtime 环境标准](runtime/index.md)）：

### 原生层注入（运行时的语言宿主实现）

| 全局                 | 签名                                                      | 说明                                                                             |
| -------------------- | --------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `$_send`             | `(channel: string, jsonString: string) => string \| null` | **唯一 JS→运行时桥**。同步通道返回结果 JSON；含 `cb` 的异步通道立即返回 `null`   |
| `__plugin`           | `{ name, version, author }`                               | 来自 yeow.json，只读                                                             |
| `$dev`               | `boolean`                                                 | 开发模式标记                                                                     |

### 引导脚本层（运行时内置 JS 引导脚本）

运行时需内置一份引导脚本（`init.js` 的等价物），evaluate 插件代码**之前**执行，定义：

| 全局                                                            | 说明                                              |
| --------------------------------------------------------------- | ------------------------------------------------- |
| `$send(channel, payload)`                                       | 包装 `$_send`，自动 JSON 序列化/解析              |
| `_registerCallback(fn, opts?)` / `_unregisterCallback(id)`      | 回调注册表（核心异步原语，格式 `cb_N`）           |
| `console.log/warn/error/info`                                   | 日志，自动添加 `[插件名]` 前缀                    |
| `setTimeout` / `setInterval` / `clearTimeout` / `clearInterval` | 定时器（经 `timer` 通道）                         |
| `fetch(url, opts?)`                                             | HTTP 客户端（经 `http` 通道）                     |
| `$hm(jsonString)`                                               | 消息分发器：解析 JSON → 分发到生命周期/回调处理器 |
| `_getCurrentCbStack()`                                          | 开发模式栈追踪辅助                                |
| `reportError(e)`                                                | 错误上报（经 `debug` 通道）                       |

**消息循环**（`$hm` 的宿主实现）：

```
循环:
  1. 从运行时拉取下一条消息（可阻塞等待）
  2. 调用 $hm(msgJson) 处理
  3. 清空微任务队列（Promise.then / FinalizationRegistry 回调）
  4. 若 __yeowGcQueue 非空 → 发送 lifecycle gc-collect
```

---

## 任务执行器

`task` 通道是插件与游戏世界交互的主要通道。运行时需实现**执行器**：把 `{type, params}` 翻译为宿主平台操作。

### 请求格式

```json
{ "type": "player.get", "params": {"identifier": "uuid"}, "cb": "cb_42", "priority": "high" }
```

| 字段       | 说明                                                                             |
| ---------- | -------------------------------------------------------------------------------- |
| `type`     | 任务类型（完整清单见 [task 规范](task/index.md)）                                |
| `params`   | 任务参数                                                                         |
| `cb`       | 可选。有 → 异步（立即返回 null，结果经回调投递）；无 → 同步（阻塞返回结果 JSON） |
| `priority` | 可选。`high` / `normal` / `low`，默认 `normal`                                   |

### 执行规则

- 所有 `task` 操作**串行执行**（不并发），避免游戏状态竞态
- 按优先级 HIGH → NORMAL → LOW 顺序调度
- 推荐实现**时间片预算**（每 tick 限制执行时长），防止单个插件挤占全部 tick
- 可选实现**自动降级**（高频 NORMAL 任务降为 LOW）与**空闲自旋**（队列空时快速响应新任务）

### 同步 vs 异步

```
同步（无 cb）:
  $send('task', {...})
    → 执行器运行 → 返回结果 JSON（阻塞 JS 线程）

异步（有 cb）:
  $send('task', {...})  → 立即返回 null
    → 调度器稍后执行 → 投递 {"t":"cb","p":"cb_42","r":result}
    → JS 消息循环 $hm → Promise resolve
```

**错误格式**：执行失败时，同步返回 `{"err":"<msg>"}`；异步时 `r` 为 `{"err":"<msg>","type":"<异常类>","task":"<taskType>","stack":"..."}`。

---

## 事件监听

### 订阅

插件通过 `task` 通道 `event.subscribe` 订阅事件：

```json
{ "type": "event.subscribe", "params": { "pluginName": "my-plugin", "eventType": "playerJoin", "callbackId": "cb_42" } }
```

运行时维护 `eventType → plugin → callbackId` 映射。取消订阅用 `event.unsubscribe`。

### 触发

游戏事件发生时，运行时提取事件字段（**仅基本类型**：string/number/boolean/object，不传宿主对象引用），投递回调：

```json
{ "t": "cb", "p": "cb_42", "r": { "_cancellable": true, "player": "uuid", "message": "hi", ... } }
```

### 完成

JS 处理器执行完毕（或手动调用 complete）后，通过 `task` 通道回传修改结果：

```json
{ "type": "event.complete", "params": { "callbackId": "cb_42", "mods": { "cancelled": true } } }
```

运行时应用 `mods`（如 `cancelled` → 取消宿主事件）。超时策略：推荐 5 秒；超时后释放事件。

事件字段清单见 [event 规范](event/index.md)。

---

## 命令执行器与补全

### 注册

插件通过 `task` 通道 `command.register` 注册命令：

```json
{ "type": "command.register", "params": {
    "pluginName": "my-plugin",
    "commandName": "back",
    "callbackId": "cb_50",
    "completerCbId": "cb_51",
    "description": "...", "usage": "...", "permission": "...", "aliases": []
} }
```

### 执行

玩家/控制台执行命令时，运行时投递执行器回调：

```json
{ "t": "cb", "p": "cb_50", "r": {
    "sender": { "name": "Steve", "uuid": "uuid-or-CONSOLE", "isPlayer": true },
    "args": ["arg1", "arg2"],
    "label": "back"
} }
```

> 注意：`sender` 是普通对象。JS 侧 yeow-api 会为其附加 `sendMessage` 方法（玩家 → 消息通道；控制台 → 日志）。其他运行时实现需保证此行为兼容（或不依赖 sendMessage 的实现细节）。

### 补全

玩家按 Tab 时，运行时投递补全回调：

```json
{ "t": "cb", "p": "cb_51", "r": { "sender": {...}, "args": ["ar"] } }
```

JS 端通过 `task` 通道回传补全结果：

```json
{ "type": "command.tabComplete", "params": { "callbackId": "cb_51", "completions": ["arg1", "arg2"] } }
```

补全超时策略：推荐 1 秒，超时返回空列表。

---

## Native Service

插件可携带原生程序（Go/Rust/C++ 等）并通过 `service` 通道调用。详见 [Native Service 规范](native-service/index.md)。要点：

- 二进制放在 `assets/`（经 `getAssetsPath()` 注入命名空间）
- `registerNativeService` 按平台（os + arch）提取并 spawn 子进程
- 子进程通过 TCP JSON line 与运行时通信（ready / request / response / publish）

---

## 合格运行时检查清单

实现一个 Yeow 兼容运行时需要处理：

- [ ] **包结构解析**：读 ZIP（yeow.json、.yeow/main.js、assets/；可选 dev.json），JAR 与 `.yeow.zip` 同构
- [ ] **同名唯一**：插件名冲突时拒绝加载并警告（自动扫描 / 命令 / 宿主机制途径一致）
- [ ] **权限模型**：解析 yeow.json `computedPermissions`；`fs:server.*`、`fs:outer.*`、`http:*`、`service:registerNative`、`assets:extract` 默认拒绝（`fs:plugin.*` 免声明）；未声明调用返回 `Permission denied: <node>`
- [ ] **加载消息**：插件加载成功时输出加载消息（含插件名、版本、权限声明）
- [ ] **JS 引擎**：ES2023+，支持 `Promise`/`WeakRef`/`FinalizationRegistry`/`Uint8Array`
- [ ] **原生注入**：`$_send`、`__plugin`、`$dev`
- [ ] **引导脚本**：`$send`、`_registerCallback`/`_unregisterCallback`、`console`、定时器、`fetch`、`$hm`
- [ ] **消息循环**：拉消息 → `$hm` 分发 → 微任务 → GC 队列刷新
- [ ] **生命周期**：INIT / LOAD / DISABLE / RELOAD，`unloadDone` 确认
- [ ] **回调系统**：`cb_N` 注册表、persistent 语义、`{"t":"cb","p","r"}` 投递
- [ ] **调度器**（推荐）：三级优先级、时间片预算、可选自动降级/空闲自旋
- [ ] **任务执行器**：`task` type → 宿主平台操作，同步/异步语义
- [ ] **事件桥**：subscribe/unsubscribe、事件数据提取（仅基本类型）、event.complete 应用
- [ ] **命令桥**：register/execute/tabComplete
- [ ] **通道实现**：timer / fs / http / assets / service / log / now / dir / debug / lifecycle
- [ ] **资源访问**：按 `assets/<命名空间id>/` 读取 JAR 内资源
- [ ] **错误处理**：JS 异常捕获、`debug` 通道上报
- [ ] **健康检测**（推荐）：`debug` ping-pong 心跳、回调超时告警
- [ ] **热重载**（开发环境）：RELOAD → unloadDone → 新上下文 → 新代码；生产卸载采用相同逻辑（5s 强制终止）
