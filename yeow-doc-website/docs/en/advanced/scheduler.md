# Scheduler & Tasks

> Three-level priority scheduler: time-slice budget, auto-demotion, idle spin; async vs sync, manual chunking; when to start executing game tasks (onLoad/onInit). Scheduler internals (three-level queue, auto-demotion algorithm, empty-queue spin) are described below in "Scheduler Design".

## Task Configuration (TaskOptions)

All API methods that go through the task channel (`player.sendMessage`, `world.setBlock`, etc., including Sync variants) accept an optional task configuration object at the **end of the parameters**, which is passed through to the scheduler:

```js
await player.sendMessage('Hello', { priority: 'high' });   // High priority
world.setBlockSync(0, 65, 0, 'stone', { priority: 'low' }); // Low priority (Sync also supported)
registerCommand('back', {...}, { priority: 'low' });        // For methods where options is already used, use taskOptions as the third parameter
```

`TaskOptions`: `{ priority?: 'high' | 'normal' | 'low' }` (extensible in the future). Property accessors (`player.ping`, etc.) cannot carry configuration; use the corresponding method form instead.

## Async vs Sync

### Async Operations (Default API)

```js
await player.sendMessage('Hello')
await world.setBlock(0, 65, 0, 'stone')
await entity.teleport(loc)
```

- Submitted via `post()` → `$send` async path
- Tasks enter the scheduler queue and execute in a future tick
- **Does not block JS code** — the JS thread can continue processing other messages
- **Subject to time-slice budget**: if the current tick's HIGH/NORMAL budget is exhausted, the task is queued to the next tick
- **Cross-plugin unified scheduling**: all plugins' async tasks execute in the same scheduler queue by priority
- Returns a `Promise`, which resolves via the callback mechanism upon completion

### Sync Operations (Sync Suffix)

```js
player.sendMessageSync('Hello')
world.setBlockSync(0, 65, 0, 'stone')
entity.teleportSync(loc)
```

- Submitted via `call()` → `$send` sync path
- The JS thread blocks on `CompletableFuture.get(task-sync-timeout-ms)` (default 10s, configurable in `plugins/Yeow/runtime/config.yml`)
- High priority by default
- (`call`) **blocks the JS thread** until the task completes. While blocked, the JS thread cannot process other messages, including:

- **SYNC_CALLBACK** (events) — event handlers cannot execute
- **TAB_COMPLETE** (command completion) — completion requests cannot be responded to
- **Callback messages** (`post` results) — async operations' Promises cannot resolve

If an event or completion request occurs during a sync operation, the main thread (currently spin-waiting in EventBridge or CommandTasks) will continue spinning until the JS thread resumes.

A small number of sync API calls won't cause issues, but prolonged sync operations (e.g., looping `setBlockSync` extensively) will prevent events and completion requests from being processed. This is generally not a problem because the JS thread's blocking does not affect the main thread.

However, once an event or completion request is triggered, the main thread waits for the JS thread's result for an extended period while the JS thread is performing a long sync operation, the event loop cannot be yielded, the completion request and event callback cannot be processed, the main thread waits until timeout, and the server reports "Can't keep up!".

**Recommended practices**:

| Scenario                                    | Solution                                                    |
| ------------------------------------------- | ----------------------------------------------------------- |
| Bulk repetitive operations (e.g., filling blocks, batch operations) | Use async API + `await`, allowing the JS event loop to process other messages |
| A small number of operations that must complete within a single tick | Sync API is fine (e.g., reading a few blocks, getting player attributes) |
| Long tasks that need to be executed in batches | See "Manual Chunking" below                                |

```js
// ✅ Recommended: async loop, does not block the event loop
for (let x = minX; x <= maxX; x++) {
    for (let y = minY; y <= maxY; y++) {
        for (let z = minZ; z <= maxZ; z++) {
            await world.setBlock(x, y, z, block);
        }
    }
}
```

### Manual Chunking

If you must use the sync API and need to process a large number of operations, you can use `setTimeout(fn, 0)` to chunk the task, voluntarily yielding the event loop:

```js
const blocks = [...];  // Large number of block operations
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

Voluntarily yields the event loop every 100 blocks; events and completion requests can be processed during the gap

### Sync Operation Constraints

Sync calls block the JS thread, during which events, completion requests, and other callbacks cannot be processed. A small number of calls in a single instance are fine, but prolonged sync loops will cause:

- Event handlers cannot execute → main thread spin-waits until timeout (server reports "Can't keep up!")
- Completion requests cannot be responded to → player's command input lags
- Async operation callbacks cannot resolve → Promises hang

When bulk operations are needed, prefer async `await` loops or use `setTimeout` for manual chunking.

### Batch Tasks (2026-08-13)

When **batch operations** (multiple independent tasks) are needed, you can use the batch API to submit an array of tasks at once and retrieve results at once — reducing round trips; suitable for dependency packages wrapping their own batch optimizations (e.g., batch item sending, batch block writing, batch reading):

```ts
import { callBatch, postBatch } from 'yeow-api';

// Sync batch: blocks until all complete, returns result array (ordered; individual failures produce {err} for that entry)
const [time, version, seed] = callBatch([
    { type: 'world.getTime', params: { world: 'world' } },
    { type: 'server.getVersion' },
    { type: 'world.getSeed', params: { world: 'world' } },
]);

// Async batch: resolves the result array once all complete
await postBatch([
    { type: 'player.sendMessage', params: { uuid, message: 'A' } },
    { type: 'player.sendMessage', params: { uuid, message: 'B' } },
]);
```

- Tasks execute independently one by one, **without atomicity** (intermediate failures do not interrupt subsequent ones); individual task failures produce an `{err}` object for that entry
- Each entry can carry `priority` (`high`/`normal`/`low`)
- Batch submission **does not change task semantics** — tasks are still executed individually through the scheduler, it simply reduces JS↔runtime round trips

### Selection Guide

| Scenario                                        | Recommendation                                    |
| ----------------------------------------------- | ------------------------------------------------- |
| Sending messages, broadcasting, setting blocks  | Async (default)                                   |
| Reading data (Player.getSync, world.time)       | Sync (`Sync` suffix or property accessors)        |
| Cancelling events in event handlers             | Sync (`e.cancelled = true`)                       |
| Command executors                               | Async (`async executor`)                          |
| Operations that must complete in the current tick | Sync (`Sync` suffix)                             |
| Executing console commands                      | Async preferred (`await dispatchCommand`)          |

## Scheduler Design

Yeow's scheduler uses a three-level priority queue + time-slice budget + auto-demotion architecture. All plugins' game tasks are scheduled uniformly.

### Three-Level Priority Queue

```
                ┌──────────────────┐
                │    HIGH Pool     │  ← High priority (`call` (sync tasks) and `post` with `high` parameter)
                ├──────────────────┤
                │   NORMAL Pool    │  ← Default priority
                ├──────────────────┤
                │     LOW Pool     │  ← Auto-demoted or manually set to `low`
                └──────────────────┘
```

Per-tick execution flow:

```
tick() called every 50ms by Paper main thread:

  ① First round (allocated by budget proportion)
     HIGH   50% budget → if unused, overflows to NORMAL
     NORMAL 30% budget → if unused, overflows to LOW
     LOW    20% budget → no overflow, stops when exhausted

  ② Greedy phase
     Ignores per-tier independent budgets, follows HIGH → NORMAL → LOW order
     Executes as many as possible until deadline is exhausted or all queues are empty

  ③ Idle spin
     After queues are empty, if deadline time remains, enters spin-wait (default 100μs)
     During spin, continuously checks all three queues; new tasks immediately enter the greedy phase
```

Per-tick total budget defaults to 20ms (`tick-budget-ms: 20`), configured in `plugins/Yeow/runtime/config.yml`. Tasks exceeding the deadline are queued to the next tick.

> **Priority parameter**: passed in at the API layer via `TaskOptions` (see "Task Configuration" above); the underlying `call`/`post` also accept legacy string priorities (`'high'` / `'normal'` / `'low'`).

### Auto-Demotion Algorithm

Auto-demotion prevents high-frequency NORMAL tasks from starving other tasks' execution time.

```
Demotion condition:
  When a NORMAL task is submitted
  → Check the call frequency of that plugin:taskType in the last 1 second
  → If it exceeds the threshold (default 200 times/second), demote priority to LOW

Demotion timing:
  Frequency is checked at submission time (submitGameSync/submitGameAsync),
  determining whether the task enters the NORMAL or LOW pool.
  Once enqueued, re-checking does not occur at execution time.

Recovery:
  When the frequency decreases, NORMAL tasks of that taskType are no longer demoted,
  naturally returning to the NORMAL pool for execution.
```

The frequency tracker `TaskFrequencyTracker` uses a sliding window algorithm:

```
1 second = 50 time slots, each 20ms

On each NORMAL task call:
  ① Calculate the time slot index for the current time
  ② If the slot is expired (>20ms since last update), reset to 0
  ③ Increment the slot counter
  ④ Sum all slot counts across the past 1 second
  ⑤ If total exceeds the threshold (default 200), return true (should demote)
```

The sliding window approach offers these advantages: no historical data retention, fixed 1-second window size, constant memory footprint (50 ints + 50 longs), and fast, stable computation.

### Empty Queue Extra Wait Mechanism

`idle-spin-us: 100` (maximum wait time, default 100 microseconds) addresses the following scenario:

```
JS thread submits task → Main thread just finished clearing the queue ← Time gap < 0.1ms
                           ↓
                  Without spin: waits 50ms for next tick
                  With spin: detects the new task within 100μs and executes immediately
```

Our empty-queue extra wait mechanism can, in the vast majority of scenarios, effectively improve resource utilization efficiency for Yeow plugins in certain common situations (up to 500x) while keeping server load stable.

For example:

```js
for(loc of locs){
  await world.setBlock(...loc, blockType);
}
```

Without the empty-queue extra wait mechanism, when Yeow scheduler load is low, the task execution efficiency of the above code is (1 task/tick, 20 task/s); with the empty-queue extra wait mechanism, task execution efficiency can reach (10000+ task/s).

### Sync Call Flow

```
call('player.getPing', {uuid})
  → $send('task', '{"type":"player.getPing","p":{"uuid":"..."}}')
  → PluginThread: scheduler.submitGameSync(pld, future, priority, name)
  → future.get(10s) [JS thread blocks; timeout adjustable via config's task-sync-timeout-ms]
  → Main thread tick(): takes from corresponding priority queue → Tasks.execute()
  → future.complete(result) [JS thread resumes]
```

### Async Call Flow

```
post('player.sendMessage', {...})
  → $send('task', '{"type":"...","p":{...},"cb":"cb_1"}')
  → PluginThread: scheduler.submitGameAsync(pld, cbId, callback, priority, name)
  → Returns immediately [JS thread not blocked]
  → Main thread tick(): executes → callback.accept(result)
    → queue.sendJs({t:"cb", p:"cb_1", r:result})
  → Message loop receives → Promise resolve
```

### fs / http / assets

These do not go through the main thread; PluginThread handles them directly:

```
$send('fs', '{"t":"readFile","p":{"path":"config.json"}}')
  → PluginThread.handleFs() → java.nio.file
  → Returns synchronously

$send('assets', '{"t":"read","p":{"path":"assets/config.a1b2c3d4.yml"}}')
  → PluginThread.handleAssets() → ZipFile reads the corresponding entry inside the JAR
  → Returns synchronously
```

Path safety: all file operations are restricted to `plugins/<pluginName>/`; `resolvePath()` blocks `../` traversal.

**Resource namespaces**: at build time, each dependency (the main project and qualifying npm packages) is assigned a unique namespace id for its `assets/`, and contents are copied verbatim into the JAR `assets/<id>/` (no hash renaming, so relative references are always valid). The JS side should always use `getAssetsPath()` (from `yeow-dev`) to obtain paths rather than hardcoding. See [Assets API](/api/assets).
