# Task Module Specification

## Overview

The `task` channel is the core communication channel of the Yeow plugin, used to perform all game-state-related operations.

### Message Format

```json
{
  "type": "player.get",
  "params": { "key": "value" },
  "cb": "cb_42"
}
```

| Field | Type | Required | Description |
|------|------|------|------|
| `type` | string | Yes | Task type identifier, named by functional module (e.g. `player.get`) |
| `params` | object | No | Task parameters, each task has its own fields |
| `cb` | string | No | Callback ID. **Present → asynchronous**: the runtime immediately returns `null` and delivers the result through the callback. **Absent → synchronous**: the runtime blocks JS until completion and returns the result synchronously |

### Priority

Task priority can be controlled via the message **top-level** `priority` field (at the same level as `cb`):

| Value | Budget share | Description |
|----|---------|------|
| `high` | 50% | High priority (default priority of synchronous `call`) |
| `normal` | 30% | Default priority (default priority of asynchronous `post`) |
| `low` | 20% | Low priority |

```json
{ "type": "player.get", "params": { "identifier": "uuid" }, "priority": "high" }
```

If not provided or an invalid value is given, it falls back to `normal`. The runtime allocates execution time each tick according to the budget proportions. If the high-priority budget is not fully used, the remainder spills over to the next level. Once all budgets are exhausted, the remaining tasks queue up for the next tick. Finally, it enters a greedy phase (ignoring the budget and consuming as much as possible in priority order).

### Return Value Conventions

- Returns `true`/`false` — whether the operation succeeded
- Returns `null` — entity/player/world does not exist
- Returns `{}` object — contains specific data
- Returns `[]` array — list data
- Asynchronous tasks deliver results through the `cb` channel

### Error Handling

If an exception is thrown during task execution, the callback data field `r` is an object containing detailed error information:

```json
{
  "err": "java.lang.NullPointerException",
  "type": "NullPointerException",
  "task": "pdc.get",
  "stack": "java.lang.NullPointerException\n\tat ...\n\tat ..."
}
```

| Field | Type | Description |
|------|------|------|
| `err` | string | Exception message |
| `type` | string | Java exception class name |
| `task` | string | Task type that triggered the exception |
| `stack` | string | Full Java stack trace |

---

## Module List

| Module | Description | Task count |
|------|------|---------|
| [player](player.md) | Player operations | 51 |
| [entity](entity.md) | Entity + potion effects | 38 |
| [world](world.md) | World + block + chunk snapshot + sound + particle + entity spawn + WorldBorder | 49 |
| [inventory](inventory-gui.md) | Inventory + container blocks + custom Inventory | 17 |
| [server](server.md) | Server-global operations + Material queries + permission registration | 13 |
| [command](command.md) | Command registration + Tab completion | 4 |
| [event-system](event-system.md) | Event subscription/completion | 3 |
| [bossbar](bossbar.md) | BossBar | 12 |
| [scoreboard](scoreboard.md) | Scoreboard | 24 |
| [pdc](pdc.md) | Custom persistent data | 6 |
| [advancement](advancement.md) | Advancements | 5 |
| [recipe](recipe.md) | Recipes | 3 |

> Total: 225 tasks (`permission.register` counted in the server module; `material.*` counted in server; `block.breakNaturally`, `chunk.*` counted in world). The Paper and Folia task sets are **strictly identical** (Folia's `recipe.add` is dispatched internally by recipe type shaped/shapeless/furnace/blast/smoker/campfire, not as separate tasks). Counting basis: number of task-semantics cases in `Tasks.java` / `FoliaTasks.java`, 2026-08-18.
