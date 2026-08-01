# 进阶知识

## 架构总览

```
    ┌─────────────────────────────────────────────────┐
    │              插件 JAR (ZipFile)                  │
    │  ┌───────────────────────────────────────────┐  │
    │  │ .yeow/main.js     (esbuild 打包的 JS)      │  │
    │  │ assets/            (静态资源)              │  │
    │  │ yeow.json          (元信息)                │  │
    │  │ plugin.yml         (Bukkit 元信息)         │  │
    │  └───────────────────────────────────────────┘  │
    └──────────────────────┬──────────────────────────┘
                           │ YeowRuntime.registerPlugin()
                           ↓
    ┌─────────────────────────────────────────────────┐
    │               Yeow Runtime (Java)                │
    │                                                  │
    │  ┌──────────────────┐  ┌─────────────────────┐  │
    │  │  PluginThread 1   │  │  PluginThread 2     │  │
    │  │  (QuickJS)        │  │  (QuickJS)          │  │
    │  │  fs, http, assets │  │  fs, http, assets   │  │
    │  │  直接处理          │  │  直接处理            │  │
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
    │  │  Bukkit 事件 → JS → applyMods()           │  │
    │  └──────────────────────────────────────────┘  │
    └──────────────────────┬──────────────────────────┘
                           ↓ Bukkit API
    ┌─────────────────────────────────────────────────┐
    │                    PaperMC                      │
    └─────────────────────────────────────────────────┘
```

### 包结构

| 包              | 语言       | 作用                                                           |
| --------------- | ---------- | -------------------------------------------------------------- |
| `yeow-api`      | TypeScript | 开发期 npm 依赖，提供 OOP 封装。esbuild 打包进 `.yeow/main.js` |
| `create-yeow`   | Node.js    | CLI 脚手架，生成项目模板 + 构建脚本                            |
| `yeow-runtime`  | Java       | Bukkit 插件，管理 QuickJS 引擎和 Bukkit API 桥接               |
| `yeow-template` | Java       | 空 JAR 骨架，构建时注入 JS 代码                                |

## 启动流程

Bukkit 加载插件时：

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
| **Bukkit 主线程**        | 每 tick 50ms 调度 Scheduler.tick()，处理游戏任务   |
| **JS 线程**（每插件）    | 运行插件 JS，处理消息循环，直接处理 fs/http/assets |
| **Timer 线程**（每插件） | 定时器到期后发消息到 JS 线程                       |
| **Fetch 线程**           | HTTP 请求（每次请求一个线程）                      |

消息队列（MsgQueue）：

```
Java → JS: queue.sendJs(msg) → JS 线程 pollJs(50ms) 读取
JS  → Java: queue.sendJava(msg) → Scheduler tick() 处理 game 消息
```

## 调度器

三级优先级队列 + 时间片预算机制。

```
tick() 每 50ms 调用:
  budget = tick-budget-ms (默认 20ms)
  deadline = now + budget

  ① HIGH   50% 预算 → 用完溢出给 NORMAL
  ② NORMAL 30% 预算 → 用完溢出给 LOW
                      → 同时检查自动降级（>200次/秒 → LOW）
  ③ LOW    20% 预算 → 不溢出
```

- 每个 tier 独立预算，用完后该 tier 的任务留到下一 tick
- 每次检查全局 deadline，超时即停止，不打断当前执行中的任务
- NORMAL 任务调用频率超过阈值时自动降级到 LOW，减少对 HIGH 的干扰

### 优先级参数

```js
// yeow-api 的 call/post 支持可选优先级
call('player.get', { id }, 'high')      // 高优先级
post('player.sendMessage', {...}, 'low') // 低优先级
// 默认 normal
```

## 异步 vs 同步

### 异步操作（默认 API）

```js
await player.sendMessage('Hello')
await world.setBlock(0, 65, 0, 'stone')
await entity.teleport(loc)
```

- 通过 `post()` → `$send` 的 async 路径提交
- 任务进入调度器队列，在未来的某个 tick 执行
- **不阻塞 JS 代码**，JS 线程可继续处理其他消息
- **受时间片预算约束**：如果当前 tick 的 HIGH/NORMAL 预算用尽，任务排队到下一 tick
- **跨插件统一调度**：所有插件的异步任务在同一调度器队列中按优先级执行
- 返回 `Promise`，通过回调机制在完成后 resolve

### 同步操作（Sync 后缀）

```js
player.sendMessageSync('Hello')
world.setBlockSync(0, 65, 0, 'stone')
entity.teleportSync(loc)
```

- 通过 `call()` → `$send` 的 sync 路径提交
- JS 线程阻塞在 `CompletableFuture.get(5s)`
- 高优先级：默认高优先级
- （`call`）会**阻塞 JS 线程**直到任务完成。阻塞期间，JS 线程无法处理其他消息，包括：

- **SYNC_CALLBACK**（事件）— 事件处理器无法执行
- **TAB_COMPLETE**（命令补全）— 补全请求无法响应
- **回调消息**（`post` 的结果）— 异步操作的 Promise 无法 resolve

如果事件或补全请求在同步操作执行期间发生，主线程（当前在 EventBridge 或 CommandTasks 中自旋等待）会持续自旋直到 JS 线程恢复。

少量调用同步 API 不会有问题，但是如果长时间同步操作（如大量循环 `setBlockSync`）会导致无法处理事件和补全请求。这在一般情况下没有问题，因为 JS 线程的阻塞不影响主线程。

但是一旦触发事件或补全请求，主线程长时间等待 JS 线程的返回结果，而这时 JS 线程正在长时间同步操作，事件循环无法被让出，补全请求和事件回调无法被处理，主线程等待直到超时，服务器报 "Can't keep up!"。

**推荐做法**：

| 场景                                 | 方案                                                 |
| ------------------------------------ | ---------------------------------------------------- |
| 大量重复操作（如填充方块、批量操作） | 使用异步 API + `await`，让 JS 事件循环能处理其他消息 |
| 必须在单 tick 内完成的少量操作       | 同步 API 无问题（例如读取几个方块、获取玩家属性）    |
| 需要分批次执行的长任务               | 见下文"手动分片"                                     |

```js
// ✅ 推荐：异步循环，不阻塞事件循环
for (let x = minX; x <= maxX; x++) {
    for (let y = minY; y <= maxY; y++) {
        for (let z = minZ; z <= maxZ; z++) {
            await world.setBlock(x, y, z, block);
        }
    }
}
```

### 手动分片

如果必须使用同步 API 且需要处理大量操作，可以使用 `setTimeout(fn, 0)` 将任务分片，主动让出事件循环：

```js
const blocks = [...];  // 大量方块操作
let i = 0;
function processChunk() {
    const end = Math.min(i + 100, blocks.length);
    for (; i < end; i++) {
        const [x, y, z, block] = blocks[i];
        world.setBlockSync(x, y, z, block);
    }
    if (i < blocks.length) setTimeout(processChunk, 0);
    else console.log('Done!');
}
processChunk();
```

每处理 100 个方块后主动让出事件循环，事件和补全请求可在间隙处理

### 同步操作的约束

同步调用会阻塞 JS 线程，期间无法处理事件、补全请求和其他回调。单次少量调用无碍，但长时间同步循环会导致：

- 事件处理器无法执行 → 主线程自旋等待直到超时（服务器报 "Can't keep up!"）
- 补全请求无法响应 → 玩家输入命令卡顿
- 异步操作的回调无法 resolve → Promise 挂起

需要大量操作时，优先异步 `await` 循环，或用 `setTimeout` 手动分片。

### 选择建议

| 场景                                   | 推荐                                |
| -------------------------------------- | ----------------------------------- |
| 发送消息、广播、设置方块               | 异步（默认）                        |
| 读取数据（Player.getSync、world.time） | 同步（`Sync` 后缀或属性访问器）     |
| 事件处理器中取消事件                   | 同步（`e.cancelled = true`）        |
| 命令执行器                             | 异步（`async executor`）            |
| 需要确保本 tick 完成的操作             | 同步（Sync 后缀）                   |
| 执行控制台命令                         | 异步优先（`await dispatchCommand`） |

## 调度器设计

Yeow 的调度器采用三级优先级队列 + 时间片预算 + 自动降级的架构，所有插件的游戏任务统一调度。

### 三级优先级队列

```
                ┌──────────────────┐
                │    HIGH Pool     │  ← 高优先级（`call`（同步任务） 和 `post` 带 `high` 参数）
                ├──────────────────┤
                │   NORMAL Pool    │  ← 默认优先级
                ├──────────────────┤
                │     LOW Pool     │  ← 自动降级或手动指定 `low`
                └──────────────────┘
```

每个 tick 的执行流程：

```
tick() 每 50ms 被 Bukkit 主线程调用:

  ① 第一轮（按预算比例分配）
     HIGH   50% 预算 → 用不完溢出给 NORMAL
     NORMAL 30% 预算 → 用不完溢出给 LOW
     LOW    20% 预算 → 不溢出，用完即止

  ② 贪婪阶段
     无视 tier 独立预算，按 HIGH → NORMAL → LOW 顺序
     尽可能多地执行，直到 deadline 耗尽或所有队列清空

  ③ 空闲自旋
     队列清空后，如果 deadline 还有剩余，进入自旋等待（默认 100μs）
     自旋期间不断检查三个队列，有新任务立即进入贪婪阶段执行
```

每个 tick 的总预算默认 20ms（`tick-budget-ms: 20`），由 `plugins/Yeow/runtime/config.yml` 配置。超过 deadline 的任务排队到下一 tick。

### 优先级参数

```js
call('player.get', { id }, 'high')      // 高优先级
post('player.sendMessage', {...}, 'low') // 低优先级
// 不传或传 'normal' 为默认
```

### 自动降级算法

自动降级防止高频 NORMAL 任务挤占其他任务的执行时间。

```
降级条件：
  NORMAL 任务提交时
  → 检查该 plugin:taskType 在最近 1 秒内的调用频率
  → 如果超过阈值（默认 200 次/秒），优先级降为 LOW

降级时机：
  任务在提交时（submitGameSync/submitGameAsync）检查频率，
  决定入队到 NORMAL 还是 LOW 池。
  一旦入队，执行时不再重新检查。

恢复：
  频率降低后，该 taskType 的 NORMAL 任务不再被降级，
  自然恢复到 NORMAL 池执行。
```

频率追踪器 `TaskFrequencyTracker` 采用滑动窗口算法：

```
1 秒 = 50 个时间槽，每槽 20ms

每次调用 NORMAL 任务时：
  ① 计算当前时间对应的时间槽索引
  ② 如果该槽已过期（>20ms 未更新），重置为 0
  ③ 递增该槽计数
  ④ 统计过去 1 秒内所有槽的总调用次数
  ⑤ 如果总次数 > 阈值（默认 200），返回 true（应降级）
```

滑动窗口的优点是：不保留历史数据，窗口大小固定为 1 秒，内存占用恒定（50 个 int + 50 个 long），计算快速且稳定。

### 空队额外等待机制

`idle-spin-us: 100`（最大等待时间，默认 100 微秒）解决以下场景：

```
JS 线程提交任务 → 主线程刚清完队列 ← 时间差 < 0.1ms
                          ↓
                  如果没有自旋，等待 50ms 到下一 tick
                  如果有自旋，100μs 内感知到新任务并立即执行
```

我们任务空队额外等待机制在绝大多数场景下，能够在保持服务器负载稳定的情况下，有效提升 Yeow 插件在某些常见情形下的资源利用效率（可达 500x）

例如：

```js
for(loc of locs){
  await world.setBlock(...loc, blockType);
}
```

如果没有空队额外等待机制，在 Yeow 调度器负载低时，上述代码的任务执行效率为（1 task/tick，20 task/s），引入空队额外等待机制后的任务执行效率可达（10000+ task/s）

### 同步调用

```
call('player.getPing', {uuid})
  → $send('task', '{"type":"player.getPing","p":{"uuid":"..."}}')
  → PluginThread: scheduler.submitGameSync(pld, future, priority, name)
  → future.get(5s) [JS 线程阻塞]
  → 主线程 tick(): 从对应优先级队列取出 → Tasks.execute()
  → future.complete(result) [JS 线程恢复]
```

### 异步调用

```
post('player.sendMessage', {...})
  → $send('task', '{"type":"...","p":{...},"cb":"cb_1"}')
  → PluginThread: scheduler.submitGameAsync(pld, cbId, callback, priority, name)
  → 立即返回 [JS 线程不阻塞]
  → 主线程 tick(): 执行 → callback.accept(result)
    → queue.sendJs({t:"cb", p:"cb_1", r:result})
  → 消息循环收到 → Promise resolve
```

### fs / http / assets

不走主线程，PluginThread 直接处理：

```
$send('fs', '{"t":"readFile","p":{"path":"config.json"}}')
  → PluginThread.handleFs() → java.nio.file
  → 同步返回

$send('assets', '{"t":"read","p":{"path":"assets/config.a1b2c3d4.yml"}}')
  → PluginThread.handleAssets() → ZipFile 读取 JAR 内对应 entry
  → 同步返回
```

路径安全：所有文件操作限制在 `plugins/<插件名>/` 下，`resolvePath()` 拦截 `../` 穿越。

**资源命名空间**：构建时每个依赖项（主项目与满足条件的 npm 包）的 `assets/` 分配唯一命名空间 id，内容原样复制到 JAR `assets/<id>/`（不哈希改名，相对引用永远有效）。JS 侧应始终通过 `getAssetsPath()`（来自 `yeow-dev`）获取路径，而非硬编码。详见 [Assets API](api/assets.md)。

## 开始执行游戏任务的时机

如果在 `onLoad` 外（顶层代码或 `onInit`）调用 `call`：

```
call('command.register', {...})
  → $send('task', ...)
  → scheduler.submitGameSync(pld, future)
  → future.get(5s)             ← JS 线程阻塞，等待主线程 tick
  ─────────────────────────────────
  → 5 秒后主线程 tick 仍未启动 → 超时
```

调度器的 tick 由 `Bukkit.getScheduler().runTaskTimer(this, () -> scheduler.tick(), 0L, 1L)` 驱动，它在 `YeowRuntime.onEnable()` 中注册。

### 启动时序

```
Bukkit 加载阶段:
  onLoad()
    → YeowRuntime.onLoad()
      → scheduler = new Scheduler(config)     // 调度器对象已创建
      → Bootstrap.onLoad() → registerPlugin()
        → PluginThread 启动
          → 创建 QuickJS 上下文 → inject()
          → evaluate(init.js) → evaluate(userCode)
          → 直接调 $hm({t:"INIT"})              ← 不入队，同步执行
            → onInit 回调执行                   ← 此时 scheduler 未 tick
          → 消息循环开始
            → pollJs 等待消息
  onEnable()
    → YeowRuntime.onEnable()
      → runTaskTimer(scheduler::tick)          // tick 开始
      → queue.sendJs({t:"LOAD"})               → 消息循环收到 LOAD
        → $hm → onLoad 回调执行                ← 此时 scheduler 正在 tick
          → call('command.register') 正常
```

### 实践

- `call()` 游戏操作（如 `registerCommand`、`eventOn`、`Player.getSync`、属性读写等）**必须放在 `onLoad` 内**
- `console.log`、`fetch`、`request`、`fs.*`、`assets.*` 不走调度器，**可以在任何阶段执行**
- `post()` 虽不阻塞但也会排队等 tick，不过不会超时

```js
onLoad(() => {
    // ✅ 同步 call 安全（Sync 后缀或属性/回调注册）
    registerCommand('hello', { executor: ... });
    eventOn('playerJoin', (e) => { ... });
    const p = Player.getSync('uuid');   // 同步获取
    broadcastSync('hello');             // 同步广播
});

// ✅ 异步 API 可在任何阶段使用
onLoad(async () => {
    const p = await Player.get('uuid');
    await broadcast('hello');
});

// ✅ 非调度器操作可在顶层
console.log('plugin loaded');
fs.writeFileSync('log.txt', 'started');
```

### EventBridge

```
Bukkit 事件触发
  → EventBridge: 检查 JS 订阅
  → eventData() 提取字段（基本类型，无 Bukkit 引用）
  → SyncCallbackHelper.register(cbId)
  → queue.sendJs({t:"cb", p:cbId, r:{事件数据}}) → JS 线程
  → 主线程自旋等待:
      while (!pend.isDone()) {
          runtime.getScheduler().tick();
      }
  → JS $hm → _hm → _cbs[cbId].h(r)
  → yeow-api 回调内:
      自动模式:
        同步 handler: 执行完 → $send('task', {type:'event.complete', params:{callbackId, mods}})
        返回 Promise: → 立即 $send('event.complete')，只有同步段修改生效
      手动模式:
        handler(e, complete) → 用户调用 complete(mods)
  → Scheduler → Tasks.execute('event.complete')
  → SyncCallbackHelper.complete(cbId, mods)
  → applyMods(): if (cancelled) event.setCancelled(true)
```

### 并发事件处理

当多个插件订阅同一事件时，Yeow 支持串行（默认）和并发两种模式：

| 模式           | 行为                                           | runtime/config.yml 配置            |
| -------------- | ---------------------------------------------- | --------------------------------- |
| 串行           | 逐个发送事件给每个插件，等待一个完成再发下一个 | `concurrent-events: false`        |
| 并发（实验性） | 同时发送给所有订阅插件，等待最慢的完成         | `concurrent-events: true`（默认） |

并发模式下，每个插件使用独立的 callbackId，`CountDownLatch` 等待所有插件完成。事件数据对所有插件共享同一份快照。由于游戏操作通过调度器串行化，不会产生竞态。`cancelled` 合并策略：任一插件取消则取消。

### 事件数据

所有事件字段是基本类型（string/number/boolean/object），JS 端 yeow-api 的 `adaptEvent()` 自动包装：
- `player` UUID → `Player.getSync(uuid)` 对象（同步转换，不影响事件处理）
- `from`/`to`/`respawnLocation` → `Location` 对象

### 事件处理器中的操作

```js
// 自动模式（默认）
eventOn('blockBreak', (e) => {
    // ✅ 同步 call — 自旋循环会处理
    const p = Player.getSync(e.player);

    // ✅ 取消事件 — 即时生效
    e.cancelled = true;

    // ✅ 异步操作（事件已释放，但异步 API 调用不受影响）
    post('player.sendMessage', {...});
});

// async handler — 返回 Promise 即立即 complete
eventOn('blockBreak', async (e) => {
    e.cancelled = true;  // ✅ 同步段生效
    await someTask();    // 事件已结束，此后设值无效
    e.cancelled = false; // ❌ 不生效
});

// 手动模式 — 完全控制 complete 时机
eventOn('blockBreak', { manualRelease: true }, (e, complete) => {
    doAsyncCheck(result => {
        e.cancelled = result;
        complete({ cancelled: result });
    });
});
```

### 事件处理模式选择

自动模式下，事件处理器返回 Promise 时立即释放事件。这意味着 async handler 中的 `await` 之后设置 `event.cancelled` 无效。

**但这不意味着 async handler 没有价值。** 绝大多数事件的用途是**触发逻辑**而非**修改结果**：

```
eventOn('playerJoin', async (e) => {
    // 查询数据库 → 发送欢迎消息 → 记录日志
    // 这些操作不需要阻塞主线程等待
    const msg = await db.getWelcome(e.player.uuid);
    e.player.sendMessage(msg);
    log.info(e.player.name + ' joined');
});
```

上面这个例子中，事件并不需要被取消，也不需要返回任何 mods。async handler 让插件自由使用异步 API，**不阻塞主线程自旋**。这正是推荐的做法：

| 场景                                     | 推荐模式                | 原因                                      |
| ---------------------------------------- | ----------------------- | ----------------------------------------- |
| 仅需触发逻辑（发消息、改数据、记录日志） | 自动模式 + async        | 不阻塞主线程，代码简洁                    |
| 需要同步决定结果（取消、修改掉落等）     | 自动模式 + 同步 handler | `await` 前设值即可                        |
| 需要异步获取数据后决定结果               | 手动模式 + `complete()` | 由用户控制 `$send('event.complete')` 时机 |

> **规则**：如果你的事件处理器逻辑不需要阻塞主线程等待结果，大胆使用 async。主线程自旋等待 JS 结果期间无法处理 tick、AI、物理等，长时间自旋会影响服务器性能。

## 回调系统

### 统一回调消息

所有 Java→JS 的回调（事件、补全、异步结果）使用同一消息格式：

```json
{"t":"cb", "p":"cb_42", "r":{...}}
```

- `t` — 固定 `"cb"`
- `p` — callbackId，由 `_registerCallback` 生成（格式 `"cb_N"`）
- `r` — 回调数据，内容因场景而异

JS 端 `_hm` 函数只有一个回调处理分支：

```js
if (t === 'cb' || t === 'CALLBACK') {
    const e = _cbs[p];
    if (e) { e.h(r); if (!e.persistent) delete _cbs[p]; }
}
```

Java 端通过 `SyncCallbackHelper` 注册等待，JS 通过 `$_send('task')` 发回响应：

| 场景      | Java 发送                            | JS 响应                                                                         |
| --------- | ------------------------------------ | ------------------------------------------------------------------------------- |
| 事件      | `{t:"cb", p:cbId, r:{event data}}`   | `$send('task', {type:'event.complete', params:{callbackId, mods}})`             |
| 补全      | `{t:"cb", p:cbId, r:{sender, args}}` | `$send('task', {type:'command.tabComplete', params:{callbackId, completions}})` |
| 异步 post | `{t:"cb", p:cbId, r:result}`         | 自动 — 回调函数处理 `r`                                                         |

响应消息均经过 Scheduler 的 `Tasks.execute()` → `SyncCallbackHelper.complete()`，不新增 JNI 函数。

### 事件注册

`eventOn()` 在 yeow-api 内部调用 `_registerCallback(fn, {persistent:true})` 注册回调，并将生成的 `cbId` 通过 `$_send('task', {type:'event.subscribe', params:{callbackId, eventType}})` 发送到 Java 端。Java 的 `EventBridge.subs` 维护 `eventType → plugin → cbId` 映射：

```
subs = {
  "blockBreak": { "myPlugin": "cb_42", "otherPlugin": "cb_43" },
  "playerJoin": { "myPlugin": "cb_44" }
}
```

事件触发时，EventBridge 通过 `subs[eventType][plugin]` 获取对应的 `cbId`，直接发送 `{t:"cb", p:cbId, r:data}`。不需要任何前缀或编码。对于每个插件，回调注册表 `_cbs` 中的条目会被调用，触发用户的事件处理器。

### 补全器注册

`registerCommand()` 内 completer 同样通过 `_registerCallback` 注册回调，`cmdId` 通过 `command.register` 任务传给 Java 端 `CommandTasks`。用户在 `complete(result)` 中传入补全结果。

```js
import { onInit, onLoad, onUnload } from 'yeow-api';

onInit(() => {
    // 在 JS 线程消息循环开始后立刻执行
    // 可以注册命令/事件，但不应该操作游戏（不保证执行时是否可用）
});

onLoad(() => {
    // Bukkit onEnable 后通过消息循环触发
    // 可以调所有游戏操作
});

onUnload(() => {
    // 插件禁用或热重载时执行
    // 清理资源、保存数据等
});
```

### 触发时序

| 钩子       | 触发时机                  | 游戏 API 可用 |
| ---------- | ------------------------- | :-----------: |
| `onInit`   | JS 上下文创建、代码加载后 |       ❌       |
| `onLoad`   | Bukkit `onEnable` 后      |       ✅       |
| `onUnload` | 插件禁用或热重载          |       ✅       |

### 热重载

当 dev-server 检测到文件变化时：

```
dev-server → WebSocket hot-reload → Java 主线程
  │
  ├─ command.unregisterAll      ← 清理旧命令
  ├─ eventBridge.unsubscribeAll ← 清理旧事件
  ├─ purgePluginServices        ← 清理旧服务（含 native 子进程）
  └─ pt.reload(newCode)         ← 阻塞等待，最多 5s
       │
       ├─ 发送 RELOAD → JS 队列 → 等待 JS 线程自然退出
       │    ├─ _hm → onUnload 回调
       │    ├─ $send('lifecycle', {type:'unloadDone'})
       │    └─ running = false → 消息循环退出 → 旧上下文销毁
       │
       ├─ 超时未退出 → 强制终止（running=false + ctx.destroy）
       │
       ├─ 清理旧 timer / io / http / 残留任务
       ├─ 清空消息队列
       └─ start() → 新线程 → 新上下文 → 新代码
```

热重载在主线程上同步等待（最多 5s），期间不影响其他 Yeow 插件。

### 生产环境 reload / unload

`/yeow reload` 与 `/yeow unload` 使用与开发模式热重载**相同的卸载步骤**（5s 强制终止）：

```
/yeow unload <plugin|all>        /yeow reload <plugin|all> [path]
        │                                │
        └── unloadPlugin(name)           └── unloadPlugin(name) → registerPlugin(原路径或新 path)
              │
              ├─ command.unregisterAll      ← 清理旧命令
              ├─ eventBridge.unsubscribeAll ← 清理旧事件
              ├─ purgePluginServices        ← 清理旧服务
              ├─ pt.stopAndWait()           ← DISABLE + 5s 等待 + 强制终止
              └─ plugins.remove(name)       ← 移出注册表
```

- `/yeow reload my-plugin` — 从原路径（JAR 或 zip 路径）重新读取磁盘上的包
- `/yeow reload my-plugin plugins/Yeow/other.yeow.zip` — 从新来源加载（URL 亦可，临时不持久化）
- `/yeow reload all` — 全部按原路径重载
- `/yeow unload <plugin|all>` — 卸载（5s 强制终止）
- `/yeow uninstall <plugin>` — 卸载并把 `plugins/Yeow/` 下同名 `.yeow.zip` 移入 `plugins/Yeow/.backup/`（数据目录需手动清理）
- `/yeow load <path|url>` — 临时加载（URL 下载到缓存，重启不保留）
- `/yeow install <url>` — 下载安装到 `plugins/Yeow/<name>-<version>.yeow.zip`（标准格式，下次启动自动扫描）
- `/yeow update <url>` — 扫描 `plugins/Yeow/` 按 `yeow.json` 的 `name` 匹配旧包，旧包移入 `plugins/Yeow/.backup/`，写入新版本；插件运行中则自动重载
- 同名插件在任何场景下重复加载（自动扫描 / 命令 / 模板 JAR）都会被拒绝并输出警告；**同时部署模板 JAR 与 `.yeow.zip` 会产生该冲突，需手动移除其一**

## 环境能力注入

PluginThread 在 JS 上下文只注册一个底层函数：

| 函数                          | 签名                                 | 说明                                   |
| ----------------------------- | ------------------------------------ | -------------------------------------- |
| `$_send(channel, jsonString)` | `(string, string) => string \| null` | JS→Java 唯一通信入口，返回 JSON 字符串 |

支持的消息通道：

| 通道          | 用途                                   | 处理位置               |
| ------------- | -------------------------------------- | ---------------------- |
| `task`        | 游戏任务（请求/获取方块/传送等）       | 主线程调度器           |
| `timer`       | 定时器（setTimeout/setInterval）       | 插件线程 Timer 线程池  |
| `fs`          | 文件系统读写                           | 插件线程直接处理       |
| `http`        | HTTP 服务器/客户端                     | 插件线程直接处理       |
| `assets`      | 插件内置资源读取                       | 插件线程直接处理       |
| `service`     | 服务注册/请求/订阅/发布               | ServiceManager        |
| `debug`       | 错误上报 / 心跳 ping-pong              | 插件线程直接处理       |
| `log`         | 控制台日志（自动添加 `[插件名]` 前缀） | 插件线程直接处理       |
| `now`         | 纳秒时间戳                             | 插件线程直接处理       |
| `dir`         | 插件数据目录路径                       | 插件线程直接处理       |
| `lifecycle`   | 生命周期确认（unloadDone）             | 插件线程直接处理       |

### `$send` 封装层

init.js 在 `$_send` 基础上封装了 `$send(channel, payload)`，自动做 JSON 转换：

```js
// $_send 直接使用需手动 JSON
$_send('task', JSON.stringify({type: 'player.get', params: {identifier: 'uuid'}}));

// $send 自动处理 JSON
$send('task', {type: 'player.get', params: {identifier: 'uuid'}});
```

### 通道说明

**task 通道** — 游戏任务，走主线程调度器：

```json
{
    "type": "player.get",
    "params": {"identifier": "uuid"},
    "cb": "cb_1",         // 异步回调 ID（可选）
    "priority": "high"     // 优先级（可选）
}
```

同步任务（无 `cb`）阻塞等待结果，异步任务（有 `cb`）通过回调 Promise resolve。

**timer 通道** — 替代 `$timeout`/`$interval`：

```json
{"type": "timeout", "cb": "cb_1", "delay": 1000}
{"type": "interval", "cb": "cb_2", "delay": 5000}
```

**log 通道** — 控制台日志自动添加 `[插件名]` 前缀：

```js
console.log('hello');     // → [MyPlugin] hello
console.warn('warning');  // → [MyPlugin] warning
```

**reportError 通道** — 手动上报错误到 dev-server source-map 解析：

```js
import { logError } from 'yeow-api';
try { riskyOp(); } catch (e) { logError(e, 'custom context'); }
```

**lifecycle 通道** — 生命周期确认（插件内部使用）：

```
$send('lifecycle', {type: 'unloadDone'})    // 禁用或热重载完成确认
```

JS 端在 `onUnload` 回调执行完毕后通过 `$send('lifecycle')` 向 Java 端确认。收到确认后 Java 端设置 `running = false`，消息循环自然退出。

### 封装层

init.js 将 `$_send` 封装为标准 JS API：

```
$_send(channel, jsonString)   ← 唯一Java原生函数
    ↓
init.js 封装层
    ├── $send(channel, object)   ← 自动 JSON 转换
    ├── console.log/warn/error   ← 自动添加 [pluginName] 前缀
    ├── setTimeout / clearTimeout
    ├── setInterval / clearInterval
    ├── fetch                    ← HTTP fetch (Promise)
    └── $hm                      ← 消息分发器
```

## 运行时配置

`plugins/Yeow/runtime/config.yml`（首次启动自动生成）：

```yaml
# 每 tick 游戏任务预算（毫秒）
tick-budget-ms: 20

# 三级优先级预算比例
priority-ratios: [0.5, 0.3, 0.2]

# 自动降级
auto-demote: true
demote-threshold: 200

# 空闲自旋等待（微秒），0 关闭
idle-spin-us: 100

# 实验性：并发处理事件
# 启用后，多个插件订阅同一事件时并发执行事件处理器，
# 操作通过调度器串行化，不会产生竞态。
concurrent-events: true

# 运行时警告与动态扩容
profile:
  warnings-enabled: true           # 预警引擎（默认开启，独立于全量分析）
  warn-cooldown-seconds: 1800      # 同类警告冷却（30min）
  latency-warn-threshold-ms: 200   # 心跳超时阈值
  event-slow-threshold-ms: 2000    # 事件响应警告阈值（超时仍为 5000）
  tab-slow-threshold-ms: 500       # 补全响应警告阈值（超时仍为 1000）
  callback-timeout-event-ms: 5000  # 事件等待上限（运行时生效）
  callback-timeout-tabcomplete-ms: 1000
  suspend-warn-seconds: 30         # 插件挂起检测
  backlog-threshold: 35            # 扩容信号：40 tick 中积压次数阈值
  backlog-window-ticks: 40
  scheduler-saturation-pct: 80     # 调度饱和告警百分比
  scaler:
    enabled: true                  # 动态扩容
    expansion-factor: 1.3
    max-multiplier: 3.0
```

## 服务机制（Service）

### Plugin Service — 插件间通信

插件 A 注册服务，插件 B 调用：

```
插件 B                         ServiceManager                 插件 A
  serviceRequest(svcId, path, body)
    → service 通道 request
      → registry 定位服务归属插件
        → 通过 onRequestCb 回调投递 {_svc:"request", path, body}
          → 插件 A 的 onRequest(path, body) 处理
          → $send('service', {t:"response", requestId, body})
      → respond(requestId, consumer)
    → 插件 B 的 Promise resolve
```

**发布/订阅**：服务方用 `publish(token, eventPath, body)` 发布事件，订阅方用 `subscribe(serviceId, eventPath, handler)` 接收。`token` 是发布鉴权凭证（注册时返回）。

### Native Service — 原生能力扩展

插件通过 `registerNativeService` 注册，运行时提取可执行文件并 spawn 子进程：

```
registerNativeService(refName, platforms)
  → 按当前平台（os + arch，精确匹配回退 os）选择二进制
  → 从 JAR assets/ 提取到临时目录（命名空间路径经 getAssetsPath 解析）
  → spawn(binary, nativePort, serviceId)
  → 子进程连接 TCP → 发送 {"type":"ready"} → ready() resolve
  → 请求走 TCP JSON line：request → response
```

- **平台粒度**：`windows-x64` / `linux-arm64` 等，精确匹配优先，回退到 `windows` / `linux`
- **单一实例**：`isPublic: true` 时同名服务只启动一个进程/保留一个注册；重复注册被拒绝（返回 `err` + `serviceId`），调用方用 `err.serviceId` 以调用者身份接入既有服务
- **协议**：见 [Native Service 规范](specifications/native-service/index.md)

API 用法见 [Service API](api/service.md)。

## 运行时警告与动态扩容

### 调度语义

三级队列语义不同，检测与告警**只针对实时队列**：

- **HIGH / NORMAL**（实时性、交互响应）——不应存在积压，积压即问题
- **LOW**（大批量重复任务）——允许积压与延迟完成，不计入告警与健康评分

### 警告检测

预警引擎默认启用（`profile.warnings-enabled: true`），按 1s 窗口聚合检测，双语告警输出（上下两条彩色分隔线，随级别变色）：

| code | 触发 |
|------|------|
| `heartbeat.timeout` | JS 线程单次心跳 >200ms |
| `plugin.hung` | >30s 持续无响应（线程已死） |
| `event.slow` / `event.timeout` | 事件响应 >2s / 等待 >5s 被释放 |
| `tab.slow` / `tab.timeout` | 补全响应 >500ms / 等待 >1s |
| `budget.congested` / `budget.restored` | 40 tick 内 HIGH/NORMAL 积压 ≥35 次 / 恢复 |
| `scheduler.saturated` | HIGH/NORMAL 执行占 tick >80% |

同类警告冷却 30 分钟（可配置）。详见 [运行时警告指南](runtime-warning.md)。

### 动态扩容（BudgetScaler）

运行时组件（独立于预警引擎）：最近 40 tick 中 HIGH/NORMAL 积压 ≥35 次（滑动窗口）→ 预算 ×1.3（指数叠加，最大 ×3）；连续 40 tick 无积压逐级回落。

### 全量分析（profile.enabled）

逐任务级采集默认关闭。开启后 `/yeow profile` 输出健康评分 + 实时/批量队列分解，`/yeow track` 单插件深度追踪。预警引擎不依赖此开关。

## 平台无关性

Yeow 插件本身**平台无关**：

- 插件包是一个 ZIP（`.yeow.zip` 或部署为 JAR），内含 `.yeow/main.js`（打包后的 JS）、`assets/`、`yeow.json`（含权限声明）
- 不依赖 Java 环境——运行时不限语言/平台
- 放入 `plugins/Yeow/` 会被运行时自动扫描加载（也可用 `/yeow load` 手动加载）
- 任何符合 [平台规范](specifications/README.md) 的运行时都能加载并运行 Yeow 插件：
  1. 理解插件包结构（读取 `yeow.json`、`.yeow/main.js`、`assets/`）
  2. 实现调度器（任务队列 + 优先级 + 时间片）
  3. 实现执行器（把任务翻译为宿主平台的游戏操作）
  4. 实现符合标准的 JS 运行时（`$_send` 桥、回调协议、生命周期消息）
  5. 实现通道（fs / http / assets / service / timer 等）

Paper/Bukkit 的 yeow-runtime 是官方实现的运行时示例。更多插件包格式见 [平台规范](specifications/README.md)。

## 定时器资源管理

- 每个 PluginThread 拥有独立的 `ScheduledExecutorService`（线程名 `timer-<插件名>`）
- 所有 `ScheduledFuture` 存储在 `timerFutures` 列表
- `stop()` 时 `cancel()` 所有 Future + `shutdownNow()`
- `scheduler.purgePluginTasks(name)` 清理残留的 PendingTask

## 安全

- **路径隔离**：文件系统限制在 `plugins/<插件名>/`，`resolvePath()` 拦截 `../`
- **上下文隔离**：每个插件独立 QuickJSContext，全局对象互不干扰
- **权限声明**：敏感消息节点（`fs:*`、`http:*`、`service:registerNative`、`assets:extract`）默认拒绝，必须在 `yeow.config.json` 声明（写入 `yeow.json`）；未声明调用返回 `Permission denied: <node>`。粒度支持节点级（`fs:readFile`）与通配级（`fs:*`），其余节点默认允许
- **权限委托**：命令权限由 Bukkit 处理，未授权玩家不执行 executor
- **同名唯一**：同一插件名只允许一个实例，重复加载（自动扫描 / `/yeow load` / 模板 JAR）均被拒绝并警告
