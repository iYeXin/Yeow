# 运行时运维与安全

> 运行时警告与动态扩容（预警引擎、BudgetScaler、全量分析）；平台无关性；定时器资源管理；安全。

## 运行时警告与动态扩容

### 调度语义

三级队列语义不同，检测与告警**只针对实时队列**：

- **HIGH / NORMAL**（实时性、交互响应）——不应存在积压，积压即问题
- **LOW**（大批量重复任务）——允许积压与延迟完成，不计入告警与健康评分

### 警告检测

预警引擎默认启用（`profile.warnings-enabled: true`），按 1s 窗口聚合检测，双语告警输出（上下两条彩色分隔线，随级别变色）：

| code                                   | 触发                                      |
| -------------------------------------- | ----------------------------------------- |
| `heartbeat.timeout`                    | JS 线程单次心跳 >200ms                    |
| `plugin.hung`                          | >30s 持续无响应（线程已死）               |
| `event.slow` / `event.timeout`         | 事件响应 >2s / 等待 >5s 被释放            |
| `tab.slow` / `tab.timeout`             | 补全响应 >500ms / 等待 >1s                |
| `budget.congested` / `budget.restored` | 40 tick 内 HIGH/NORMAL 积压 ≥35 次 / 恢复 |
| `scheduler.saturated`                  | HIGH/NORMAL 执行占 tick >80%              |

同类警告冷却 30 分钟（可配置）。详见 [运行时警告指南](/runtime-warning)。

### 动态扩容（BudgetScaler）

运行时组件（独立于预警引擎）：最近 40 tick 中 HIGH/NORMAL 积压 ≥35 次（滑动窗口）→ 预算 ×1.3（指数叠加，最大 ×3）；连续 40 tick 无积压逐级回落。

### 全量分析（profile.enabled）

逐任务级采集默认关闭。开启后 `/yeow profile` 输出健康评分 + 实时/批量队列分解，`/yeow track` 单插件深度追踪。预警引擎不依赖此开关。

## 平台无关性

Yeow 插件本身**平台无关**：

- 插件包是一个 ZIP（`.yeow.zip` 或部署为 JAR），内含 `.yeow/main.js`（打包后的 JS）、`assets/`、`yeow.json`（含权限声明）
- 不依赖 Java 环境——运行时不限语言/平台
- 放入 `plugins/Yeow/` 会被运行时自动扫描加载（也可用 `/yeow load` 手动加载）
- 任何符合 [平台规范](/specifications/README) 的运行时都能加载并运行 Yeow 插件：
  1. 理解插件包结构（读取 `yeow.json`、`.yeow/main.js`、`assets/`）
  2. 实现调度器（任务队列 + 优先级 + 时间片）
  3. 实现执行器（把任务翻译为宿主平台的游戏操作）
  4. 实现符合标准的 JS 运行时（`$_send` 桥、回调协议、生命周期消息）
  5. 实现通道（fs / http / assets / service / timer 等）

Paper 系（Paper/Purpur/Leaf 等）的 yeow-runtime 是官方实现的运行时示例。更多插件包格式见 [平台规范](/specifications/README)。

## 定时器资源管理

- 每个 PluginThread 拥有独立的 `ScheduledExecutorService`（线程名 `timer-<插件名>`）
- 所有 `ScheduledFuture` 存储在 `timerFutures` 列表
- `stop()` 时 `cancel()` 所有 Future + `shutdownNow()`
- `scheduler.purgePluginTasks(name)` 清理残留的 PendingTask
