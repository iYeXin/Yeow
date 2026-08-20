# Task 模块规范

## 概述

`task` 通道是 Yeow 插件的核心通信通道，用于执行所有与游戏状态相关的操作。

### 消息格式

```json
{
  "type": "player.get",
  "params": { "key": "value" },
  "cb": "cb_42"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 任务类型标识符，按功能模块命名（例：`player.get`） |
| `params` | object | 否 | 任务参数，各任务自有字段 |
| `cb` | string | 否 | 回调 ID。**存在 → 异步**：运行时立即返回 `null`，通过回调投递结果。**不存在 → 同步**：运行时阻塞 JS 直到完成并同步返回结果 |

### 优先级

可通过消息**顶层** `priority` 字段（与 `cb` 同级）控制任务优先级：

| 值 | 预算占比 | 说明 |
|----|---------|------|
| `high` | 50% | 高优先级（同步 `call` 默认优先级） |
| `normal` | 30% | 默认优先级（异步 `post` 默认优先级） |
| `low` | 20% | 低优先级 |

```json
{ "type": "player.get", "params": { "identifier": "uuid" }, "priority": "high" }
```

未传或非法值回退为 `normal`。运行时每 tick 按预算比例分配执行时间。若高优先级预算未用尽，溢出给下一级。全部预算用尽后，剩余任务排队到下一 tick。最后进入贪婪阶段（无视预算按优先级顺序尽量消费）。

### 返回值约定

- 返回 `true`/`false` — 操作是否成功
- 返回 `null` — 实体/玩家/世界不存在
- 返回 `{}` object — 包含具体数据
- 返回 `[]` array — 列表数据
- 异步任务通过 `cb` 通道投递结果

### 错误处理

如果任务执行过程中抛出异常，回调数据字段 `r` 为一个包含详细错误信息的对象：

```json
{
  "err": "java.lang.NullPointerException",
  "type": "NullPointerException",
  "task": "pdc.get",
  "stack": "java.lang.NullPointerException\n\tat ...\n\tat ..."
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `err` | string | 异常消息 |
| `type` | string | Java 异常类名 |
| `task` | string | 触发异常的任务类型 |
| `stack` | string | 完整 Java 堆栈跟踪 |

---

## 模块列表

| 模块 | 说明 | 任务数量 |
|------|------|---------|
| [player](player.md) | 玩家操作 | 51 |
| [entity](entity.md) | 实体 + 药水效果 | 38 |
| [world](world.md) | 世界 + 方块 + 区块快照 + 音效 + 粒子 + 实体生成 + WorldBorder | 49 |
| [inventory](inventory-gui.md) | 物品栏 + 容器方块 + 自定义 Inventory | 17 |
| [server](server.md) | 服务器全局操作 + Material 查询 + 权限注册 | 13 |
| [command](command.md) | 命令注册 + Tab 补全 | 4 |
| [event-system](event-system.md) | 事件订阅/完成 | 3 |
| [bossbar](bossbar.md) | BossBar | 12 |
| [scoreboard](scoreboard.md) | 计分板 | 24 |
| [pdc](pdc.md) | 自定义持久化数据 | 6 |
| [advancement](advancement.md) | 进度 | 5 |
| [recipe](recipe.md) | 配方 | 3 |

> 合计 225 个任务（`permission.register` 计入 server 模块；`material.*` 计入 server；`block.breakNaturally`、`chunk.*` 计入 world）。Paper 与 Folia 任务集**严格一致**（Folia 的 `recipe.add` 内部按配方类型 shaped/shapeless/furnace/blast/smoker/campfire 分派，非独立任务）。统计口径：`Tasks.java` / `FoliaTasks.java` 任务语义 case 数，2026-08-18。
