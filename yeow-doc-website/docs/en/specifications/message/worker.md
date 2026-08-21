# Worker Channel

Worker (virtual plugin) channel — the main plugin JS side controls Worker creation/unloading/messaging/reloading via `$send('worker', ...)` (internally wrapped by yeow-api `worker.ts`).

> **Internal implementation**: Worker channel is a runtime internal capability (used with yeow-api's `createWorker`), not part of the plugin public permission model (not subject to permission checks).

## Message Format

```json
// Register Worker (only registers in registry and returns handle — does not start; worker.load() triggers startup)
{ "t": "create", "p": { "name": "<worker name>", "code": "<code>" | "entry": "<resource path>", "msgCb": "<main plugin side callback id>", "cb": "<callback id>" } }
// Load (register entity → init.js → worker-inject.js → Worker code → INIT → LOAD → ready)
{ "t": "load", "p": { "name": "<worker name>", "cb": "<callback id>" } }
// Unload (physically destroy JS context and clean up events/commands/services/tasks; handle preserved, can re-load)
{ "t": "unload", "p": { "name": "<worker name>", "cb": "<callback id>" } }
// Send message to Worker (errors if not loaded)
{ "t": "post", "p": { "name": "<worker name>", "msg": <any JSON>, "cb": "<callback id>" } }
// Reload (must be loaded)
{ "t": "reload", "p": { "name": "<worker name>", "code" | "entry", "cb": "<callback id>" } }
```

**Callback**: When `cb` is included, executes asynchronously; completion/failure is delivered via `{"t":"cb","p":"<cbId>","r":"true"|{"err":...}}` (`r` is a JSON string or object).

**Worker → Main plugin** (handled internally in the Worker thread, does not go through main plugin channel routing):

```json
{ "t": "postToMain", "p": { "msg": <any JSON> } }
```

Runtime delivers to the main plugin JS side that Worker's `onMessage` callback (`{"t":"cb","p":"<msgCb>","r":<msg>}`).

## Lifecycle

| Event | Behavior |
|-------|----------|
| `create` | Register only (construct handle, no startup); duplicate/illegal names report error |
| `load` | Register entity (plugins map + profiler) → construct execution unit (independent QuickJS context + thread) → `INIT` → `LOAD` → callback when ready; already loaded is a no-op |
| `unload` | Send `DISABLE` → wait for exit (5s forced) → clean up events/commands/services/tasks → unregister profiler — **handle preserved, can re-`load`** |
| `reload` | Send `RELOAD` → old context destroyed → new code reloaded (`INIT` + `LOAD`); must be loaded |
| Main plugin unload/hot-reload | **Cascade unload** all attached Workers (thorough cleanup, handles destroyed with it) |

## Errors

Worker JS errors are relayed via the `debug` channel (same as plugins), messages carry an `origin` field:

```json
{ "t": "reportError", "p": { "origin": "<worker name>", "message": "...", "stack": "...", "fileName": "...", "lineNumber": 1, "columnNumber": 1 } }
```

dev-server reverse-resolves the corresponding Worker's source-map by `origin` (`JS Error in Worker <name>`). Main plugin errors have `origin` as `"main"`.

## Constraints

- **Workers cannot create new Workers**: `$send('worker', ...)` inside a Worker only accepts `postToMain`; all others return `{"err":"workers cannot create workers"}`
- Workers **share data directory / permissions / resources** with the main plugin (fs plugin-level base, assets namespace are consistent)
- Worker registers as a plugin entity with `<main plugin>.<worker name>` (events/commands/services/scheduler are independent); `/yeow` management commands do not cover virtual plugins
