# Architecture & Threading Model

> Architecture overview: package structure, startup flow, threading model, plugin entity abstraction, Worker (virtual plugin), dev-mode error echo, resource path mechanism (getAssetsPath).

## Architecture Overview

```
    ┌─────────────────────────────────────────────────┐
    │          Plugin Package .yeow.zip / JAR (ZIP)    │
    │  ┌───────────────────────────────────────────┐  │
    │  │ .yeow/main.js     (esbuild-bundled JS)     │  │
    │  │ assets/<id>/      (resources, namespaced)   │  │
    │  │ yeow.json         (metadata + perms + native)│ │
    │  │ plugin.yml        (Paper metadata, required │  │
    │  │                    for JAR)                 │  │
    │  └───────────────────────────────────────────┘  │
    └──────────────────────┬──────────────────────────┘
                           │ YeowRuntime.registerPlugin()
                           ↓
    ┌─────────────────────────────────────────────────┐
    │               Yeow Runtime (Java)                │
    │                                                  │
    │  ┌──────────────────┐  ┌─────────────────────┐  │
    │  │  PluginEntity 1   │  │  PluginEntity 2     │  │
    │  │  (PluginThread)   │  │  (Adapter / Worker) │  │
    │  │  QuickJS + message│  │  Message-driven loop│  │
    │  │  driven loop      │  │  fs/http/assets self│  │
    │  │  fs/http/assets   │  │  handled            │  │
    │  └───────┬──────────┘  └──────┬──────────────┘  │
    │          │                    │                 │
    │  ┌───────┴────────────────────┴──────────────┐  │
    │  │  Scheduler (per tick)                     │  │
    │  │  Three-level queue: HIGH / NORMAL / LOW   │  │
    │  │  Time-slice budget + auto-demotion        │  │
    │  └────────────────┬──────────────────────────┘  │
    │                   │                             │
    │  ┌────────────────┴─────────────────────────┐  │
    │  │  EventBridge                              │  │
    │  │  Paper events → plugin → applyMods()      │  │
    │  │  (auto-registered if unregistered;        │  │
    │  │   skipped if no subscribers)              │  │
    │  └──────────────────────────────────────────┘  │
    └──────────────────────┬──────────────────────────┘
                           ↓ Paper APIs
    ┌─────────────────────────────────────────────────┐
    │                    PaperMC                      │
    └─────────────────────────────────────────────────┘
```

### Package Structure

| Package          | Language   | Purpose                                                        |
| ---------------- | ---------- | -------------------------------------------------------------- |
| `yeow-api`       | TypeScript | Dev-time npm dependency providing OOP wrappers. Bundled by esbuild into `.yeow/main.js` |
| `create-yeow`    | Node.js    | CLI scaffolding tool, generates project templates + build scripts |
| `yeow-runtime`   | Java       | Paper plugin, manages the QuickJS engine and Paper API bridge  |
| `yeow-template`  | Java       | Empty JAR skeleton; JS code is injected at build time          |

## Startup Flow

When Paper loads plugins:

```
Paper startup
  → YeowRuntime.onLoad()
    → Read init.js → runtime bootstrap code
    → Read plugins/Yeow/runtime/config.yml → scheduler config
  → Bootstrap.onLoad() [each template JAR plugin]
    → Read .yeow/main.js → userCode
    → Read yeow.json → plugin metadata + permission declarations
    → YeowRuntime.registerPlugin(jarPath)
      → Create PluginThread(name, jarPath, userCode, permissions)
      → Thread starts
  → YeowRuntime.onEnable()
    → Auto-scan plugins/Yeow/*.yeow.zip → registerPlugin (same as JAR behavior)
    → Register /yeow admin command
    → Register per-tick scheduler
    → Send {t:"LOAD"} message to each plugin
```

JS thread startup:

```
PluginThread.run()
  ① ctx = QuickJSContext.create()
  ② inject() → $_send
  ③ ctx.evaluate(init.js)    → console, _cbs, fetch, $hm
  ④ ctx.evaluate(userCode)   → registerCommand, eventOn, onInit/onLoad registrations
  ⑤ Directly call $hm({t:"INIT"}) → onInit callback executes (not enqueued, guaranteed before all messages)
  ⑥ Message loop starts
  ...
  ⑦ Receive {t:"LOAD"}      → onLoad callback executes
```

## Threading Model

| Thread                      | Responsibility                                        |
| --------------------------- | ----------------------------------------------------- |
| **Paper main thread**       | Calls Scheduler.tick() every tick (50ms), processes game tasks |
| **JS thread** (per plugin)  | Runs plugin JS, handles message loop, directly handles fs/http/assets |
| **Timer thread** (per plugin) | Sends messages to JS thread when timers expire       |
| **Fetch thread**            | HTTP requests (one thread per request)                |

Message queue (MsgQueue, **message-driven**):

```
Java → Plugin: postMessage(msg) → enqueue (atomic) → wake up plugin message loop
          Plugin thread blocks waiting for messages (zero polling); processes immediately upon receipt;
          if more messages remain after processing, takes next one immediately;
          if none remain, returns to blocking wait ("message loop stopped", only a semantic wait state)
JS  → Java: $_send channel messages → Scheduler tick() processes game tasks / plugin thread directly handles fs etc.
```

### Timer Resource Management

- Each plugin owns an independent `ScheduledExecutorService` (thread name: `timer-<pluginName>`)
- All `ScheduledFuture` instances are stored in the `timerFutures` list
- On unload/reload: `cancel()` all Futures + `shutdownNow()`; `scheduler.purgePluginTasks(name)` cleans up remaining PendingTasks

## Platform Independence

Yeow plugins are **platform-independent**:

- A plugin package is a ZIP (`.yeow.zip` or deployed as JAR) containing `.yeow/main.js` (bundled JS), `assets/`, and `yeow.json` (with permission declarations)
- Does not depend on a Java environment — the runtime is not limited to a specific language/platform
- Placing it in `plugins/Yeow/` will cause the runtime to auto-scan and load it (manual loading via `/yeow load` is also supported)
- Any runtime conforming to the [Platform Specification](/specifications/README) can load and run Yeow plugins:
  1. Understand the plugin package structure (read `yeow.json`, `.yeow/main.js`, `assets/`)
  2. Implement a scheduler (task queue + priority + time slices)
  3. Implement an executor (translate tasks into host platform game operations)
  4. Implement a standards-compliant JS runtime (`$_send` bridge, callback protocol, lifecycle messages)
  5. Implement channels (fs / http / assets / service / timer, etc.)

The Paper-family (Paper/Purpur/Leaf, etc.) yeow-runtime is the official reference runtime implementation; the Folia runtime is the second official implementation ([Folia Support](folia.md)). See the [Platform Specification](/specifications/README) for more plugin package formats.

## Plugin Entity Abstraction

The runtime treats every plugin as a **`PluginEntity`** interface: it can receive messages (`postMessage`), has lifecycle methods (`start` / `stopAndWait` / `reload`), and behavioral metrics (`ping()` heartbeat round-trip). JS-specific details (QuickJS context, `$hm` message protocol, init.js) exist only within the JS adapter (`PluginThread`); the scheduler / event bridge / command bridge / Service / Profile all depend solely on this interface:

- **Scheduler** only knows plugin names (for submitting tasks and reply callbacks), not the execution engine
- **Profile** uniformly collects response latency via `ping()`; in-flight management is handled by the adapter
- **Virtual plugins**: Worker entities implementing `PluginEntity` ([Worker API](/api/worker)) participate in the full pipeline; the `isVirtual()` flag distinguishes them in performance stats and alerts

## Worker (Virtual Plugin)

A Worker is a **virtual plugin** — a concrete implementation of `PluginEntity` (`WorkerThread`) that provides the main plugin with a **dedicated thread + independent QuickJS context** for parallel execution. See the [Worker API](/api/worker) for API usage and the [Worker Channel Specification](/specifications/message/worker) for channel protocols.

### Essence

- **It is a plugin**: registered as a plugin entity with the name `<mainPlugin>.<workerName>` (globally unique) — scheduler / event bridge / command bridge / Service / Profile all treat it as a normal plugin
- **It is virtual**: `isVirtual() = true` — the `/yeow` admin command does not cover it; profiler reports and alerts carry a `(worker of <mainPlugin>)` tag
- **Dependent on the main plugin**: does not run independently — when the main plugin is unloaded/hot-reloaded, the Worker is also unloaded (handles are fully destroyed); creating Workers inside a Worker is also **prohibited**
- **Shared, not independent**: has no data directory or permissions of its own — fs plugin-level base points to the main plugin's data directory (`plugins/<mainPlugin>/`), the assets channel uses the same namespace, and permissions are directly inherited from the main plugin (no independent declarations)
- **Lifecycle**: `createWorker` only registers (obtains a handle); `load()` executes `init.js → worker-inject.js → Worker code → INIT → LOAD`; **cannot be destroyed, only unloaded** — `unload()` physically destroys the JS context and cleans up its events/commands/services/tasks; the handle is retained and can be re-`load()`ed

### Implementation

```
Main plugin PluginThread (JS) ──$_send('worker')──► Java main thread
   │   create: register handle (not started)
   │   load:   registerPluginEntity (into plugins map + profiler) → WorkerThread.start()
   │   post:   deliver {"t":"cb","p":"__workerMessage","r":msg} to Worker queue
   │   reload/unload: lifecycle control
   └── postToMain (Worker internal channel) ──► main plugin JS side's onMessage callback for that Worker
```

`WorkerThread` is an independent variant of `PluginThread`:

- Independent QuickJS context + thread (`yeow-worker-<mainPlugin>.<workerName>`) + MsgQueue; error reports include `origin` (worker name)
- `$_send` full channel:
  - `task` → scheduler (`_plugin` = registered name; independent stats/purge)
  - `timer` → independent ScheduledExecutorService (cleaned up with the Worker)
  - `fs`/`assets`/`http` → **delegated to the main plugin** (shared data directory, permissions, resources; HTTP listeners follow the main plugin lifecycle)
  - `service` → independently handled (registers services under the registered name)
  - `worker` → only accepts `postToMain` (nested creation is rejected)
- Bootstrap scripts: init.js (standard environment) + worker-inject.js (`__workerId`, internal message callback `__workerMessage`)
- `ping()` heartbeat integrates with Profile (consistent with regular plugins)

### Integration with Other Systems

| System        | Integration                                                                                                      |
| ------------- | ---------------------------------------------------------------------------------------------------------------- |
| Scheduler     | Worker's `call/post` goes through the task channel; `_plugin` is injected with the registered name — independent queue stats and purge |
| Event Bridge  | `eventOn` subscribes under the registered name (EventBridge.subs keyed by registered name); `unsubscribeAll` on unload |
| Command Bridge | `registerCommand` registers under the registered name; `unregisterAll` on unload                                |
| Service       | `registerService` registers under the registered name (`ServiceManager.requestPlugin` delivers via entity); `purgePluginServices` on unload |
| Profile       | Heartbeat stats registered at `registerPluginEntity` time; `isVirtual()` + `source()` (main plugin name) tagged in reports/alerts |
| /yeow Admin   | `realPluginNames()` filters `isVirtual()` — unload/reload/uninstall/track/tabComplete do not cover Workers       |
| Lifecycle     | Main plugin's `cleanupResources` calls `unloadPlugin(worker)` for each (full cleanup) + `workers.clear()`         |

### Developer Toolchain

`yeow.config.json`'s `dev.worker` declares Worker bundling:

```json
{ "dev": { "worker": [
    { "name": "web-worker", "entry": "worker/web-worker/index.ts", "dist": "assets/worker/web-worker.js" }
] } }
```

- **Build order**: `build.js` first bundles each Worker via `esbuild` (`entry → assets/<rootId>/<dist>`, with sourcemaps in dev mode), **then bundles the main plugin** — the main plugin reads Worker artifacts via `getAssetsPath(dist)`; build-time dependencies (`yeow-dev`) are injected under the main plugin's namespace; Workers share `yeow-api` with the main plugin
- **Hot reload**: dev-server watches the directory of each Worker's `entry` — source changes → rebuild (Worker + main plugin) → hot-reload → main plugin re-`createWorker`/`load`, Worker is rebuilt accordingly
- **Error echo**: js-error messages carry an `origin` (`main` or worker name) — dev-server selects the corresponding source-map (`dist/.dev/.assets/<id>/worker/<name>.js.map`) based on `origin` for decompilation, outputting `JS Error in Worker <name>` + code context
- **Package authors**: debug in a real project; after successful testing, manually place the bundled Worker files into the resource directory (`dist` declared path)

## Dev-Mode Error Echo

In development mode (`npm run dev`, runtime with `-Dyeow.dev=true`), plugin errors go through a complete echo pipeline back to the terminal:

```
Plugin JS error → init.js reportError / uncaught exception → $_send('debug', {t:'reportError'})
  → PluginThread parses (message/stack/fileName/line/column)
  → dev WebSocket → dev-server (built into create-yeow, port 17368)
  → source-map library decompiles bundled positions back to src/ source code
  → Terminal output: error line ±3 lines context + → locator + async call chain
```

**Async stack traces** (dev mode only): each callback/async request captures the user call stack **at registration time**; on error, it is appended to the error in segments marked `--- cb registered at ---`, `--- promise chain ---`, and `--- outer callback ---` — even deeply nested callbacks can have their origin traced. `console.log` is unaffected; production mode incurs no stack capture overhead (errors are only output to the server log).

- Manual reporting: `logError(e, context?)` (actively report in `catch` blocks, enjoying the same source-map decompilation)
- See [CLI Reference - Debug Experience](/cli#debug-experience)
