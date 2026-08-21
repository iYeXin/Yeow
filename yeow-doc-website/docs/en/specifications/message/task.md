# Task Channel

The `task` channel is the only scheduler channel in Yeow. All operations related to game state are queued uniformly through the scheduler for execution.

**Specification document**: [task module specification](../task/index.md)

---

## Batch Tasks (2026-08-13)

The payload uses a `tasks` array instead of a single `{type, params}`, submitting multiple tasks at once, with results returned once in their original order (each executed independently, **no atomicity**):

```json
// synchronous batch
{ "tasks": [ { "type": "world.getTime", "params": { "world": "world" } }, { "type": "server.getVersion" } ] }

// async batch (with cb)
{ "tasks": [ { "type": "player.sendMessage", "params": { "uuid": "...", "message": "hi" } } ], "cb": "cb_1" }
```

| Field   | Description |
|---------|-------------|
| `tasks` | Task array: `[{ "type": "<taskType>", "params": {...}, "priority": "high"\|"normal"\|"low"? }]` |
| `cb`    | Optional. Present → async (after all complete, `r` is the result array); absent → synchronous blocking, returns the result array JSON |

- A single task failing does not interrupt the batch — the corresponding result item is an error object `{"err": "<msg>", "type": "<exception class>", "task": "<taskType>"}` (same shape as a single-task error; `type`/`task` are best-effort filled; only `err` may be present when entry parsing fails)
- `_plugin` attribution is automatically injected by the runtime for each task (aligned with single tasks)
- JS-side wrappers: `callBatch(tasks)` (synchronous, returns the result array) / `postBatch(tasks)` (async, Promise of the result array)
