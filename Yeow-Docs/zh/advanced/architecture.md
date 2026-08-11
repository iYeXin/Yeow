# 架构与线程模型

> 架构总览：包结构、启动流程、线程模型、插件实体抽象、Worker（虚拟插件）、开发模式错误回显、资源路径机制（getAssetsPath）。

## 架构总览

```
    ┌─────────────────────────────────────────────────┐
    │          插件包 .yeow.zip / JAR (ZIP)            │
    │  ┌───────────────────────────────────────────┐  │
    │  │ .yeow/main.js     (esbuild 打包的 JS)      │  │
    │  │ assets/<id>/      (资源，按命名空间分目录)  │  │
    │  │ yeow.json         (元信息 + 权限 + native) │  │
    │  │ plugin.yml        (Paper 系元信息，JAR 需)  │  │
    │  └───────────────────────────────────────────┘  │
    └──────────────────────┬──────────────────────────┘
                           │ YeowRuntime.registerPlugin()
                           ↓
    ┌─────────────────────────────────────────────────┐
    │               Yeow Runtime (Java)                │
    │                                                  │
    │  ┌──────────────────┐  ┌─────────────────────┐  │
    │  │  PluginEntity 1   │  │  PluginEntity 2     │  │
    │  │  (PluginThread)   │  │  (适配器 / Worker)  │  │
    │  │  QuickJS + 消息    │  │  消息驱动循环        │  │
    │  │  驱动循环          │  │  fs/http/assets 自处 │  │
    │  │  fs/http/assets   │  │  理                 │  │
    │  └───────┬──────────┘  └──────┬──────────────┘  │
    │          │                    │                 │
    │  ┌───────┴────────────────────┴──────────────┐  │
    │  │  Scheduler (每 tick)                      │  │
    │  │  三级队列: HIGH / NORMAL / LOW            │  │
    │  │  时间片预算 + 自动降级                     │  │
    │  └────────────────┬──────────────────────────┘  │
    │                   │                             │
    │  ┌────────────────┴─────────────────────────┐  │
    │  │  EventBridge                              │  │
    │  │  Paper 系事件 → 插件 → applyMods()         │  │
    │  │  （未注册自动注册；无订阅自动跳过）        │  │
    │  └──────────────────────────────────────────┘  │
    └──────────────────────┬──────────────────────────┘
                           ↓ Paper 系 API
    ┌─────────────────────────────────────────────────┐
    │                    PaperMC                      │
    └─────────────────────────────────────────────────┘
```

### 包结构

| 包              | 语言       | 作用                                                           |
| --------------- | ---------- | -------------------------------------------------------------- |
| `yeow-api`      | TypeScript | 开发期 npm 依赖，提供 OOP 封装。esbuild 打包进 `.yeow/main.js` |
| `create-yeow`   | Node.js    | CLI 脚手架，生成项目模板 + 构建脚本                            |
| `yeow-runtime`  | Java       | Paper 系插件，管理 QuickJS 引擎和 Paper 系 API 桥接               |
| `yeow-template` | Java       | 空 JAR 骨架，构建时注入 JS 代码                                |

## 启动流程

Paper 系加载插件时：

```
Paper 启动
  → YeowRuntime.onLoad()
    → 读取 init.js → 运行时引导代码
    → 读取 plugins/Yeow/runtime/config.yml → 调度器配置
  → Bootstrap.onLoad() [每个模板 JAR 插件]
    → 读取 .yeow/main.js → userCode
    → 读取 yeow.json → 插件元信息 + 权限声明
    → YeowRuntime.registerPlugin(jarPath)
      → 创建 PluginThread(name, jarPath, userCode, permissions)
      → 线程启动
  → YeowRuntime.onEnable()
    → 自动扫描 plugins/Yeow/*.yeow.zip → registerPlugin（与 JAR 行为一致）
    → 注册 /yeow 管理命令
    → 注册每 tick 调度器
    → 向每个插件发送 {t:"LOAD"} 消息
```

JS 线程启动：

```
PluginThread.run()
  ① ctx = QuickJSContext.create()
  ② inject() → $_send
  ③ ctx.evaluate(init.js)    → console, _cbs, fetch, $hm
  ④ ctx.evaluate(userCode)   → registerCommand, eventOn, onInit/onLoad 注册
  ⑤ 直接调 $hm({t:"INIT"})   → onInit 回调执行（不入队，保证先于所有消息）
  ⑥ 消息循环开始
  ...
  ⑦ 收到 {t:"LOAD"}         → onLoad 回调执行
```

## 线程模型

| 线程                     | 职责                                               |
| ------------------------ | -------------------------------------------------- |
| **Paper 系主线程**        | 每 tick 50ms 调度 Scheduler.tick()，处理游戏任务   |
| **JS 线程**（每插件）    | 运行插件 JS，处理消息循环，直接处理 fs/http/assets |
| **Timer 线程**（每插件） | 定时器到期后发消息到 JS 线程                       |
| **Fetch 线程**           | HTTP 请求（每次请求一个线程）                      |

消息队列（MsgQueue，**消息驱动**）：

```
Java → 插件: postMessage(msg) → 入队（原子）→ 唤醒插件消息循环
          插件线程阻塞等待消息（零轮询）；收到即处理；处理完有剩余立即取，
          无剩余回到阻塞等待（"消息循环停止"，仅语义上的等待态）
JS  → Java: $_send 通道消息 → Scheduler tick() 处理 game 任务 / 插件线程直接处理 fs 等
```

## 插件实体抽象

运行时以 **`PluginEntity`** 接口看待每个插件：可接收消息（`postMessage`）、有生命周期（`start` / `stopAndWait` / `reload`）与行为指标（`ping()` 心跳往返）。JS 的特殊性（QuickJS 上下文、`$hm` 消息协议、init.js）只存在于 JS 适配器（`PluginThread`）内；调度器 / 事件桥 / 命令桥 / Service / Profile 均只依赖该接口：

- **调度器**只认插件名（提交任务、回复回调），不感知执行引擎
- **Profile** 通过 `ping()` 统一采集响应延迟，in-flight 管理由适配器负责
- **虚拟插件**：实现 `PluginEntity` 的 Worker 实体（[Worker API](/api/worker)）接入全链路；`isVirtual()` 标记用于性能统计与告警的区分

## Worker（虚拟插件）

Worker 是**虚拟插件**——`PluginEntity` 的一个具体实现（`WorkerThread`），为主插件提供**独立线程 + 独立 QuickJS 上下文**的并行执行单元。API 用法见 [Worker API](/api/worker)，通道协议见 [worker 通道规范](/specifications/message/worker)。

### 本质

- **它是插件**：以 `<主插件>.<worker名>` 注册为插件实体（全局唯一）——调度器 / 事件桥 / 命令桥 / Service / Profile 全部按普通插件对待
- **它是虚拟的**：`isVirtual() = true`——`/yeow` 管理命令不覆盖它；profiler 报告与告警中带 `(worker of <主插件>)` 标记
- **依附主插件**：不脱离主插件运行——主插件卸载/热重载时连带卸载（彻底销毁句柄）；Worker 内部也**禁止再创建 Worker**
- **共享而非独立**：没有自己的数据目录与权限——fs 的 plugin 级 base 指向主插件数据目录（`plugins/<主插件>/`），assets 通道同一命名空间，权限直接继承主插件（无独立声明）
- **生命周期**：`createWorker` 仅注册（拿到句柄）；`load()` 才执行 `init.js → worker-inject.js → Worker 代码 → INIT → LOAD`；**无法销毁，只能卸载**——`unload()` 物理销毁 JS 上下文并清理其事件/命令/服务/任务，句柄保留，可重新 `load()`

### 实现

```
主插件 PluginThread (JS) ──$_send('worker')──► Java 主线程
   │   create: 注册句柄（不启动）
   │   load:   registerPluginEntity（进 plugins map + profiler）→ WorkerThread.start()
   │   post:   投递 {"t":"cb","p":"__workerMessage","r":msg} 到 Worker 队列
   │   reload/unload: 生命周期控制
   └── postToMain（Worker 内部通道）──► 主插件 JS 侧该 Worker 的 onMessage 回调
```

`WorkerThread` 是 `PluginThread` 的独立变体：

- 独立 QuickJS 上下文 + 线程（`yeow-worker-<主插件>.<worker名>`）+ MsgQueue，错误上报带 `origin`（worker 名）
- `$_send` 全通道：
  - `task` → 调度器（`_plugin` = 注册名，独立统计/purge）
  - `timer` → 独立 ScheduledExecutorService（随 Worker 清理）
  - `fs`/`assets`/`http` → **委托主插件处理**（共享数据目录、权限、资源；http 监听随主插件生命周期）
  - `service` → 独立处理（以注册名注册服务）
  - `worker` → 仅接受 `postToMain`（嵌套创建被拒绝）
- 引导脚本：init.js（标准环境）+ worker-inject.js（`__workerId`、内部消息回调 `__workerMessage`）
- `ping()` 心跳接入 Profile（与普通插件一致）

### 接入其他系统

| 系统       | 接入方式                                                                                                  |
| ---------- | --------------------------------------------------------------------------------------------------------- |
| 调度器     | Worker 内 `call/post` 走 task 通道，`_plugin` 注入注册名——独立队列统计与 purge                            |
| 事件桥     | `eventOn` 以注册名订阅（EventBridge.subs 按注册名）；卸载时 `unsubscribeAll`                              |
| 命令桥     | `registerCommand` 以注册名注册；卸载时 `unregisterAll`                                                    |
| Service    | `registerService` 以注册名注册（`ServiceManager.requestPlugin` 经实体投递）；卸载时 `purgePluginServices` |
| Profile    | `registerPluginEntity` 时注册心跳统计；`isVirtual()` + `source()`（主插件名）在报告/告警中标记            |
| /yeow 管理 | `realPluginNames()` 过滤 `isVirtual()`——unload/reload/uninstall/track/tabComplete 均不覆盖                |
| 生命周期   | 主插件 `cleanupResources` 逐个 `unloadPlugin(worker)`（完整清理）+ `workers.clear()`                      |

### 开发工具链

`yeow.config.json` 的 `dev.worker` 声明 Worker 打包：

```json
{ "dev": { "worker": [
    { "name": "web-worker", "entry": "worker/web-worker/index.ts", "dist": "assets/worker/web-worker.js" }
] } }
```

- **打包顺序**：`build.js` 先对每个 Worker `esbuild`（`entry → assets/<rootId>/<dist>`，dev 带 sourcemap），**再打包主插件**——主插件经 `getAssetsPath(dist)` 读取 Worker 产物，构建期依赖（`yeow-dev`）按主插件命名空间注入，Worker 与主插件共用 `yeow-api`
- **热重载**：dev-server 监听 Worker `entry` 所在目录——源码变化 → 重建（Worker + 主插件）→ hot-reload → 主插件重新 `createWorker`/`load`，Worker 随之重建
- **错误回显**：js-error 消息携带 `origin`（`main` 或 worker 名）——dev-server 按 `origin` 选择对应 source-map（`dist/.dev/.assets/<id>/worker/<name>.js.map`）反解，输出 `JS Error in Worker <name>` + 代码上下文
- **依赖包作者**：在真实项目中调试，测试成功后自行将打包后的 Worker 文件放入资源目录（`dist` 声明路径）

## 开发模式错误回显

开发模式（`npm run dev`，运行时带 `-Dyeow.dev=true`）下，插件错误经过完整链路回显到终端：

```
插件 JS 错误 → init.js reportError / 未捕获异常 → $_send('debug', {t:'reportError'})
  → PluginThread 解析（message/stack/fileName/line/column）
  → dev WebSocket → dev-server（create-yeow 内置，端口 17368）
  → source-map 库把打包后位置反解回 src/ 原始源码
  → 终端输出：错误行 ±3 行上下文 + → 定位符 + 异步调用链
```

**异步栈追踪**（仅开发模式）：每个回调/异步请求在**注册时**捕获用户调用栈，出错时以 `--- cb registered at ---`、`--- promise chain ---`、`--- outer callback ---` 分段附加到错误——多层嵌套回调也能还原来源。`console.log` 不受影响；生产模式无任何栈捕获开销（错误只输出到服务器日志）。

- 手动上报：`logError(e, context?)`（`catch` 块中主动上报，享受同样的 source-map 定位）
- 详见 [CLI 参考 - 调试体验](/cli#调试体验)
