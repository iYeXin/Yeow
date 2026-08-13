# Task 通道

`task` 通道是 Yeow 中唯一的调度器通道。所有与游戏状态相关的操作进入调度器统一排队执行。

**规范文档**：[task 模块规范](../task/index.md)

---

## 批量任务（2026-08-13）

payload 以 `tasks` 数组替代单个 `{type, params}`，一次提交多个任务，结果按原顺序一次返回（逐个独立执行，**无原子性**）：

```json
// 同步批量
{ "tasks": [ { "type": "world.getTime", "params": { "world": "world" } }, { "type": "server.getVersion" } ] }

// 异步批量（含 cb）
{ "tasks": [ { "type": "player.sendMessage", "params": { "uuid": "...", "message": "hi" } } ], "cb": "cb_1" }
```

| 字段 | 说明 |
|------|------|
| `tasks` | 任务数组：`[{ "type": "<taskType>", "params": {...}, "priority": "high"\|"normal"\|"low"? }]` |
| `cb` | 可选。有 → 异步（全部完成后 `r` 为结果数组）；无 → 同步阻塞返回结果数组 JSON |

- 单个任务失败不中断批处理——对应结果项为错误对象 `{"err": "<msg>", "type": "<异常类>", "task": "<taskType>"}`（与单任务错误同形状，`type`/`task` 尽力填充；入口解析失败时可能只有 `err`）
- `_plugin` 归属由运行时为每个任务自动注入（对齐单任务）
- JS 侧封装：`callBatch(tasks)`（同步，返回结果数组）/ `postBatch(tasks)`（异步，Promise 结果数组）
