# Folia Support (Experimental)

> **Status: Experimental.** Core pathways (scheduler/tasks/events/commands/timers/PDC/text/permissions) have been verified on Folia 1.21. **Tasks and events are fully aligned with Paper** (tasks are strictly consistent with all 224 Paper test cases; events include 41 Bukkit events + permissionCheck ecosystem hooks, 2026-08-13). The official Paper-family (Paper/Purpur/Leaf, etc.) implementation remains `yeow-runtime`.

## Experimental Notice

Folia is a fork of Paper, with the core difference being **regionized multithreading**: there is no global main thread — the world is divided into regions by chunk, and each region is ticked in parallel by its own thread. This directly impacts Yeow's synchronous bridge model, which was originally built on a "single main thread" assumption. Therefore, Folia support is an **independent runtime implementation**, not an adaptation of the Paper runtime.

Current support scope (fully aligned with Paper tasks/events, 2026-08-13):

| Category          | Coverage                                                                                                                                                          |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| World             | Block read/write (including BlockData states), time/weather/difficulty/rules (global state writes auto-routed to global thread), spawn point, biome, lighting, entity query/spawn, drop/lightning/explosion/sounds/particles/chunk snapshots |
| Player            | Attributes/position/teleport/messages (MiniMessage)/Title/ActionBar/sounds/items/resource packs/permissions (including permissionCheck ecosystem hooks) — 40+ operations |
| Entity            | Attributes/custom name/health/glow/invulnerable/silent/gravity/bounds/passengers/vehicle/potion effects                                                           |
| Data              | PDC (entity/block/world), Material registry query and static checks                                                                                               |
| Events            | 41 Bukkit events fully aligned (player/entity/block/inventory/server classes) + `permissionCheck` ecosystem hooks                                                 |
| Commands          | Register/unregister/execute/Tab completion (permission node registration with PermissionDefault)                                                                   |
| Inventory/Misc    | Unified Inventory (player inventory / container blocks / custom chest UIs), BossBar, Scoreboard (creation restricted, see below), Recipe, Advancement              |
| Runtime           | Hot reload (dev WS), timers, fs/http/assets/service channels (core built-in)                                                                                      |

See [Architecture Implications](#architecture-implications) at the end for known differences.

## Yeow Architecture under Folia

### Layering

```
yeow-runtime/jvm/
├── core/    Platform-agnostic engine (QuickJS, message bridge, plugin lifecycle, TaskScheduler contract) — shared by Paper/Folia
├── paper/   Paper platform implementation (PaperScheduler: main thread consumes three-tier pool every tick — pre-split model, no dedicated scheduler thread)
└── folia/   Folia platform implementation (FoliaScheduler + FoliaTasks + event/command bridge, independently written, not shared with Paper)
```

The engine (plugin loading, JS thread, message channels, Service, Profile, permission model) lives in core — zero difference between platforms; all platform differences are confined to the **scheduler** and **task executors**.

### Folia Constraints (Why the Architecture Looks This Way)

Folia's regionized multithreading is a remarkable achievement, but due to its internal implementation complexity, the concurrency primitives Folia can stably expose to upper layers are quite limited — understanding the four constraints below is key to understanding why Yeow's scheduler uses "its own queue + its own budget + its own timeouts, with Folia only as a transport layer", and to understanding Yeow's dispatch cycle:

1. **Region threads are unstable**: The binding between a region and a thread is not a contract — regions can be dynamically loaded/unloaded, the thread pool can be reshuffled, and the same region may be ticked by different threads at different times. **You cannot assume "region X = thread Y" holds persistently**. Therefore, Yeow's residency marker must be re-parsed every time a cycle starts (`runCycleOn` parses the marker → re-dispatches; world targets can be resolved directly, uuid targets are constrained by AsyncCatcher and must go through the global thread). Thread references cannot be cached
2. **Threads are not identifiable**: The API does not provide "get the thread a task will run on" (`ScheduledTask` has no thread getter, and there is no identity query beyond `isOwnedByCurrentRegion`). **Identity can only be determined via ownership queries**, not by comparing thread names/ids
3. **Scheduling queues are non-intrusive**: Each region scheduler's internal pending-execution queue is a black box — you cannot peek into it, count items, determine "whether a task is enqueued / when it will execute", or intercept task enqueueing within a region thread. **Yeow must maintain all its own task pools** (high/normal/low tiers); Folia's scheduler only serves as a dispatch channel. After dispatch, only **handle-level control** remains (cancel / status query, see constraint 4)
4. **`Scheduler.run` control after dispatch is limited**: Once a task is dispatched, you cannot **intercept enqueueing** (Folia queues are FIFO, no priority hooks), and you cannot **observe execution completion** — region schedulers have **no** retired/completion callback (only entity schedulers do), nor can you set priority. After dispatch, only handle-level control remains: `ScheduledTask.cancel()` (cancel unexecuted tasks — Yeow uses this for "ghost execution" prevention in timeout fallback) and `getExecutionState()` (status query). Dispatched tasks may also **silently never execute** (world unload / region shutdown) — so Yeow attaches a **5s timeout fallback** to every dispatch (add error + reclaim in-flight), and all waits are capped with timeouts — making deadlocks structurally impossible

Folia exposes region scheduling as a black box. Yeow understands and respects this choice, but it brings substantial complexity.

**Available primitives** (two core + three extended usages, constituting Yeow's entire dependency on Folia's public API):

| Primitive                                                                                                                   | Usage                                                                                                    |
| --------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `Bukkit.isOwnedByCurrentRegion(target)`                                                                                     | Ownership query (core) — basis of `ownedHere`: determines "whether the target region is on the current thread" |
| Target region's Scheduler (`entity.getScheduler()` / `Bukkit.getRegionScheduler()` / `Bukkit.getGlobalRegionScheduler()`) | Dispatch channel (core) — basis of `getScheduler` closure: "hand off the task to the target region thread" |
| `Bukkit.isGlobalTickThread()`                                                                                               | Ownership query extension — GLOBAL tasks execute in-place during the global transient (eliminates double-hop from a second dispatch) |
| `ScheduledTask.cancel()`                                                                                                    | Handle control extension — cancels unexecuted tasks during timeout fallback (prevents "caller already got an error, but the task still executes later" ghost execution) |
| `getGlobalRegionScheduler().runAtFixedRate`                                                                                 | Periodic task extension — triple duty for per-tick scheduled tasks: budget-exhaustion restart / watchdog (1s liveness) / coarse-grained scan of in-flight dispatch timeouts |

Yeow's scheduler relies only on these primitives, supplying its own queues, priorities, budget, **migration**, and timeouts. The migration mechanism allows Yeow's scheduler, outside of Folia's black box, to maximize local execution of task logic in hotspot regions. The migration mechanism works by using the above primitives with heuristic rules, so the scheduler automatically gets dispatched by Folia to reside in hotspot regions via `Scheduler.run()`.

### Scheduling Model: Non-blocking Scheduler + Region Residency

Folia has no "main thread" to spin on, so Yeow's synchronous bridge (JS synchronous calls that wait for game thread results) must be redesigned. The Folia scheduler follows these principles:

- **The scheduler is not a separate thread**: It "resides" (borrows) on a region thread, running as a **dispatch cycle** — the cycle itself is posted as a task to the residence region's thread, consuming the task queue in the gaps between region ticks
- **Non-blocking dispatch**: After picking up a task, the dispatch cycle **immediately dispatches** it (target in residence region → execute in-place; in another region → async dispatch via Folia scheduler), and **never waits for the task result** — when the task completes, the target thread calls back (completes the future, wakes the scheduler). Therefore multiple tasks can execute **in parallel** across multiple regions (concurrency capped by in-flight limit)
- **JS synchronous semantics preserved**: The plugin's JS thread still waits on `future.get` — the wait happens on the **JS thread**, not consuming the scheduler

#### Task Type Three-Function Contract

The scheduler has **zero awareness of task types** — each task type (sharing implementation within a family) provides exactly three things (cohesive in `FoliaTasks`):

| Function               | Role               | Description                                                                                                                                                            |
| ---------------------- | ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ownedHere(params)`    | Current thread ownership check | **Determined directly from raw parameters** (no reverse lookup via key); the scheduler uses this to decide between in-place execution or dispatch          |
| `getScheduler(params)` | Target scheduling handle | `DispatchTarget{ marker, run }`: marker is a plain string residency marker (calculable by any thread); run is a **lazy scheduling closure** (zero Bukkit calls at construction, only resolves the entity on the global thread and dispatches to the target at dispatch time) |
| `execute(params)`      | Task body          | Corresponds to the Paper-side task implementation                                                                                                                      |

The scheduler only consumes these three things; entity/world/chunk coordinate resolution within task families is fully encapsulated in the respective implementations. When adding new task types, the three functions are implemented in parallel for the corresponding branch.

#### Dispatch Cycle

```
Dispatch cycle running on the thread of residence region X:
  Queue non-empty → continue processing:
    ├─ Event/completion mode active → exit (event thread takes over)
    ├─ in-flight task count ≥ limit (default 100) → exit (task completion callback wakes)
    ├─ Budget exhausted (wall time) → exit (maintain placeholder; per-tick scheduled task restarts at next tick boundary, wait ≈ remaining window ~30ms)
    ├─ Pick task → ownedHere(params) is true (or GLOBAL task and already on global thread) → execute in-place (microseconds, does not count toward in-flight)
    │             Otherwise → Path B: getScheduler().run dispatch (in-flight++; GLOBAL dispatches to global, not counted toward migration statistics)
    └─ Two consecutive non-local tasks → yield the residency marker (await preemption, see migration mechanism)
  Queue empty → block waiting for `folia.scheduler-idle-wait-us` (default 2000us) for new tasks; only exit if still empty (submit wakes early)
```

> **Idle wait is parking, not busy-spinning**: When the queue is empty, the dispatch cycle **blocks** on `cycleLock` (`Object.wait`);
> new tasks / task completion / event mode end wake it early via `wake()` `notifyAll`. This wait covers
> the **net overhead** — the round-trip gap of the JS thread (complete → JS wake → loop body → submit), typically measured
> **usually < 50µs**; when region threads are fully saturated, OS wake latency may rise to 100µs~ms level, an occasional peak.
> Busy-spin is not used because: the microsecond response advantage of busy-spin only exists at low load; under saturation the gap
> with parking vanishes, but it continuously occupies an entire core; parking releases the CPU, and the region tick pause semantics are consistent with spinning
> (the cycle task occupies the region thread, so the tick is already paused). Actual blocking duration may slightly exceed the configured value
> due to OS timer granularity (Windows ~1-15.6ms) — exits if still empty, only once at cycle tail.
> (The parameter name `scheduler-idle-wait-us` replaces the earlier `scheduler-spin-us`: semantics changed from spinning to blocking wait.)
> **Note: This wait only covers "iteration gaps during cycle lifetime"; the JS thread's own OS wake latency is not controlled by this parameter.**

- **for await pattern optimization**: In plugin loops with consecutive synchronous calls (`for (..) await e.player.xxx`), the targets are all in the player's region — once the scheduler resides in that region, tasks **execute in-place** (skipping region scheduler queuing), reducing loop iterations from "one tick per task (~50ms)" to microseconds. Measured locally (**total plugin-perceived latency including ticks**): 30 setBlock loop iterations 2-6ms (0.1-0.2ms each); 400 gapless continuous iterations 83-89ms (0.2ms each). The net overhead (JS round-trip gap) is a small fraction — total latency is mainly composed of wake + scheduling + execution
- **Budget (wall time)**: Within each 50ms window, when the scheduler's active wall time exceeds the budget (`folia.tick-budget-ms`, default 20ms), dispatch stops, tasks remain queued, and dispatch resumes after the window rolls over. **Budget measures wall-clock passage, not the sum of individual task durations** — summing would artificially inflate budget during multi-region parallel execution; wall time is friendlier to parallelism. The budget also limits the cycle's residence duration, ensuring the residence region gets at least 30ms/50ms of tick time. When budget is exhausted, the cycle maintains its placeholder and is **restarted by the per-tick scheduled task at the next tick boundary** — the wait is ≈ remaining window time (typically ~30ms); under sustained load, synchronous calls may experience a single ≤30ms jitter, inherent to the budget model. **A single task can exceed the budget** (budget is only checked between tasks, preserving task atomicity) — long tasks are borne by the executing thread; the scheduler does not interrupt. Restart is only triggered by the scheduled task after the cycle fully exits, and **never overlaps with running tasks**
- **in-flight cap**: The number of dispatched incomplete tasks never exceeds the configured limit (`folia.max-inflight`, default 100), preventing unbounded dispatch from overwhelming a region
- **Dispatch timeout fallback**: Path B dispatches are recorded in the in-flight registry, scanned coarsely by the per-tick periodic task (every 1s) — if not completed within 5s, **first `cancel()` the target task** (prevent ghost execution: the caller already received a timeout error; the task must not execute later; cancel on an already-completed/running task is a no-op), then add the error and reclaim in-flight. Region schedulers have **no** retired callback (only entity schedulers do); when a world unloads or a region shuts down, tasks may never execute; without the fallback, in-flight would leak permanently → once the limit is reached, the scheduler stalls. **Periodic tasks also serve as a watchdog**: if the cycle should be running but shows no activity for 1s (e.g., startup dispatch silently dropped) → force restart (long task false triggers are harmless — the resumed dispatch and the running cycle are serial on the same thread)
- **Priority**: The only dequeue rule = **always prioritize higher priority** (HIGH → NORMAL → LOW, FIFO within each pool); LOW starvation is prevented by **automatic demotion at submission time** (same sliding window algorithm as Paper: when a plugin's NORMAL call frequency for a task type exceeds `demote-threshold` (default 200/sec), it is automatically demoted to LOW; when frequency drops, it naturally recovers)

> **Configuration ownership**: `folia.scheduler-idle-wait-us`, `folia.tick-budget-ms`, `folia.max-inflight`,
> `folia.migration-threshold` are Folia-specific / semantically different parameters, unified under the `folia:` section in `config.yml`,
> not mixed with Paper parameters at the top level.

#### Migration Mechanism

The scheduler's residence region is determined by **hotspots**; migration is based on **yield + preemption**:

1. **Yield**: When the dispatch cycle encounters `folia.migration-threshold` (default 2) consecutive **non-local tasks** in the residence region (GLOBAL does not count) → sets the residency marker to "unowned", but **does not actively yield** — the current cycle continues processing in the original region (local tasks still execute in-place)
2. **Preemption**: In the unowned state, the next task (tried on both Path A/B) **preempts the residency marker** before execution — the hotspot follows the most recent task's target; after preemption, the cycle re-anchors to the new marker via `runCycleOn` when it restarts after budget exhaustion or idle exit (parsing failure clears stale markers: auto-released after entity disconnect / world unload)

> **Migration threshold tuning**: The threshold is configurable (`folia.migration-threshold`). **Increasing it** provides more stability in complex environments (multi-player/multi-plugin, hotspots frequently oscillating between regions) — prevents repeated yield-preemption from one or two foreign tasks; but **increases hotspot migration delay** (more consecutive foreign tasks needed before yielding, during which foreign tasks continue via dispatch). Within a reasonable range, this parameter has minimal impact on observable performance. Default is 2; not recommended to set too high.

The migration mechanism ensures the scheduler always stays close to task hotspots: tasks in hotspot regions execute in-place, tasks in cold regions execute via dispatch (async parallel). Residence benchmark (local, total plugin-perceived latency including ticks): setBlock loop switching between three coordinate sets (A → B, 1000 blocks apart → back to A), 30 times per set, 2-6ms (0.1-0.2ms each) — coordinate switching does not interrupt in-place execution during residence.

#### Event/Completion Mode: Temporary Scheduler

When an event fires (or command completion request arrives):

- **General scheduler pauses**: The dispatch cycle exits during event activity, resumes after the count drops to zero
- **Each event-triggering region thread becomes a temporary scheduler**: Spins to consume the task queue, **only picking tasks for the plugin that dispatched the event** (pure L approach: plugin name filtering, no ownership resolution). Design rationale: events map one-to-one to (region, plugin set); concurrent events naturally fan out by plugin, with no contention, and speeds up event processing; **no migration**
- **Events and completions are not mutually exclusive**: Multiple events can be processed in different regions simultaneously
- **Per-round execution cap** (64 tasks): The event thread is not slowed by backlog from the event itself (latch check exits promptly between each drain round)
- Each task is still handed to the executor: target in current thread → execute in-place; otherwise dispatch to the target region (same non-blocking logic as the general scheduler's executor)

> **Trade-offs of pure L**: During an event, tasks from other plugins are not picked up (cycle paused) — their synchronous calls
> wait for the event to end (latency bounded: ≤ event timeout 5s), which is an acceptable compromise. The only deadlock scenario:
> an event listener enables manual mode (manualRelease) and waits on a service involving game operations
> provided by another plugin/Worker — this is an anti-pattern, covered by the 5s timeout fallback; the fan-out design is not compromised for this.
> (Aligning with Paper's drainDuringWait full drain would be a K approach; after weighing trade-offs, it was not adopted.)

### Plugin JS Single Thread is the Fulcrum

Each plugin corresponds to a single JS thread (processing messages serially). During event processing, that plugin's JS thread is occupied by the event. Therefore, the event thread picking tasks by **plugin** from the queue is naturally correct: the event handler's synchronous calls (`e.player.location`, `event.complete`, etc.) are always consumed in-place by the event thread, with no need for buffering or cross-plugin coordination. At the same time, events within the same plugin are naturally serial (JS thread processes one message at a time), while events across different plugins can run concurrently.

This fulcrum is exactly why the pure L approach works — during an event, the plugin's tasks can only come from the handler; picking the plugin's tasks covers all of the handler's synchronous calls; tasks from other plugins are left for the cycle after the event ends.

## Architecture Implications

1. **Event processing only blocks its own region**: While the event thread spins, that region's tick stalls (capped at the event timeout, default 5s); other regions continue running in parallel
2. **Cross-plugin events run in parallel**: Different plugins' JS threads do not interfere; events can run concurrently; same-plugin events are serial (JS single thread)
3. **Synchronous calls within an event should only access the event target**: Calls like `e.player.xxx` are executed in-place by the event thread and return immediately; **cross-region synchronous calls** (accessing other entities/worlds) are dispatched by the executor to the target region — if the target region is idle, execution is immediate; if it happens to be busy with an event, it waits until timeout. **For cross-target operations in event handlers, use async APIs (`post`)**
4. **Synchronous API cross-region calls have latency**: During event processing, synchronous API access to cross-region resources is dispatched to the target region thread (next tick cycle, 0~50ms). Although Folia's region design provides data locality and cross-region operations in events are relatively rare, async APIs are still recommended during event processing
5. **Synchronous calls in non-event scenarios also benefit from residency**: When the scheduler is near a hotspot region, synchronous calls execute in-place (microseconds) — loop patterns are no longer dragged down by the 50ms tick cycle
6. **Global tasks (`server.*` etc.) are unaffected during events**: Dispatched to the global region via Folia (global region does not participate in event spinning or scheduler residency)
7. **Async task cross-region order is not guaranteed**: On Paper, async tasks run serially on the main thread in FIFO order (`post(a); post(b)` guarantees order); on Paper-family, cross-region async tasks execute in parallel with indeterminate completion order — **for dependent consecutive operations, use `await` to serialize**
8. **Budget-exhaustion restart has ≤30ms jitter**: Under sustained load, synchronous calls may encounter a single ≤30ms pause (waiting for the next tick boundary = remaining window time), inherent to the budget model semantics, not an anomaly
9. **Event reentrant deadlock**: When the event thread drains the queue executing synchronous tasks and that task triggers a new event (e.g., `performCommandSync` → `playerCommand`), the nested spin waits for JS while JS is blocked by the synchronous call → deadlock until event timeout (default 5s, bounded fallback, present in both Paper/Folia). **In event handlers, unless the logic is very simple, do not use synchronous operations (including property reads/writes)** — see [Events & Callbacks - Event Reentrant Deadlock](events.md#event-reentrant-deadlock)

> **Known differences**:
> - `server.getTps` returns `null` for all three values (Folia has no global TPS concept; plugins must handle unavailability themselves)
> - **Scoreboard creation restricted**: `board.createObjective(...)` / `board.createTeam(...)` return explicit errors (Folia only supports reading/modifying existing objectives/teams; all overloads of `registerNewObjective`/`registerNewTeam` throw `UnsupportedOperationException`) — see [Scoreboard API](../api/scoreboard.md)
> - **Global state writes auto-routed**: `world.setTime` / `setStorm` / `setThundering` / `setDifficulty` / `setSpawnLocation` / `setGameRule` can only be modified on the **global region thread** on Folia (AsyncCatcher intercepts) — Yeow runtime automatically routes these tasks to the global thread; plugins see no difference

## Platform Transparency

- **Upper API is identical**: `yeow-api`'s Player/World/Event/Command/Worker/fs/http… have zero usage differences between Paper and Folia; event fields, command protocols, and MiniMessage text semantics are all the same
- **Plugin packages are platform-agnostic**: `.yeow.zip` contains no platform bindings; the same plugin can be migrated directly between Paper and Folia
- **Differences are only in runtime implementation**: Scheduling model, threading model, and task executors are internal platform details; plugin authors need not be aware; the `platform` field in `getEnv()` can be used to distinguish (`"paper"` / `"folia"`)

## Deployment

**Identical to Paper**:

1. Place the Folia runtime jar (`yeow-runtime-folia-0.5.0.jar`) into the server's `plugins/`
2. Place plugin packages (`.yeow.zip` or template JAR) into `plugins/Yeow/` (auto-scanned) or load dynamically via `/yeow load <path>`
3. Plugin data, `/yeow` admin commands, hot reload, etc. behave the same as Paper

Development mode: `npm run dev` is the same as Paper — just point `paperJar` in `yeow.config.json` to the Folia build, and replace the runtime in `.yeow/assets/` with the Folia runtime jar.
