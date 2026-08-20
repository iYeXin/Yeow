# 运行时环境能力

面向 Yeow 插件开发者的**运行环境速览**：你的插件代码运行在一个**每插件独立的 QuickJS 单线程**里（Java 侧），不是浏览器、也不是完整 Node.js。本文说明“你有哪些能力、哪些没有”，以及与标准浏览器 / Node 环境的差异。底层细节见 [运行时环境标准](specifications/runtime/index.md)。

## 线程模型与异步

- 每个插件一个 **QuickJS 执行线程（单线程）**，事件 / 命令 / 回调 / 定时器都在这条线程上串行执行。
- 插件代码用 **`async` / `await`** 写异步：不 `await` 的阻塞调用（如 `xxxSync`、属性同步读取、同步 `$send`）会**卡住整条 JS 线程**——期间无法处理事件 / 命令（可能触发 `event.timeout` 告警）。**事件处理器 / 高频场景务必用异步 API**（`await xxx()`）。
- 真正的 IO（网络、文件、压缩）在运行时侧的 `ioExecutor` / 调度器执行，不占用你的 JS 线程。

## 全局可用（内置，无需 import）

| 能力                                                            | 说明                                                                                                                                                             |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `$send(channel, payload)`                                       | **唯一** JS→运行时通信入口（自动 JSON 序列化）。底层 `$_send` 属内部实现，**已被 init.js 闭包持有并从全局移除**——直接引用报 `ReferenceError`，只能使用 `$send`。 |
| `fetch(url, init?)`                                             | HTTP 客户端（Promise）。响应为 `Response`，提供 `text()` / `json()` / `base64()` / `arrayBuffer()`。需声明 `http:requestAsync` 权限。                            |
| `TextEncoder` / `TextDecoder`                                   | **utf-8** 编解码（Web 语义；环境要求存在）。为**同步** API。                                                                                                     |
| `setTimeout` / `clearTimeout` / `setInterval` / `clearInterval` | Web 定时器语义。                                                                                                                                                 |
| `console.log/warn/error/info`                                   | 日志（自动加 `[插件名]` 前缀）。                                                                                                                                 |
| `Promise`,`JSON`,`Map`/`Set`/`Symbol`/`Proxy`/`Reflect` 等      | 标准 ECMAScript。                                                                                                                                                |
| `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()`   | 引擎原生 Base64（ES2025 SecU8）。                                                                                                                                |
| `__plugin`                                                      | 插件元信息（name/version/author，只读）。                                                                                                                        |
| `__yeow*`（如 `__yeowInitCbs`、`__yeowGcQueue`）                | 运行时内部生命周期钩子，一般**不需要直接接触**。                                                                                                                 |

> 绝大多数能力建议通过 **yeow-api**（`player`/`world`/`fs`/`http`/`util`/`pdc`/…）使用，而非裸 `$send`。全局仅 `$send` / `fetch` / `TextEncoder` / `TextDecoder` / 定时器 / `console` 是“基础设施”。

## 与浏览器环境的差异

| 浏览器有                               | Yeow 运行时                                                      |
| -------------------------------------- | ---------------------------------------------------------------- |
| DOM / `window` / `document` / `canvas` | **无**——纯逻辑执行环境，没有页面                                 |
| `XMLHttpRequest` / `WebSocket`         | **无**全局客户端；HTTP 用全局 `fetch`（或 yeow-api `request`）； |
| `crypto`（SubtleCrypto）               | **无**                                                           |
| `localStorage` / `IndexedDB`           | **无**——持久化用 yeow-api `pdc` / `fs`                           |
| `atob` / `btoa`                        | **无**——用原生 `Uint8Array.toBase64()` / `fromBase64()`          |

## 与 Node.js 环境的差异

| Node 有                         | Yeow 运行时                                                                            |
| ------------------------------- | -------------------------------------------------------------------------------------- |
| `require` / `module`（CJS）     | **无**——Yeow 产物经 esbuild 打包为单个 IIFE，源码常用 `import`（ESM 语法，构建期打包） |
| `process` / `global`            | **无** `process`；全局用 `globalThis` / `__plugin`                                     |
| `Buffer`                        | **无**——一律用 `Uint8Array`（Base64 按原生）                                           |
| `fs` / `http` / `path` 原生模块 | **无** Node 模块——用 yeow-api 的 `fs` / `http` / `path`（异步、经运行时通道）          |
| `__dirname` / `__filename`      | **无**——资源路径用 yeow-api `assets`/`getAssetsPath` 或 `__plugin`                     |
| 阻塞式同步 I/O                  | **不推荐**——同步变体（`xxxSync`）会卡 JS 线程；高频/事件场景用异步                     |

## 内置类型差异

- **没有 `ArrayBuffer` 用法限制**：`fetch(...).arrayBuffer()` 返回标准 `ArrayBuffer`；Base64 转换用原生 `Uint8Array.fromBase64` / `toBase64`。
- **文件 / HTTP 二进制**：yeow-api 的 `fs.readFile` / `http.request` 默认返回 `Uint8Array`（二进制优先，`'utf8'` / `'base64'` 编码可选）——与 Browser/Node 的“默认字符串”不同，详见 [FS](api/fs.md) / [HTTP](api/http.md)。

## 建议

- **面向插件开发**：优先 TS + yeow-api（类型完整，杜绝“编造 API”），全局能力只作兜底。
- **性能**：**同步** UTF-8 编解码直接用 `TextEncoder` / `TextDecoder`（性能最好——小载荷纯 JS 直转、零往返）；大规模**非阻塞**编解码用 yeow-api 的 `stringToBytes` / `bytesToString`（异步，ioExecutor 执行）。
- **权限**：敏感能力（HTTP、服务器文件、插件管理等）需在 `yeow.config.json` 声明，见 [权限与原生服务可信性](permissions.md)。
