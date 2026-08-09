# 调度器与任务

> 三级优先级调度器：时间片预算、自动降级、空闲自旋；异步 vs 同步、手动分片；开始执行游戏任务的时机（onLoad/onInit）。

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

**任务配置（TaskOptions）**：所有走 task 通道的 API 方法（`player.sendMessage`、`world.setBlock` 等，含 Sync 变体）均可在**参数末尾**传入可选任务配置对象，透传给调度器：

```js
await player.sendMessage('Hello', { priority: 'high' });   // 高优先级
world.setBlockSync(0, 65, 0, 'stone', { priority: 'low' }); // 低优先级（Sync 同样支持）
registerCommand('back', {...}, { priority: 'low' });        // options 已被占用的方法用 taskOptions 第三参
```

`TaskOptions`：`{ priority?: 'high' | 'normal' | 'low' }`（未来可扩展）。属性访问器（`player.ping` 等）无法携带配置，请使用对应的方法形式。

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
- JS 线程阻塞在 `CompletableFuture.get(task-sync-timeout-ms)`（默认 10s，`plugins/Yeow/runtime/config.yml` 可配置）
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
  → future.get(10s) [JS 线程阻塞；超时可由 config 的 task-sync-timeout-ms 调整]
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

**资源命名空间**：构建时每个依赖项（主项目与满足条件的 npm 包）的 `assets/` 分配唯一命名空间 id，内容原样复制到 JAR `assets/<id>/`（不哈希改名，相对引用永远有效）。JS 侧应始终通过 `getAssetsPath()`（来自 `yeow-dev`）获取路径，而非硬编码。详见 [Assets API](/api/assets)。
