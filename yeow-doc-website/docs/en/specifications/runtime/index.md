# Yeow Runtime Environment Standard

This specification describes the JavaScript runtime environment for Yeow plugin code. Any runtime that implements this specification can execute Yeow plugins, regardless of internal implementation details.

---

## Language Standard

Implementations must provide a **ES2025 + Sec-Uint8Array** (or higher) JavaScript runtime, including at minimum the following features:

- `Promise`, `async`/`await`, `Promise.all`, `Promise.race`
- `Symbol`, `Proxy`, `Reflect`
- `WeakRef`, `FinalizationRegistry` (for resource reclamation)
- `ArrayBuffer`, `Uint8Array` (binary data processing)
- `Uint8Array` section capabilities (Sec-Uint8Array): `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()` and other base64/hex encoding/decoding
- `JSON.parse` / `JSON.stringify`
- `Error`, `SyntaxError`, `TypeError`

> **Official implementation engine version**: The `yeow-runtime` for Paper family (Paper/Purpur/Leaf, etc.) uses **QuickJS 2026-06-04** ([iyexin/quickjs](https://github.com/iyexin/quickjs) fork, upstream [bellard/quickjs](https://bellard.org/quickjs/)), additionally providing: resizable `ArrayBuffer`, `ArrayBuffer.prototype.transfer`, `Iterator` objects and set methods, `Math.sumPrecise()`, regex duplicate named groups, and `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()` (see [https://tc39.es/ecma262/multipage/indexed-collections.html#sec-uint8array](https://tc39.es/ecma262/multipage/indexed-collections.html#sec-uint8array)).

---

## Callback System

`_registerCallback` is the fundamental primitive of Yeow's asynchronous communication model. All operations that require cross-thread asynchronous results (`task` channel, `timer` channel, `fs` channel, `assets` channel, `http` channel) are based on this callback mechanism.

### Registration and Unregistration

```
_registerCallback(fn, options?) → string    // Returns callback ID "cb_N"
_unregisterCallback(id)                      // Unregisters callback
```

**`persistent` option:**

| Value           | Behavior                                              | Typical Use Cases                                        |
| --------------- | ----------------------------------------------------- | -------------------------------------------------------- |
| `false` (default) | Callback is automatically unregistered after first invocation | `post()` Promise resolve, `fetch()` request callbacks   |
| `true`          | Callback can be invoked multiple times until explicitly `_unregisterCallback` | Event handlers (`eventOn`), timer loops (`setInterval`) |

### Callback ID Format

`"cb_N"`, where `N` is a globally incrementing integer. Each `_registerCallback` call generates a globally unique ID.

### cb Field: The Async Pathway Across All Channels

Any `$send(channel, payload)` call that includes a `cb` field in the `payload` (value being the callback ID) means: **"This operation is asynchronous, results are delivered via callback, `$send` immediately returns `null`"**.

```json
// Async request
{ "type": "player.get", "params": {...}, "cb": "cb_42" }
// $send immediately returns null, JS does not block
```

If the `payload` **does not contain** a `cb` field, it means: **"This operation is synchronous, `$send` blocks until completion and returns the result directly"**.

### Callback Delivery Protocol

When an asynchronous operation completes (or an event occurs, or a timer expires), the runtime delivers callback messages to JS through the message queue. Message format:

```json
// Current standard unified format
{ "t": "cb", "p": "<callbackId>", "r": <result> }
```

Additionally, the runtime interacts with plugins during load and unload through defined message types, not through the `cb` delivery protocol:

- `INIT` message → executes `__yeowInitCbs` (no callback)
- `LOAD` message → executes `__yeowLoadCbs` (no callback)
- `DISABLE` message → executes `__yeowUnloadCbs` (no callback)
- `RELOAD` message → executes `__yeowUnloadCbs` (no callback)

JS-side processing flow:

```
Runtime delivers { "t": "cb", "p": "cb_42", "r": ... }
  → Looks up _cbs["cb_42"]
  → Calls fn(result)
  → If !persistent, deletes _cbs["cb_42"]
```

### Async Flow Example

Using `post('player.get', { identifier })` as an example, showing how a call-dependent system operates together:

```
1. JS calls post()
2. post() internally registers callback → obtains "cb_42"
3. $send('task', { type:"player.get", params:{identifier}, cb:"cb_42" })
4. Runtime detects cb exists → submitGameAsync → immediately returns null
5. $send returns null → post() returns unresolved Promise
6. JS executes await or continues processing other code
7. ... next tick ...
8. Scheduler picks up task → executes player.get → callback λ → queue.sendJs(cbMessage)
9. JS-side message loop receives → $hm → _cbs["cb_42"].h(result) → Promise resolve
10. Microtask queue processing → await resumes execution
```

---

## Event Loop and Async Collaboration

The runtime uses an event loop model to handle asynchronous operations. Plugin code depends on the **immediate message → async callback → microtask → GC → message fetch** model functioning correctly.

### Loop Model

```
1. Wait for the next runtime message to be delivered to the JS environment
2. Call the global message dispatch function to process the message
3. Drain the microtask queue (queueMicrotask callbacks, Promise.then callbacks, FinalizationRegistry callbacks)
4. Drain the GC reclamation queue (__yeowGcQueue), send gc-collect message
5. Return to step 1
```

### Importance of Microtasks

Microtasks are the foundation for:

- `await` pause and resume (`await` itself is a microtask suspension for V8)
- `Promise.then` callbacks
- `FinalizationRegistry` GC callbacks (pushing no-longer-used resource IDs into `__yeowGcQueue`)

While the previous message is still being processed, callback functions may register new callbacks (e.g., a second `post()`, which essentially repeats this async invocation process). Microtasks ensure these dependent operations execute in the correct order within the current loop — before returning to wait for new messages.

### Timing Relationship Between Message Processing and Microtasks

Steps of a complete message loop:

```
[1] Pull new message from runtime
        → Call corresponding handler (may be event/async callback/timer)
        → Handler completes synchronously
[2] while (microtask queue not empty)
        → Promise.then / finalization
        → If new async task registered in then → submit message to runtime again
[3] If __yeowGcQueue not empty → send gc-collect
[4] Return to [1], continue pulling messages
```

**Note:** New async tasks registered in [2] may be processed by the runtime at any time — no need to wait for JS to actively pull. The key is that after [1] completes and [3] executes, new results are already ready in the runtime and will be pulled in the next loop.

---

## Per-Channel cb Semantics

The following specifies the behavior of each channel's support for the `cb` field:

| Channel     | Supports cb | Sync Behavior                                             | Async (with cb) Behavior                                       |
| ----------- | ----------- | ---------------------------------------------------------- | -------------------------------------------------------------- |
| `task`      | Yes         | `$send` blocks until scheduler completes task and returns   | `$send` immediately returns `null`, callback sent after scheduler completes |
| `timer`     | Yes         | —                                                          | Always async (`$send` immediately returns), callback sent on timer expiry |
| `fs`        | Yes         | `$send` blocks until IO completes                           | `$send` immediately returns `null`, callback sent after IO thread completes |
| `http`      | Yes         | Request completes synchronously                             | `requestAsync` includes `cb`, callback sent after HTTP thread processing completes |
| `assets`    | Yes         | `$send` blocks until IO completes                           | `$send` immediately returns `null`, callback sent after IO thread completes |
| `lifecycle` | No          | `$send` returns `null` (fire-and-forget)                    | —                                                              |
| `log`       | No          | `$send` returns `null` (fire-and-forget)                    | —                                                              |
| `env`       | No          | `$send` directly returns environment info JSON (synchronous) | —                                                              |
| `debug`     | No          | `$send` returns `null` (fire-and-forget)                    | —                                                              |
| `service`   | Yes         | Register/request/subscribe/publish. Request is async, register is sync | —                                                    |
| `util`      | Yes         | `$send` blocks until computation completes                   | `$send` immediately returns `null`, callback sent after IO thread completes |
| `worker`    | Yes         | create/load/unload/reload execute synchronously (`$_send` blocks) | When `cb` is present, executes asynchronously; completion/failure delivered via callback (`r` is JSON string or object) |

---

## Global Variables

The following global variables are injected by the runtime before plugin code execution; all variables are mounted on `globalThis`.

### `$send(channel, payload)`

```ts
(channel: string, payload: any) => any | null
```

The sole communication entry point between JS and the runtime (as specified). **`payload` is a plain JS object** — the underlying transport format (JSON or other) and the `$_send` bridge function are **implementation details** decided by the implementer; examples in this specification use JSON.

> **Implementation boundary**: In the runtime implementation, `$_send` is held by the init.js closure and **removed from the global object** — plugin code can only access `$send`; directly referencing `$_send` throws a `ReferenceError`.

- `channel`: A string specifying the operation category. See [Message Channels](#message-channels-overview) for available values.
- `payload`: A plain JS object; the runtime is responsible for serialization and transport.

Behavior:
- For `task` channel calls: if `payload` contains `cb` (callback ID), the runtime must **immediately return `null`**, not blocking JS execution. Task results are returned asynchronously via the callback channel.
- For `task` channel calls: if `payload` does not contain `cb`, the runtime must **block JS execution** until the task completes and synchronously return the result.
- For `fs` and `assets` channels: executes asynchronously when `cb` is present, synchronously when absent.
- For `timer` channel: always asynchronous.
- For `http` channel: `requestAsync` with `cb` executes asynchronously, `request` is synchronous.

Task execution order:
- `task` channel messages are scheduled in **HIGH → NORMAL → LOW** priority order
- All `task` channel operations execute serially on a single thread; concurrency is not permitted

### `$dev`

```ts
boolean
```

Whether in development mode, controlled by runtime startup parameters (default `false`).

Effects:
- When `true`, `_registerCallback` captures the call stack at callback registration time and attaches it to exception info on error
- When `true`, the runtime sends JavaScript error information externally (e.g., source-map parsing)

### `_registerCallback(fn, options?)`

```ts
(fn: (result: any) => any, options?: { persistent?: boolean }) => string
```

Registers a callback function, returning a unique callback ID in the format `"cb_N"` (`N` is an auto-incrementing integer, **with a random base per context** — after hot-reload/reload creates a new context, callback IDs will not overlap with previous generations).

> **Cross-generation uniqueness (why it's required)**: The runtime message queue is shared during reload / exists in a race window — old-generation messages (old timer deliveries, in-flight event dispatches, late-arriving async results) may fall into the new context. If new and old generation callback IDs overlap (same sequence number, different purpose), old messages would incorrectly invoke new callbacks (e.g., event handler receiving other events/timer payloads). **Callback IDs must be globally unique (at least cross-generation unique)**, so old-generation messages are silently discarded when no matching callback is found in the new context.

This is the core primitive of Yeow's async model. Any operation that needs to asynchronously obtain results registers a callback through this function, then passes the callback ID as the `cb` field in `$send`'s payload.

**Parameters:**

- `fn` — Callback function. The input parameter is the `r` field delivered by the runtime (any JSON-serializable value representing the result). If `r` contains an `err` field, the operation failed.
- `options.persistent` (default `false`):
  - `false` — Callback is automatically unregistered after first invocation. Suitable for one-shot operations like `post()`, `fetch()`, `setTimeout`.
  - `true` — Callback can be invoked multiple times until explicitly `_unregisterCallback`. Suitable for persistent operations like event handlers, `setInterval`, Tab completers.

**Stack trace ($dev mode):**

When `$dev` is `true`, the call stack (`new Error().stack`) is captured at registration time. When an exception is thrown during callback execution, this stack information is attached to the exception's `stack` in the format `"    --- cb registered at ---\n"` + original stack. This helps developers locate the async callback registration site in source maps.

**Promise auto-unwrapping:**

If the callback function returns a Promise (i.e., `result.then` is a function), the callback wrapper automatically attaches `.then(null, ex => ...)` error handling, converting unhandled Promise rejections into enhanced stack information.

### `_unregisterCallback(id)`

```ts
(id: string) => void
```

Unregisters a previously registered callback. After invocation, the corresponding `cb_*` ID becomes invalid, and subsequent messages delivered to that ID will be ignored.

### `fetch(url, init?)`

```ts
(url: string, init?: {
  method?: string;
  headers?: Record<string, string>;
  body?: string | Uint8Array | null;  // Same semantics as fs.writeFile: Uint8Array for direct binary; string interpreted by encoding
  encoding?: 'utf8' | 'base64';        // When body is a string: 'utf8' (default) for text / 'base64' for base64 binary
  timeout?: number;                    // Timeout in milliseconds (connection and read; defaults to runtime 5s/10s)
}) => Promise<Response>
```

HTTP client, behavior conforms to the WHATWG Fetch standard as closely as possible.

**Internal implementation dependency:** `_registerCallback` + `$send('http', {t:'requestAsync', p:{url,method,headers,body,responseType:'base64',timeout?,cb}})`. When the callback triggers, it resolves to a `Response` object.

**Response body caches raw bytes as base64** (`responseType: 'base64'`), decoding is triggered on demand:

```ts
interface Response {
  ok: boolean;           // status >= 200 && status < 300
  status: number;        // HTTP status code
  statusText: string;    // "OK" or "Error"
  headers: { get(name: string): string | undefined };
  base64(): Promise<string>;              // Raw base64 (zero decoding)
  bytes(): Promise<Uint8Array>;           // Raw bytes (Uint8Array)
  arrayBuffer(): Promise<ArrayBuffer>;    // Raw bytes (standard ArrayBuffer)
  text(): Promise<string>;                // UTF-8 decoded via TextDecoder (small payloads direct JS conversion, above threshold via util channel)
  json(): Promise<any>;                   // JSON.parse after TextDecoder decoding
}
```

**Note:** Implementations are not required to support `ReadableStream`, `blob()`, or other advanced features.

### `TextEncoder` / `TextDecoder`

**Implementations must provide** (Web API semantics, utf-8):

```ts
new TextEncoder().encode(str: string): Uint8Array
new TextDecoder('utf-8').decode(bytes: Uint8Array): string
```

- Currently only `utf-8` is supported; other encodings throw `RangeError`.
- `TextDecoder` replaces invalid UTF-8 sequences with `U+FFFD`.
- **Internal implementation is not specified**: Whether to use underlying channels, thresholds, etc., is at the implementer's discretion (current implementation: ≤100 bytes and ≤50 characters uses pure JS conversion, above threshold goes through `util` channel `encode.utf8` / `decode.utf8` — this strategy is not a specification constraint).

> [!WARNING]
> **`fetch` depends on `http:requestAsync` permission**: The `fetch` implementation is based on the http channel's `requestAsync` (`$send('http', {t:'requestAsync', ...})`). When a plugin does not declare `http:*` (or `http:requestAsync`) permission, the runtime must return `Permission denied: http:requestAsync` (via callback delivery), and `fetch`'s Promise rejects. See [Channel Permissions](#channel-permissions-sensitive-nodes-default-deny).

### `setTimeout(fn, ms)` / `clearTimeout(id)` / `setInterval(fn, ms)` / `clearInterval(id)`

```ts
setTimeout(fn: () => void, ms: number): string
clearTimeout(id: string): void
setInterval(fn: () => void, ms: number): string
clearInterval(id: string): void
```

Timer functions, behavior conforms to Web standards.

- Returns a unique identifier (string) for use with `clearTimeout`/`clearInterval`
- `ms` precision is milliseconds; delays less than 1ms are not supported (`setInterval(fn, 0)` is treated as 1ms)
- **Internal implementation dependency:** `_registerCallback` + `$send('timer', {type:'timeout'|'interval'|'clear', cb, delay})`
- `setInterval` callbacks are registered with `persistent: true`; `clearTimeout`/`clearInterval` cancel the Java-side timer task via `_unregisterCallback` + `$send('timer', {type:'clear', cb})` (unregistering locally only would cause the interval periodic task to run indefinitely)
- Timer callback delivery format: `{ "t": "cb", "p": "<id>", "r": true }` (empty result, callback argument is ignored by JS timer wrapper)

### `console`

```ts
console.log(...args: any[]): void
console.warn(...args: any[]): void
console.error(...args: any[]): void
console.info(...args: any[]): void
```

Log output. The runtime-provided `console` should automatically add a prefix.

### `_getCurrentCbStack()`

```ts
() => string | null
```

Only effective when `$dev` is `true`. Returns the call chain node object of the currently executing callback (internal structure, includes the call stack at registration time; used by `_attachCbStack` / `_attachNode`).

**Purpose:** When an async operation fails (e.g., `post()` Promise rejects), the error point is usually not on the line where the callback was registered. The stack information returned by this function is used to enhance exception stacks, helping developers trace the original call location.

### `__plugin`

```ts
{
  name: string;       // Plugin name (from yeow.json)
  version: string;    // Version (from yeow.json)
  author: string;     // Author (from yeow.json)
}
```

Metadata for the current plugin. Read by the runtime from the `yeow.json` file in the plugin package; **read-only**.

### `__yeowInitCbs`

```ts
(() => void)[]
```

`onInit` callback queue. The runtime triggers iteration after plugin code execution completes and before the message loop starts, via the `INIT` message.

At this point the scheduler has not yet started, so you **cannot** call `call()` to execute synchronous game operations. You can execute `console.log`, `fetch`, `fs.*` and other non-scheduler operations.

### `__yeowLoadCbs`

```ts
(() => void)[]
```

`onLoad` callback queue. The runtime triggers iteration after the scheduler is ready (i.e., game state can be safely accessed), via the `LOAD` message.

At this point it is safe to call all `call()` synchronous game operations.

### `__yeowUnloadCbs`

```ts
(() => void)[]
```

`onUnload` callback queue. The runtime triggers iteration when a plugin is disabled or hot-reloaded, via the `DISABLE` or `RELOAD` message.

After all `onUnload` callbacks complete execution, the runtime receives the `unloadDone` message from the `lifecycle` channel and then closes the plugin thread.

### `__yeowEventHandlers`

```ts
Record<string, Array<{ cbId: string; handler: Function; manualRelease?: boolean }>>
```

Event handler registry. **Maintained by yeow-api `event.ts` (not runtime-injected; lazily created on the JS side when `eventOn` is first called)**. Application code registers event handlers via `_registerCallback` (persistent: true) and submits the callback ID to the runtime via the `event.subscribe` task. The runtime does not directly operate this table when delivering events.

Each entry stores the callback ID (used for sending `event.complete` when an event completes), the handler function reference (used for reference comparison in `eventOff`), and whether manual complete mode is enabled.

### `__yeowGcQueue`

```ts
string[]
```

Resource reclamation queue.

- Application-layer code (e.g., `InstanceId`) pushes no-longer-referenced resource identifiers into this queue
- The runtime **must drain this queue after every message loop iteration**, sending a `lifecycle` channel `gc-collect` message for each identifier in the queue
- The runtime should use `FinalizationRegistry` or equivalent mechanisms to drive the population of this queue
- `FinalizationRegistry` callbacks execute during the microtask phase, right at step 3 of the event loop

> [!NOTE]
> Base64 encoding/decoding uses the engine's native **ES2026 `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()`**:

> ```js
> const b64 = new Uint8Array([1, 2, 3]).toBase64(); // "AQID"
> const bytes = Uint8Array.fromBase64(b64);         // Uint8Array(3) [1, 2, 3]
> ```

---

## Message Channels Overview

The first parameter of `$send`, `channel`, determines how messages are routed. Supported channels are as follows:

| Channel     | Purpose                 | Dispatch Method                    | Supports cb | Specification Document                          |
| ----------- | ----------------------- | ---------------------------------- | ----------- | ----------------------------------------------- |
| `task`      | Game tasks              | Scheduler queue, executed per tick | Yes         | [task module spec](../task/index.md)            |
| `timer`     | Timers                  | Independent timer thread           | Yes         | [timer channel](../message/timer.md)            |
| `fs`        | File system             | Direct processing. Uses IO thread for async | Yes | [fs channel](../message/fs.md)                  |
| `http`      | HTTP client/server      | Direct processing / HTTP thread    | Yes         | [http channel](../message/http.md)              |
| `assets`    | Built-in resources      | Direct processing. Uses IO thread for async | Yes | [assets channel](../message/assets.md)          |
| `lifecycle` | Lifecycle confirmation / Resource reclamation | Direct processing | No | [lifecycle channel](../message/lifecycle.md)    |
| `log`       | Logging                 | Direct processing                  | No          | [log channel](../message/log.md)                |
| `env`       | Runtime environment info + Microsecond timestamp | Direct processing (sync) | No | —                                   |
| `debug`     | Debug / Error reporting / Ping | Direct processing             | No          | —                                               |
| `service`   | Service register/request/subscribe/publish | Direct processing / Cross-thread routing | Yes | [service channel](../message/service.md) |
| `util`      | gzip + UTF-8 ↔ byte conversion | Direct processing. Uses IO thread for async | Yes | [util channel](../message/util.md) |
| `worker`    | Virtual plugin (Worker) control/message | Direct processing (internal channel, not governed by permission model) | Yes | [worker channel](../message/worker.md) |

**Channel dispatch principles:**
- `task` channel messages enter the scheduler and are executed per tick according to priority and time budget. All plugins' tasks are scheduled uniformly.
- `timer` channel messages enter an independent timer thread, with callbacks delivered on expiry.
- `fs` and `assets` channel messages execute in the IO thread pool when `cb` is present, and synchronously when absent.
- `http` channel `requestAsync` executes HTTP requests in the IO thread pool.
- All other channel messages are processed directly by the plugin thread without involving additional threads.

### Channel Permissions (Sensitive Nodes Default Deny)

The runtime **must** enforce permission checks on the following channels based on the plugin's `yeow.json` `computedPermissions` declarations (granularity = message node):

| Default Denied Nodes              | Covered Operations                                                                              | Declaration Example                                    |
| --------------------------------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| `fs:server.*` / `fs:outer.*`     | fs channel `server` / `outer` prefix nodes (server root / arbitrary paths); `fs:plugin.*` nodes (plugin data directory) are exempt | `["fs:server.*"]` or `["fs:server.readFile"]` |
| `http:*`                          | All http operations (including `requestAsync` used by `fetch`)                                  | `["http:*"]` or `["http:requestAsync"]`                |
| `service:registerNative`          | Registering native services (spawning subprocesses)                                             | `["service:registerNative"]`                           |

> The `assets` channel has no permission interception: it reads only packaged resources or extracts to the plugin's own data directory (extraction target is forcibly limited to `plugins/<pluginName>/`; out-of-bounds returns an error).

Rules:

- **Node concept**: Permissions are only considered by **message node** (`channel:node`); segments in the node name (e.g., `plugin` in `fs:plugin.readFile`) are business/access scope naming, **not hierarchy**, and do not participate in matching
- **Wildcards**: Declaring `channel:*` matches all nodes in that channel; **group wildcard** `channel:segment.*` matches all nodes with that prefix; **node-level**: declaring `channel:segment.op` matches only that specific operation
- **Default allow**: Nodes not in the above default-deny list (`service:request`, `service:register`, `assets:read`, `assets:readBase64`, `fs:plugin.readFile`, etc.) require no declaration
- **Deny behavior**: Undeclared calls return `Permission denied: <channel>:<op>` — synchronous calls return error JSON; async calls with `cb` deliver `{"err": "Permission denied: <channel>:<op>"}` via callback (JS-side Promise rejects)
- `task` / `timer` / `log` / `env` / `debug` / `lifecycle` channels are not restricted

### `env` Channel

Synchronously returns runtime environment information (JSON, `$send('env', {})`):

```json
{
  "cpus": 16,
  "memory": 17179869184,
  "arch": "windows-x64",
  "minecraftVersion": "1.21.4",
  "yeow": { "platform": "paper", "version": "0.5.0" },
  "now": 1723100000000000,
  "pluginDir": "plugins/my-plugin"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `cpus` | number | CPU logical core count |
| `memory` | number | JVM total memory (bytes) |
| `arch` | string | System architecture (`<os>-<arch>`, e.g., `windows-x64` / `linux-x64` / `linux-arm64`) |
| `minecraftVersion` | string | Minecraft version (e.g., `1.21.4`) |
| `yeow` | object | Runtime information: `{ "platform": "paper", "version": "<runtime version>" }` |
| `now` | number | **Epoch microsecond** timestamp (communication overhead is at the microsecond level; nanoseconds are meaningless) |
| `pluginDir` | string | Plugin data directory path (e.g., `plugins/<pluginName>`; merged from former `dir` channel; in Worker, refers to the main plugin directory) |