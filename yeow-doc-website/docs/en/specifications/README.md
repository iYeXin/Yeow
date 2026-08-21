# Platform Specification

> **Who is this directory for?** It targets developers who want to **implement a Yeow-compatible runtime** (e.g., writing a runtime bridge for another platform). It describes the **protocol layer** between plugin JS code and the runtime: package structure, loading flow, message channel formats, task types, event data, and Native Service communication.

> **Regular plugin developers do not need to read this directory.** For day-to-day development, use the [API Reference](../api/README.md) — yeow-api encapsulates all underlying protocols.

---

## Directory Structure

| Subdirectory                             | Content                                                                           |
| ---------------------------------------- | --------------------------------------------------------------------------------- |
| [message](message/index.md)             | Message formats for non-dispatcher channels (timer / fs / http / assets / service / debug, etc.) |
| [task](task/index.md)                   | Dispatcher task type catalog (request/response formats for `player.get`, `world.setBlock`, etc.) |
| [event](event/index.md)                 | Event subscription mechanism and data fields for each event type                  |
| [native-service](native-service/index.md) | Native Service subprocess protocol (TCP JSON line)                              |
| [runtime](runtime/index.md)             | Runtime mechanics (JS environment, callback system, global variables, event loop) |
| [values.md](values.md)                  | **Value Appendix**: format rules (R1–R5) and listings — platform-specific enums maintained directly (gamemode/difficulty/BossBar/scoreboard/ClickType/ItemFlag/InventoryType, etc.) + reference implementations (non-normative: DamageCause/teleport cause/regen cause); version-varying domains (blocks/items/entities/biomes/sounds/particles/enchantments/potions/attributes/damage types/game rules/translation keys/advancements/recipes) with rules and authoritative links |
| [adapter](adapter/index.md)             | Plugin adapter specification (multi-language / community adapters implement PluginEntity and register) |

---

## Protocol Overview

**JS → Runtime**: The specification defines `$send(channel, payload)` — `payload` is a **pure JS object** (the runtime handles serialization and transmission). `$_send`, the underlying communication format (not limited to JSON), and the message loop are all **internal implementation details** that implementors may decide at their own discretion (see [Internal Implementation Boundaries](#internal-implementation-boundaries)).

**Runtime → JS**: The callback delivery format (e.g., `{"t":"cb","p":"<callbackId>","r":<data>}`) is defined by the implementor and dispatched by the JS-side bootstrap script.

**Lifecycle Messages**: `INIT` / `LOAD` / `DISABLE` / `RELOAD`, handled by the bootstrap script.

Detailed formats are documented in each subdirectory.

---

## Internal Implementation Boundaries

The Yeow Platform Specification only defines behavior at the **contract level**. The following are **not** constrained by the specification and may be decided by the implementor:

- **`$_send`**: The underlying raw JS→runtime bridge function is an internal implementation detail — the specification only guarantees the semantics of `$send(channel, payload)` (accepting pure JS objects)
- **Communication Format**: The transport format between JS and the runtime is **not limited to JSON** (e.g., binary, structured clone, etc. may be used at the implementor's discretion); examples in the specification are presented in JSON
- **Message Loop**: The JS-side message dispatch/event loop mechanism is an internal implementation detail (e.g., blocking wait, polling, event-driven); the specification only requires semantic correctness (messages are eventually processed, callbacks are delivered, lifecycle events are triggered)

> Plugin code **may only depend on** `$send` and its return value semantics. It must not depend on `$_send`, message loop timing, or specific transport formats.

## Yeow Plugin Package Structure

A Yeow plugin is a **ZIP archive** (deployed as `.jar` on Paper-based servers, or `.yeow.zip` when placed in the plugin directory — both are fundamentally ZIP files). The runtime only needs to read the ZIP and **does not depend on a Java environment**.

```
my-plugin.jar / my-plugin.yeow.zip (ZIP)
├── yeow.json              ← 插件元信息 + 权限声明（必需）
├── .yeow/
│   ├── main.js            ← esbuild 打包的插件代码（IIFE 格式，生产构建）
│   └── dev.json           ← 开发模式信息（仅 dev 构建，见下）
├── assets/                ← 打包资源（按命名空间 id 分目录）
└── plugin.yml             ← 宿主平台元信息（Paper 系需要；`.yeow.zip` 与纯平台实现可忽略）
```

> **`.yeow.zip` and JAR behave identically**: The runtime registers them using the same logic (read `yeow.json` → permissions → code → startup). Placing them in the runtime data directory (official Paper implementation uses `plugins/Yeow/`) triggers automatic scanning and loading. They can also be loaded manually via `/yeow load <path>`. Only one instance per plugin name is allowed; duplicate loads are rejected with a warning.

### `yeow.json` — Plugin Metadata

```json
{
    "name": "my-plugin",
    "version": "1.0.0",
    "author": "",
    "description": "A Yeow plugin",
    "api": "1.18",
    "java": 21,
    "permissions": ["fs:server.*", "http:requestAsync", "service:registerNative"]
}
```

| Field                 | Description                                                                                   |
| --------------------- | --------------------------------------------------------------------------------------------- |
| `name`                | Plugin name (injected as `__plugin.name`; only one instance per plugin name is allowed)         |
| `version`             | Version (injected as `__plugin.version`)                                                       |
| `author`              | Author (injected as `__plugin.author`)                                                         |
| `api` / `java`        | API/Java version required by the host platform (may be ignored for other platforms)             |
| `permissions`         | Developer-declared permissions (sensitive nodes, see [Permission Model](#permission-model) below) |
| `computedPermissions` | Final effective permissions computed at build time (merged + wildcard normalization); read by the runtime (v0 phase is incompatible with legacy package formats) |
| `native`              | Native service trustworthiness declaration (SHA-256 computed at build time): `[{ "serviceId": "...", "files": [{ "<path-after-packaging>": "<sha256>" }, ...], "source": "..." }]` |

### `.yeow/main.js` — Plugin Code

An **IIFE** bundled by esbuild (`"use strict"; (() => {...})()`), with `bundle: true`, `target: esnext`. The runtime does not parse modules — it simply evaluates the entire file. When plugin code executes, it registers callbacks (`onInit`/`onLoad`/`onUnload`, `_registerCallback`, etc.) without performing game operations.

### `.yeow/dev.json` — Development Mode (Optional)

```json
{
    "name": "my-plugin",
    "codeFile": "/abs/path/to/dist/.dev/main.js",
    "assetsDir": "/abs/path/to/dist/.dev/.assets"
}
```

Only present in development builds. When the runtime detects dev.json, it should:
- Read plugin code from `codeFile` (absolute file system path) instead of `.yeow/main.js` inside the JAR
- Read `assets` resources from `assetsDir` (file system directory) instead of from inside the JAR

### `assets/` — Bundled Resources

At build time, each dependency (the main project and qualifying npm packages) is assigned a **unique namespace id** (8-character hex, not a content hash) for its `assets/` directory. Contents are copied **as-is** into `assets/<id>/` — **files are not renamed via hashing**, and relative references within `assets/` (including cross-directory) remain valid at all times.

The JS side obtains the namespace-prefixed path via `getAssetsPath()` (e.g., `"assets/a1b2c3d4/icon.png"`). The runtime looks up this path under the JAR's `assets/` directory and must **not** apply any additional transformation to the path.

---

## Loading Flow

The standard flow for a runtime loading a Yeow package:

```
1. Read yeow.json          → Plugin metadata (name/version/author) and permission declarations
2. Duplicate name check    → Plugin name already exists → Reject load and warn (same for all loading paths)
3. Read plugin code:
     Has .yeow/dev.json → Read from dev.json.codeFile (development mode)
     Otherwise          → Read from .yeow/main.js (production)
4. Create JS context (isolated, plugins are sandboxed from each other)
5. Inject global functions (see "JS Runtime Injections" below)
6. evaluate(bootstrap script)   → Define $send, _registerCallback, console, $hm, etc.
7. evaluate(plugin code)        → Register onInit/onLoad/onUnload, commands, events
8. Synchronously call $hm('{"t":"INIT"}')   → Trigger onInit callback (not queued, before all messages)
9. Start message loop
10. Deliver {"t":"LOAD"}        → Trigger onLoad callback (game operations are now available)
```

**Loading Sources** (all behave identically, following the above flow):

- Template JAR registration (host platform plugin mechanism, e.g., Paper's `depend`)
- Data directory auto-scan (`plugins/Yeow/*.yeow.zip`, at startup)
- Admin commands: `load <path|url>` (temporary), `install <url>` (download and save in standard format to data directory), `update <url>` (replace old package of the same name, old package moved to `.backup/`)

> **`.yeow.zip` takes priority**: Admin commands treat `.yeow.zip` as the primary target. When a template JAR and `.yeow.zip` with the same plugin name both exist, a conflict warning is generated (duplicate load is rejected), and one must be manually removed.

**Load Message**: When a plugin loads successfully, a load message is printed containing the plugin name, version, and **permission declarations**.

## Permission Model

Sensitive message nodes are **denied by default**; plugins must declare them in `computedPermissions` in `yeow.json`:

| Node (may be omitted)       | Covers message operations                                                                         |
| --------------------------- | ------------------------------------------------------------------------------------------------- |
| `fs:server.*`               | fs channel `server` prefix nodes (server root directory, e.g., `fs:server.readFile`); `fs:plugin.*` nodes (plugin data directory) **need no declaration, allowed by default** |
| `fs:outer.*`                | fs channel `outer` prefix nodes (arbitrary paths, e.g., `fs:outer.readFile`)                      |
| `http:*`                    | All http channel operations (`request`/`requestAsync`/`listen`/`respond`/`close`)                 |
| `service:registerNative`    | `registerNative` on the service channel (spawn subprocess)                                         |

> The `assets` channel has no permission interception: it only reads bundled resources or extracts to the plugin's own data directory (target is forcibly constrained).

Rules:

- **Node concept**: Permissions are only considered by **message node** (`channel:node`). Segments in the node name (e.g., `plugin` in `fs:plugin.readFile`, `player` in `task:player.get`) are business/access scope names, **not hierarchy**, and do not participate in permission matching
- **Node matching**: Exact node (`fs:server.readFile`); **group wildcard** `fs:server.*` matches all nodes under that prefix; **channel wildcard** `fs:*` matches all nodes in the fs channel — at build time, `fs:*` is **automatically expanded** in `computedPermissions` to `fs:outer.*, fs:server.*` (semantically equivalent)
- **Default allowed**: Nodes outside the above default-deny nodes (e.g., `service:request`, `service:register`, `assets:read`, `fs:plugin.readFile`) need no declaration
- **Denial behavior**: Undeclared calls return error `Permission denied: <node>`. Synchronous calls return error JSON directly; asynchronous calls (including those with `cb`) deliver `{"err":"Permission denied: <node>"}` via callback, manifesting as a Promise reject on the JS side
- **Other channels** (`task`/`timer`/`log`/`env`/`debug`/`lifecycle`) are not constrained by the permission model
- Permissions are read and **fixed** at plugin load time (cannot be changed at runtime); the load message prints the declared content — when printing, `fs:*` is **expanded to `fs:outer.*, fs:server.*`** (display only, to help server administrators understand the scope; permission checks still use the original value `fs:*`)

**`computedPermissions` semantics**: Plugin authors and dependency packages declare permissions in their respective `yeow.config.json`'s `permissions`; at build time, these are merged (deduplicated + wildcard normalization — `X:*` supersedes `X:...`, `X:segment.*` supersedes `X:segment.<op>`; `fs:*` expands to `fs:outer.*, fs:server.*`) and written to `computedPermissions` in `yeow.json`. The runtime reads this field. The runtime only verifies wildcard/node matching and does not need to understand the meaning of node name segments.

**Lifecycle Message Semantics**:

| Message   | Trigger Timing                    | JS-side Handling                                                         |
| --------- | --------------------------------- | ------------------------------------------------------------------------ |
| `INIT`    | After context ready, before message loop | Execute `__yeowInitCbs` (dispatcher not yet started, game operations not available synchronously) |
| `LOAD`    | After dispatcher ready            | Execute `__yeowLoadCbs` (game operations available synchronously)       |
| `DISABLE` | Plugin is disabled                | Execute `__yeowUnloadCbs` → After receiving `unloadDone`, close thread   |
| `RELOAD`  | Hot reload                        | Execute `__yeowUnloadCbs` → After receiving `unloadDone`, destroy old context, load new code |

---

## Runtime Architecture

A compliant runtime contains the following components:

```
┌────────────────────────────────────────────────────┐
│                    Runtime                          │
│                                                    │
│  ┌──────────────┐   ┌──────────────────────────┐  │
│  │ Plugin 1     │   │ Plugin 2                 │  │
│  │ JS Context   │   │ JS Context               │  │
│  │ + Msg Loop   │   │ + Msg Loop               │  │
│  └──────┬───────┘   └──────┬───────────────────┘  │
│         │                  │                      │
│  ┌──────┴──────────────────┴───────────────────┐  │
│  │ Dispatcher (optional but recommended)        │  │
│  │ Three-level queue HIGH/NORMAL/LOW + timeslice │  │
│  └──────┬──────────────────────────────────────┘  │
│         │                                         │
│  ┌──────┴──────────────────────────────────────┐  │
│  │ Executor (task type → host platform ops)     │  │
│  │ Event bridge (subscribe/trigger/complete)     │  │
│  │ Command bridge (register/execute/complete)    │  │
│  │ Channel impl (fs/http/assets/service/timer...)│  │
│  └─────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────┘
```

**Threading Model** (recommended):

| Thread               | Responsibility                                             |
| -------------------- | ---------------------------------------------------------- |
| Main thread          | Dispatcher tick (game tasks executed serially), event/command bridge |
| JS thread (per plugin) | Plugin code + message loop, directly handles non-game operations like fs/http/assets |
| Timer thread (per plugin) | setTimeout/setInterval expiration callback delivery    |
| IO thread            | Async fs/http operations                                   |

**Key Principle**: JS logic is separated from game operations — plugin code does not block the game main thread; game tasks are serialized through the dispatcher to avoid race conditions.

---

## JS Runtime Injections

Before evaluating plugin code, the runtime must inject the following into `globalThis` (see [Runtime Environment Standard](runtime/index.md) for details):

### Native Layer Injections (runtime's language host implementation)

| Global                | Signature                                                 | Description                                                                      |
| --------------------- | --------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `__plugin`            | `{ name, version, author }`                               | From yeow.json, read-only                                                         |
| `$dev`                | `boolean`                                                 | Development mode flag                                                             |

> The native layer only needs to ensure the bootstrap script can implement `$send` semantics; underlying bridge functions (like `$_send`) are **internal implementation details** and are not listed here.

### Bootstrap Script Layer (runtime's built-in JS bootstrap script)

The runtime must include a built-in bootstrap script (equivalent to `init.js`), executed **before** evaluating plugin code, defining:

| Global                                                          | Description                                         |
| --------------------------------------------------------------- | --------------------------------------------------- |
| `$send(channel, payload)`                                       | JS→runtime entry point, accepts **pure JS objects** (underlying serialization at implementor's discretion) |
| `_registerCallback(fn, opts?)` / `_unregisterCallback(id)`      | Callback registry (core async primitive, format `cb_N`) |
| `console.log/warn/error/info`                                   | Logging, automatically prefixed with `[plugin name]` |
| `setTimeout` / `setInterval` / `clearTimeout` / `clearInterval` | Timers (via `timer` channel)                        |
| `fetch(url, opts?)`                                             | HTTP client (via `http` channel)                    |
| `_getCurrentCbStack()`                                          | Development mode stack trace helper                 |
| `reportError(e)`                                                | Error reporting (via `debug` channel)               |

**Message Loop** (internal implementation, semantic requirements):

```
Loop:
  1. Pull next message from runtime (may block-wait — message-driven, no CPU usage when no messages)
  2. Call message dispatcher to process (callback / lifecycle)
  3. Drain microtask queue (Promise.then / FinalizationRegistry callbacks)
  4. If __yeowGcQueue is non-empty → Send lifecycle gc-collect
  5. Messages still in queue → Continue processing; no messages → Return to blocking wait
```

---

## Task Executor / Event Listener / Command Executor

The **complete contracts** for the three major execution components (request/response formats, execution rules, sync/async semantics, event completion, command completion) are documented in their respective dedicated articles:

- [Task Executor and task Channel](task/index.md) — Request format, priority, sync vs async, error format, per-item specifications for 224 task types
- [Event Listener](event/index.md) — Subscribe/trigger/complete, `eventId` matching, cancellable events, data fields for 41 events
- [Command Executor and Completion](task/command.md) — Register/execute/complete, sender adaptation (Player / CONSOLE), timeout strategy

## Native Service

Plugins can include native programs (Go/Rust/C++, etc.) and invoke them via the `service` channel. See [Native Service Specification](native-service/index.md) for details. Key points:

- Binaries are placed in `assets/` (namespace injected via `getAssetsPath()`)
- `registerNativeService` extracts and spawns a subprocess based on platform (os + arch)
- The subprocess communicates with the runtime via TCP JSON line (ready / request / response / publish)

---

## Compliant Runtime Checklist

Implementing a Yeow-compatible runtime requires handling:

- [ ] **Package structure parsing**: Read ZIP (yeow.json, .yeow/main.js, assets/; optional dev.json), JAR and `.yeow.zip` are structurally identical
- [ ] **Unique name enforcement**: Reject load and warn on plugin name conflict (same behavior for auto-scan / commands / host mechanism)
- [ ] **Permission model**: Parse yeow.json `computedPermissions`; `fs:server.*`, `fs:outer.*`, `http:*`, `service:registerNative` are denied by default (`fs:plugin.*` needs no declaration; `assets` channel has no permission interception, extraction target constrained to plugin data directory); undeclared calls return `Permission denied: <node>`
- [ ] **Load message**: Output load message on successful plugin load (includes plugin name, version, permission declarations)
- [ ] **JS engine**: ES2025+ (Sec-Uint8Array), supports `Promise`/`WeakRef`/`FinalizationRegistry`/`Uint8Array`
- [ ] **Native injections**: `__plugin`, `$dev` (underlying bridge like `$_send` is internal, not part of the spec)
- [ ] **Bootstrap script**: `$send`, `_registerCallback`/`_unregisterCallback`, `console`, timers, `fetch`, `$hm`
- [ ] **Message loop**: Pull message → `$hm` dispatch → Microtask → GC queue drain
- [ ] **Lifecycle**: INIT / LOAD / DISABLE / RELOAD, `unloadDone` acknowledgment
- [ ] **Callback system**: `cb_N` registry, persistent semantics, `{"t":"cb","p","r"}` delivery
- [ ] **Dispatcher** (recommended): Three-level priority, timeslice budget, optional auto-degrade/idle spin
- [ ] **Task executor**: `task` type → host platform operation, sync/async semantics
- [ ] **Event bridge**: subscribe/unsubscribe, event data extraction (basic types only), event.complete application
- [ ] **Command bridge**: register/execute/tabComplete
- [ ] **Channel implementations**: timer / fs / http / assets / service / log / env / dir / debug / lifecycle
- [ ] **Resource access**: Read JAR resources via `assets/<namespace-id>/`
- [ ] **Error handling**: JS exception capture, `debug` channel reporting
- [ ] **Health check** (recommended): `debug` ping-pong heartbeat, callback timeout warnings
- [ ] **Hot reload** (development): RELOAD → unloadDone → new context → new code; production unload uses the same logic (5s forced termination)
