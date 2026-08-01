# 运行时警告指南

Yeow 内建**预警引擎**（默认启用，`profile.warnings-enabled: true`），以每秒一次的窗口聚合检测异常行为，在控制台输出**双语警告**（中英文），帮助开发者和服主快速定位性能问题。

> **所有警告都不是致命错误**，不会导致服务器崩溃。如果你不是对应插件的开发者，可以忽略它们。

> **输出样式**：告警为**上下两条彩色分隔线**（严重=红 / 警告=黄 / 提示=蓝）包裹的紧凑段落，以适配不同宽度的终端。

---

## 调度语义（理解告警的前提）

三级调度队列的语义不同，检测与告警**只针对实时队列**：

| 队列              | 承载                                     |         允许积压？         |
| ----------------- | ---------------------------------------- | :------------------------: |
| **HIGH / NORMAL** | 实时性与交互响应（命令、事件、异步 API） | ❌ **不应积压**——积压即问题 |
| **LOW**           | 大批量重复任务（自动降级/手动指定 low）  |  ✅ **允许积压与延迟完成**  |

因此：所有"积压/饱和"类告警只统计 HIGH/NORMAL；LOW 队列的积压与延迟是设计语义，不告警、不计入健康评分。

---

## 告警类型

| code                  | 严重程度 | 触发条件                                     | 数据来源  |
| --------------------- | -------- | -------------------------------------------- | --------- |
| `heartbeat.timeout`   | 警告 🟡   | JS 线程单次心跳往返 >200ms                   | 心跳 ping |
| `plugin.hung`         | 严重 🔴   | JS 线程 >30s 持续无响应                      | 心跳 ping |
| `event.slow`          | 警告 🟡   | 事件响应 >**2s**（未超时）                   | 事件桥    |
| `event.timeout`       | 警告 🟡   | 事件等待超时（**5s**，已强制释放）           | 事件桥    |
| `tab.slow`            | 警告 🟡   | 补全响应 >**500ms**（未超时）                | 命令桥    |
| `tab.timeout`         | 警告 🟡   | 补全等待超时（1s，已返回空列表）             | 命令桥    |
| `budget.congested`    | 警告 🟡   | 最近 40 tick 中 HIGH/NORMAL 积压 ≥ **35** 次 | 调度器    |
| `budget.restored`     | 提示 🔵   | 连续 40 tick 无积压，预算恢复                | 调度器    |
| `scheduler.saturated` | 警告 🟡   | HIGH/NORMAL 执行占 tick 时长 >**80%**        | 调度器    |

### 事件 2s 警告 vs 5s 超时（设计语义）

- **2s（`event.slow`）**：阻塞主线程 2 秒已不可容忍——即使事件最终完成，玩家交互与 TPS 已经受损，**开发者必须重视**
- **5s（`event.timeout`）**：运行时的等待上限，达到后事件被强制释放（此前的取消/修改不生效）

两者独立告警：慢但完成 → `event.slow`；超时 → `event.timeout`。

---

## 各类告警详解

### heartbeat timeout（心跳超时）

**检测机制：** Yeow 每秒向每个插件 JS 线程发送一次心跳 ping。以下任一情况触发：
- **无任何响应**——ping 发出后窗口内没有收到 pong（死循环、长阻塞）
- **响应缓慢**——单次往返超过 200ms

**含义：** 插件 JS 线程暂时性阻塞（无响应场景下最快 1-2 秒即可检出；持续 30s 将升级为 `plugin.hung`）。可能原因：
- 较长的同步循环（如遍历大量区块设置方块）
- 同步 IO 操作阻塞了事件循环
- 大量消息积压

**解决方案：**
1. 检查长同步循环 → 改用异步操作或手动分片
2. 检查同步网络/文件 IO → 使用异步 API

### event slow / event timeout（事件响应慢 / 超时）

**检测机制：** 事件处理器单次响应 >2s → `event.slow`；等待 >5s 被强制释放 → `event.timeout`。

**含义：** 事件处理逻辑阻塞了主线程。主线程自旋等待 JS 结果期间无法执行其他任务。

**解决方案：**
1. 使用 `async` handler（立即释放事件，逻辑异步执行）
2. 将重逻辑拆分到 `setTimeout` / `await` 后分段执行

```js
// ❌ 长同步操作阻塞主线程
eventOn('playerJoin', (e) => {
    const data = heavySyncCalculation(); // 耗时 3s → event.slow
    e.player.sendMessage(data);
});

// ✅ 返回 Promise，立即释放事件
eventOn('playerJoin', (e) => {
    return Promise.resolve().then(() => {
        const data = heavySyncCalculation();
        e.player.sendMessage(data);
    });
});
```

### tab slow / tab timeout（补全响应慢 / 超时）

**检测机制：** 补全回调 >500ms → `tab.slow`；等待 >1s → `tab.timeout`（返回空列表）。

**含义：** 补全器耗时过长，玩家输入命令时卡顿。

**解决方案：** 优化 completer；对结果做缓存。

### plugin thread hung（插件线程挂起）

**检测机制：** 连续 >30s 无任何心跳响应。

**含义：** JS 线程陷入**死循环 / 死锁 / 完全阻塞**，插件功能完全停止。

**解决方案：** 排查 `onLoad`/`onInit` 中无出口的 `while(true)`；繁重计算转移到原生服务；`/yeow reload` 或重启服务器。

### budget.congested / budget.restored（实时队列积压）

**检测机制：** 最近 40 tick 中 HIGH/NORMAL 队列出现积压的次数 ≥35（滑动窗口，`backlog-threshold`）。

**含义：** 任务提交速度超过处理能力，实时交互受损。运行时将自动扩容 tick 预算（见[动态扩容](#动态扩容)）；积压清除后恢复并提示。

> **LOW 批量队列不计入**——大批量重复任务允许积压与延迟。

### scheduler.saturated（调度饱和）

**检测机制：** 窗口内 HIGH/NORMAL 执行时间占 tick 总时长 >80%。

**含义：** 实时调度接近吃满，LOW 批量任务将被无限期推迟，交互响应下降。

---

## 配置

所有阈值与策略在 `plugins/Yeow/runtime/config.yml` 中配置：

```yaml
profile:
  warnings-enabled: true            # 预警引擎（默认开启；与全量分析独立）
  warn-cooldown-seconds: 1800       # 同类告警冷却（30min）
  latency-warn-threshold-ms: 200    # 心跳超时阈值（ms）
  event-slow-threshold-ms: 2000     # 事件响应警告阈值（ms；超时仍为 5000）
  tab-slow-threshold-ms: 500        # 补全响应警告阈值（ms；超时仍为 1000）
  callback-timeout-event-ms: 5000   # 事件回调等待上限（ms，运行时生效）
  callback-timeout-tabcomplete-ms: 1000 # 补全等待上限（ms，运行时生效）
  suspend-warn-seconds: 30          # 插件挂起检测阈值（s）
  backlog-threshold: 35             # 扩容信号：40 tick 中积压次数阈值
  backlog-window-ticks: 40          # 积压统计窗口
  scheduler-saturation-pct: 80      # 调度饱和告警百分比
```

> `callback-timeout-*` 是**运行时行为**（事件桥/命令桥实际等待的上限）；`*-slow-threshold-*` 是**告警阈值**（更早预警，但不影响行为）。

---

## 告警冷却机制

按 **(code, 插件)** 粒度冷却：同插件同 code 的告警在冷却时间内最多输出一次；不同插件/不同 code 互不影响。冷却默认 30 分钟。

---

## 动态扩容

**运行时组件**（独立于预警引擎）自动调节 tick 预算，默认启用：

- **扩容信号**：最近 40 tick 中 HIGH/NORMAL 积压 ≥35 次（`backlog-threshold`）→ 预算 ×1.3（指数叠加，上限 3.0x）
- **降级**：连续 40 tick 无积压 → 逐级 ÷1.3 回落至基准
- **达限**：达到上限仍在积压 → 输出严重警告

```yaml
profile:
  scaler:
    enabled: true              # 是否启用动态扩容
    expansion-factor: 1.3      # 每次扩容倍数
    max-multiplier: 3.0        # 最大扩容上限（3x = 60ms/tick）
```

---

## 全量分析（profile.enabled）

`/yeow profile` 与 `/yeow track` 需要 `profile.enabled: true`（默认关闭，避免逐任务采集开销）。开启后：
- 逐任务/逐插件时间分解、任务热力图
- `/yeow profile` 输出健康评分 + 实时/批量队列指标 + 各插件分解，并保存详细报告文件
- `/yeow track <plugin> <秒>` 单插件深度追踪

预警引擎不依赖此开关。

---

## 常见问答

### Q: 心跳超时 200ms 很严格，应该放宽吗？

200ms 是合理的：异步 IO 与异步 API 不影响心跳响应。若确定无问题，可忽略冷却期内的单次警告，或调大 `latency-warn-threshold-ms`。

### Q: 事件 2s 警告是不是太严格？

这是有意设计：阻塞主线程 2s 已足以造成可感知卡顿。`event.slow` 只是提醒，不会强制释放事件；只有 5s 超时才会。若插件确有合理的长任务，应改用 async handler 释放主线程，而不是放宽阈值。

### Q: LOW 队列积压会告警吗？

不会。LOW 队列承载大批量重复任务，允许积压与延迟完成——这是设计语义。若 LOW 任务长期不执行，请检查实时队列是否饱和（`scheduler.saturated`）。

### Q: 插件线程挂起后会自动恢复吗？

很可能不会（死循环不会自行退出）。开发环境可热重载（5s 强制销毁旧上下文）；生产环境 `/yeow reload` 或重启服务器。

### Q: 如何完全关闭某类告警？

设置 `profile.warnings-enabled: false` 关闭整个预警引擎（全量分析不受影响）；或调大对应阈值间接禁用。

---

## 更多信息

- [入门指南](getting-started.md) — 基本用法
- [进阶知识](advanced.md) — 架构和线程模型
- [CLI 参考](cli.md) — 命令用法
