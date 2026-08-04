# Yeow 运行时环境标准

本规范描述 Yeow 插件代码运行时的 JavaScript 环境。任何实现本规范的运行环境均可执行 Yeow 插件，无关内部实现细节。

---

## 语言标准

实现必须提供 **ES2023** 及以上版本的 JavaScript 运行时，至少包含以下特性：

- `Promise`、`async`/`await`、`Promise.all`、`Promise.race`
- `Symbol`、`Proxy`、`Reflect`
- `WeakRef`、`FinalizationRegistry`（用于资源回收）
- `ArrayBuffer`、`Uint8Array`（二进制数据处理）
- `JSON.parse` / `JSON.stringify`
- `Error`、`SyntaxError`、`TypeError`

> **官方实现引擎版本**：Paper/Bukkit 的 `yeow-runtime` 使用 **QuickJS 2026-06-04**（[iyexin/quickjs](https://github.com/iyexin/quickjs) fork，上游 [bellard/quickjs](https://bellard.org/quickjs/)），额外提供：resizable `ArrayBuffer`、`ArrayBuffer.prototype.transfer`、`Iterator` 对象与 set 方法、`Math.sumPrecise()`、正则重复具名组，以及 ES2026 的 `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()`（参见 [https://tc39.es/ecma262/multipage/indexed-collections.html#sec-uint8array](https://tc39.es/ecma262/multipage/indexed-collections.html#sec-uint8array)）。

---

## 回调系统

`_registerCallback` 是 Yeow 异步通信模型的基础原语。所有需要跨线程异步结果的操作（`task` 通道、`timer` 通道、`fs` 通道、`assets` 通道、`http` 通道）均基于同一回调机制。

### 注册与注销

```
_registerCallback(fn, options?) → string    // 返回回调 ID "cb_N"
_unregisterCallback(id)                      // 取消回调
```

**`persistent` 选项：**

| 值              | 行为                                           | 典型用途                                             |
| --------------- | ---------------------------------------------- | ---------------------------------------------------- |
| `false`（默认） | 回调首次调用后自动注销                         | `post()` 的 Promise resolve、`fetch()` 的请求回调    |
| `true`          | 回调可多次调用，直到显式 `_unregisterCallback` | 事件处理器（`eventOn`）、定时器循环（`setInterval`） |

### 回调 ID 格式

`"cb_N"`，`N` 为全局自增整数。每次 `_registerCallback` 调用均生成全局唯一的 ID。

### cb 字段：打通所有通道的异步路径

任何 `$send(channel, payload)` 调用，若 `payload` 中包含 `cb` 字段（值为回调 ID），含义为：**"此操作异步执行，结果通过回调投递，`$send` 立即返回 `null`"**。

```json
// 异步请求
{ "type": "player.get", "params": {...}, "cb": "cb_42" }
// $send 立即返回 null，JS 不阻塞
```

若 `payload` 中**不含** `cb` 字段，含义为：**"此操作同步执行，`$send` 阻塞直到完成并直接返回结果"**。

### 回调投递协议

当异步操作完成（或事件发生、定时器到期），运行时通过消息队列向 JS 投递回调消息。消息格式：

```json
// 当前标准统一格式
{ "t": "cb", "p": "<callbackId>", "r": <result> }
```

此外，运行时与插件在加载和卸载期间互动是通过定义的消息类型，并非通过 `cb` 投递协议：

- `INIT` 消息 → 执行 `__yeowInitCbs`（无回调）
- `LOAD` 消息 → 执行 `__yeowLoadCbs`（无回调）
- `DISABLE` 消息 → 执行 `__yeowUnloadCbs`（无回调）
- `RELOAD` 消息 → 执行 `__yeowUnloadCbs`（无回调）

JS 端处理流程：

```
运行时投递 { "t": "cb", "p": "cb_42", "r": ... }
  → 查找 _cbs["cb_42"]
  → 调用 fn(result)
  → 若 !persistent，删除 _cbs["cb_42"]
```

### 异步流程示例

以 `post('player.get', { identifier })` 为例，展示一个依赖通话的系统是如何联合运作的：

```
1. JS 调用 post()
2. post() 内部注册回调 → 获得 "cb_42"
3. $send('task', { type:"player.get", params:{identifier}, cb:"cb_42" })
4. 运行时发现 cb 存在 → submitGameAsync → 立即返回 null
5. $send 返回 null → post() 返回未 resolve 的 Promise
6. JS 执行 await 或继续处理其他代码
7. ... 下一个 tick ...
8. 调度器取出任务 → 执行 player.get → 回调 λ → queue.sendJs(cbMessage)
9. JS 端消息循环收到 → $hm → _cbs["cb_42"].h(result) → Promise resolve
10. 微任务队列处理 → await 恢复执行
```

---

## 事件循环与异步协作

运行时使用事件循环模型来处理异步操作。插件的代码依赖于**即时消息 → 异步回调 → 微任务 → GC → 消息获取**模型的正常运行。

### 循环模型

```
1. 等待下一条运行时消息被投递到 JS 环境
2. 调用全局消息分发函数，处理该消息
3. 清空微任务队列（queueMicrotask 回调、Promise.then 回调、FinalizationRegistry 回调）
4. 清空 GC 回收队列（__yeowGcQueue），发送 gc-collect 消息
5. 回到步骤 1
```

### 微任务的重要性

微任务是以下功能的基础：

- `await` 暂停和恢复（`await` 本身对 V8 而言是一次微任务挂起）
- `Promise.then` 回调
- `FinalizationRegistry` GC 回调（将不再使用的资源 ID 推入 `__yeowGcQueue`）

当上一条消息还在处理中时，回调函数可能注册新的回调（如第二个 `post()`，本质上是重复此异步调用过程）。微任务确保这些关联操作在当前循环中按正确的顺序执行——在返回等待新消息之前。

### 消息处理与微任务的计时关系

一个完整的消息循环的步骤：

```
[1] 从运行时拉到新消息
        → 调用对应处理器（可能是事件/异步回调/定时器）
        → 处理器同步执行完成
[2] while (微任务队列非空)
        → Promise.then / finalization
        → 如果在 then 中又注册了新异步任务 → 再次提交消息到运行时
[3] 如 __yeowGcQueue 非空 → 发送 gc-collect
[4] 回到 [1]，继续拉消息
```

**注意：**[2] 中注册的新异步任务可能在任何时间由运行时进行处理——无需等待 JS 主动拉取。关键是在 [1] 完成和 [3] 执行后，新的结果已经在运行时准备好，下一次循环就会拉取。

---

## 每通道 cb 语义

以下是每个通道支持 `cb` 字段的行为规范：

| 通道        | 支持 cb | 同步行为                                         | 异步（含 cb）行为                                          |
| ----------- | ------- | ------------------------------------------------ | ---------------------------------------------------------- |
| `task`      | 是      | `$send` 阻塞，直到调度器执行完任务并返回         | `$send` 立即返回 `null`，调度器完成后 sent 回调            |
| `timer`     | 是      | —                                                | 总是异步（`$send` 立即返回），定时器到期时发送回调         |
| `fs`        | 是      | `$send` 阻塞，直到 IO 完成                       | `$send` 立即返回 `null`，IO 线程完成后发送回调             |
| `http`      | 是      | 请求本身同步完成                                 | `requestAsync` 中包含 `cb`，在 HTTP 线程中处理完后发送回调 |
| `assets`    | 是      | `$send` 阻塞，直到 IO 完成                       | `$send` 立即返回 `null`，IO 线程完成后发送回调             |
| `lifecycle` | 否      | `$send` 返回 `null`（fire-and-forget）           | —                                                          |
| `log`       | 否      | `$send` 返回 `null`（fire-and-forget）           | —                                                          |
| `now`       | 否      | `$send` 直接返回时间戳字符串                     | —                                                          |
| `dir`       | 否      | `$send` 直接返回目录路径字符串                   | —                                                          |
| `debug`     | 否      | `$send` 返回 `null`（fire-and-forget）           | —                                                          |
| `service`   | 是      | 注册/请求/订阅/发布。request 异步，register 同步 | —                                                          |

---

## 全局变量

以下全局变量在插件代码执行前由运行时注入，所有变量挂载在 `globalThis` 下。

### `$send(channel, payload)`

```ts
(channel: string, payload: any) => any | null
```

JS 与运行时的唯一通信入口。

- `channel`：字符串，指定操作分类。可选值见 [消息通道](#消息通道)。
- `payload`：对象，运行时在传输前将其序列化为 JSON。

行为：
- 对于 `task` 通道的调用：若 `payload` 中包含 `cb`（回调 ID），运行时必须**立即返回 `null`**，不阻塞 JS 执行。任务结果通过回调通道异步返回。
- 对于 `task` 通道的调用：若 `payload` 中不包含 `cb`，运行时必须**阻塞 JS 执行**，直到任务完成并同步返回结果。
- 对于 `fs`、`assets` 通道：含 `cb` 时异步执行，不含时同步执行。
- 对于 `timer` 通道：始终异步。
- 对于 `http` 通道：`requestAsync` 包含 `cb` 异步执行，`request` 同步。

任务执行顺序：
- `task` 通道的消息按 **HIGH → NORMAL → LOW** 优先级顺序调度
- 所有 `task` 通道的操作在单一线程中串行执行，不允许并发

### `$dev`

```ts
boolean
```

是否处于开发模式，由运行时启动参数控制（默认 `false`）。

影响：
- `true` 时 `_registerCallback` 会捕获回调注册时的调用栈，出错时附加到异常信息
- `true` 时运行时会向外部发送 JavaScript 错误信息（如 source-map 解析）

### `_registerCallback(fn, options?)`

```ts
(fn: (result: any) => any, options?: { persistent?: boolean }) => string
```

注册一个回调函数，返回唯一回调 ID，格式为 `"cb_N"`（`N` 为自增整数）。

这是 Yeow 异步模型的核心原语。任何需要异步获取结果的操作都通过此函数注册回调，然后将回调 ID 作为 `cb` 字段传入 `$send` 的 payload 中。

**参数：**

- `fn` — 回调函数。输入参数为运行时投递的 `r` 字段（结果的任意 JSON 可序列化值）。如果 `r` 中含有 `err` 字段，表示操作失败。
- `options.persistent`（默认 `false`）：
  - `false` — 回调首次调用后自动注销。适用于 `post()`、`fetch()`、`setTimeout` 等单次操作。
  - `true` — 回调可多次调用，直到显式 `_unregisterCallback`。适用于事件处理器、`setInterval`、Tab 补全器等持续性操作。

**栈追踪（$dev 模式）：**

在 `$dev` 为 `true` 时，注册时会捕获调用栈（`new Error().stack`）。当回调执行中抛出异常时，该栈信息附加到异常的 `stack` 中，格式为 `"    --- cb registered at ---\n"` + 原始栈。这帮助开发者在 source-map 中找到异步回调的注册位置。

**Promise 自动展开：**

若回调函数返回一个 Promise（即 `result.then` 为函数），回调包装器会自动附加 `.then(null, ex => ...)` 错误处理，将未捕获的 Promise 拒绝转为增强的栈信息。

### `_unregisterCallback(id)`

```ts
(id: string) => void
```

取消已注册的回调。调用后对应 `cb_*` ID 失效，后续投递到该 ID 的消息将被忽略。

### `fetch(url, init?)`

```ts
(url: string, init?: {
  method?: string;
  headers?: Record<string, string>;
  body?: string | null;
}) => Promise<Response>
```

HTTP 客户端，行为尽可能符合 WHATWG Fetch 标准。

**内部实现依赖：** `_registerCallback` + `$send('http', {t:'requestAsync', p:{url,method,headers,body,cb}})`。回调触发时解析为 `Response` 对象。

返回的 `Response` 对象结构：

```ts
interface Response {
  ok: boolean;           // status >= 200 && status < 300
  status: number;        // HTTP 状态码
  statusText: string;    // "OK" 或 "Error"
  headers: { get(name: string): string | undefined };
  text(): Promise<string>;
  json(): Promise<any>;
}
```

**注意：** 实现不要求支持 `ReadableStream`、`blob()`、`arrayBuffer()` 等高级特性。

> [!WARNING]
> **`fetch` 依赖 `http:requestAsync` 权限**：`fetch` 的实现基于 http 通道的 `requestAsync`（`$send('http', {t:'requestAsync', ...})`）。插件未声明 `http:*`（或 `http:requestAsync`）权限时，运行时必须返回 `Permission denied: http:requestAsync`（经回调投递），`fetch` 的 Promise reject。见[通道权限](#通道权限敏感节点默认拒绝)。

### `setTimeout(fn, ms)` / `clearTimeout(id)` / `setInterval(fn, ms)` / `clearInterval(id)`

```ts
setTimeout(fn: () => void, ms: number): string
clearTimeout(id: string): void
setInterval(fn: () => void, ms: number): string
clearInterval(id: string): void
```

定时器函数，行为符合 Web 标准。

- 返回唯一标识符（字符串），可用于 `clearTimeout`/`clearInterval`
- `ms` 精度为毫秒，不支持小于 1ms 的延迟
- **内部实现依赖：** `_registerCallback` + `$send('timer', {type:'timeout'|'interval', cb, delay})`
- `setInterval` 的回调以 `persistent: true` 注册，`clearInterval` 通过 `_unregisterCallback` + 停止定时器来实现
- 定时器回调投递格式：`{ "t": "cb", "p": "<id>", "r": null }`（空结果）

### `console`

```ts
console.log(...args: any[]): void
console.warn(...args: any[]): void
console.error(...args: any[]): void
console.info(...args: any[]): void
```

日志输出。运行时提供的 `console` 应自动添加前缀。

### `_getCurrentCbStack()`

```ts
() => string | null
```

仅在 `$dev` 为 `true` 时有效。返回当前正在执行的回调注册时的调用栈字符串。

**用途：** 在异步操作失败时（如 `post()` 的 Promise reject），出错点通常不在注册回调的代码行。此函数返回的栈信息用于增强异常堆栈，帮助开发者追踪原始调用位置。

### `__plugin`

```ts
{
  name: string;       // 插件名（来自 yeow.json）
  version: string;    // 版本（来自 yeow.json）
  author: string;     // 作者（来自 yeow.json）
}
```

当前插件的元信息。运行时从插件包中的 `yeow.json` 文件中读取，**只读**。

### `__yeowInitCbs`

```ts
(() => void)[]
```

`onInit` 回调队列。运行时在插件代码执行完毕后、消息循环开始前，通过 `INIT` 消息触发遍历执行。

此时调度器尚未启动，**不可**调用 `call()` 执行同步游戏操作。可执行 `console.log`、`fetch`、`fs.*` 等非调度器操作。

### `__yeowLoadCbs`

```ts
(() => void)[]
```

`onLoad` 回调队列。运行时在调度器就绪后（即游戏状态可安全访问时），通过 `LOAD` 消息触发遍历执行。

此时可安全调用所有 `call()` 同步游戏操作。

### `__yeowUnloadCbs`

```ts
(() => void)[]
```

`onUnload` 回调队列。运行时在插件被禁用或热重载时，通过 `DISABLE` 或 `RELOAD` 消息触发遍历执行。

所有 `onUnload` 回调执行完毕后，运行时收到 `lifecycle` 通道的 `unloadDone` 消息后关闭插件线程。

### `__yeowEventHandlers`

```ts
Record<string, Array<{ cbId: string; handler: Function; manualRelease?: boolean }>>
```

事件处理器注册表。应用代码通过 `_registerCallback`（persistent: true）注册事件处理器，并将回调 ID 通过 `event.subscribe` 任务提交到运行时。运行时投放事件时无需直接操作此表。

每个条目保存了回调 ID（用于事件 complete 时发送 `event.complete`）、处理器函数引用（用于 `eventOff` 的引用比较）、以及是否启用手动 complete 模式。

### `__yeowGcQueue`

```ts
string[]
```

资源回收队列。

- 应用层代码（如 `InstanceId`）将不再被引用的资源标识符推入此队列
- 运行时**必须在每次消息循环完毕后**清空该队列，并为队列中的每个标识符发送 `lifecycle` 通道的 `gc-collect` 消息
- 运行时应使用 `FinalizationRegistry` 或等效机制驱动此队列的填充
- `FinalizationRegistry` 回调在微任务阶段执行，正好在事件循环的步骤 3 中处理

> [!NOTE]
> Base64 编解码使用引擎原生的 **ES2026 `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()`**：

> ```js
> const b64 = new Uint8Array([1, 2, 3]).toBase64(); // "AQID"
> const bytes = Uint8Array.fromBase64(b64);         // Uint8Array(3) [1, 2, 3]
> ```

---

## 消息通道总览

`$send` 的第一个参数 `channel` 决定了消息的路由方式。支持的通道如下：

| 通道        | 用途                    | 调度方式                     | 支持 cb | 规范文档                                  |
| ----------- | ----------------------- | ---------------------------- | ------- | ----------------------------------------- |
| `task`      | 游戏任务                | 调度器队列，逐 tick 执行     | 是      | [task 模块规范](../task/index.md)         |
| `timer`     | 定时器                  | 独立定时线程                 | 是      | [timer 通道](../message/timer.md)         |
| `fs`        | 文件系统                | 直接处理。异步时使用 IO 线程 | 是      | [fs 通道](../message/fs.md)               |
| `http`      | HTTP 客户端/服务端      | 直接处理 / HTTP 线程         | 是      | [http 通道](../message/http.md)           |
| `assets`    | 内置资源                | 直接处理。异步时使用 IO 线程 | 是      | [assets 通道](../message/assets.md)       |
| `lifecycle` | 生命周期确认 / 资源回收 | 直接处理                     | 否      | [lifecycle 通道](../message/lifecycle.md) |
| `log`       | 日志                    | 直接处理                     | 否      | [log 通道](../message/log.md)             |
| `now`       | 纳秒时间戳              | 直接处理                     | 否      | —                                         |
| `dir`       | 插件数据目录路径        | 直接处理                     | 否      | —                                         |
| `debug`     | 调试 / 错误上报 / Ping  | 直接处理                     | 否      | —                                         |
| `service`   | 服务注册/请求/订阅/发布 | 直接处理 / 跨线程路由        | 是      | [service 通道](../message/service.md)     |

**通道分发原则：**
- `task` 通道的消息进入调度器，按优先级和时间预算逐 tick 执行。所有插件的任务统一调度。
- `timer` 通道的消息进入独立定时器线程，到期时投递回调。
- `fs`、`assets` 通道的消息含 `cb` 时在 IO 线程池中执行，不含 `cb` 时同步。
- `http` 通道的 `requestAsync` 在 IO 线程池中执行 HTTP 请求。
- 其余通道的消息直接由插件线程处理，不涉及额外线程。

### 通道权限（敏感节点默认拒绝）

运行时**必须**根据插件 `yeow.json` 的 `computedPermissions` 声明对以下通道执行权限检查（粒度 = 消息节点）：

| 默认拒绝的节点                 | 覆盖的操作                                                                                 | 声明示例                                      |
| ------------------------------ | ------------------------------------------------------------------------------------------ | --------------------------------------------- |
| `fs:server.*` / `fs:outer.*`   | fs 通道 `server` / `outer` 前缀节点（服务器根 / 任意路径）；`fs:plugin.*` 节点（插件数据目录）免声明 | `["fs:server.*"]` 或 `["fs:server.readFile"]` |
| `http:*`                       | http 全部操作（含 `fetch` 使用的 `requestAsync`）                                          | `["http:*"]` 或 `["http:requestAsync"]`       |
| `service:registerNative`       | 注册原生服务（spawn 子进程）                                                               | `["service:registerNative"]`                  |
| `assets:extract`               | 解压资源到磁盘                                                                             | `["assets:extract"]`                          |

规则：

- **节点概念**：权限只按**消息节点**（`channel:node`）考虑；节点名中的段（`fs:plugin.readFile` 的 `plugin`）是业务/访问范围命名，**不是层级**，不参与匹配
- **通配**：声明 `channel:*` 命中该通道全部节点；**整组通配** `channel:段.*` 命中该前缀全部节点；**节点级**：声明 `channel:段.op` 只命中该操作
- **默认允许**：上述默认拒绝节点之外的节点（`service:request`、`service:register`、`assets:read`、`assets:readBase64`、`fs:plugin.readFile` 等）无需声明
- **拒绝行为**：未声明调用返回 `Permission denied: <channel>:<op>`——同步调用以错误 JSON 返回；含 `cb` 的异步调用通过回调投递 `{"err": "Permission denied: <channel>:<op>"}`（JS 侧 Promise reject）
- `task` / `timer` / `log` / `now` / `dir` / `debug` / `lifecycle` 通道不受约束
