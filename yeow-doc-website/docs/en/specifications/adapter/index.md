# Plugin Adapter Specification (Adapter)

Yeow's **standard development language is JavaScript** (official JS adapter). Other languages are supported through **community adapters** — adapters are **platform-specific**: when providing an adapter for a host platform (e.g., NeoForge server), you must adapt to its mod/plugin structure, but the adapter workload is manageable: on the Java platform, you only need to implement the `PluginEntity` interface and register.

Yeow v1's multi-language support is not yet mature; we recommend using JavaScript/TypeScript. When using other languages, developer experience, user experience, performance, resource usage, and plugin security model reliability depend heavily on the language's characteristics, the adapter author's design, and the adapter's implementation quality. Yeow v1 makes no guarantees about the availability of other development approaches.

Below is the plugin adapter specification for Yeow on Paper-series platforms. If other platforms also implement the Yeow runtime, adapter authors should reference their specifications.

Typical adapter forms:

- **Yeow-Python** (Java Paper plugin with built-in CPython dynamic library): designs its own Python plugin package structure, reads plugin packages, wraps a Python adapter, then registers
- **TCP adapter**: maps plugin entities to remote processes or network services
- **WASM adapter** (Java plugin with built-in WASM runtime, e.g., wasmtime): plugins are packaged as WebAssembly modules (`.wasm`), the adapter handles module loading, host API bridging (`postMessage` → imported function calls, `submitTask` result delivery) and `ping()` probing. Some typical characteristics:
  - **Cross-platform**: WASM modules compile once and run on any WASM-supporting host, with no platform or language binding, supporting any language that can compile to WASM
  - **High performance**: Near-native execution speed (AOT/JIT compilation), no interpreter overhead
  - **Strong sandbox control**: WASM linear memory and import/export boundaries are naturally isolated; plugins cannot escape host-granted capabilities (no filesystem/network access unless explicitly imported), clear security model
  - **Lightweight resource usage**: Configurable memory limits (linear memory + stack), no full VM/interpreter resident overhead; a single server can host many WASM plugin instances
  - **Some disadvantages**: Higher development barrier for WASM plugins; ecosystem development may be limited in the short term; most game plugin code is glue code with modest performance requirements, so WASM's advantage over JS is marginal; poor design causing communication overhead (e.g., frequent serialization/deserialization) may dilute WASM's performance; development toolchain requires deep adaptation, otherwise developer experience is limited

## Plugin Entity Interface (PluginEntity)

The runtime treats each plugin as a `yeow.PluginEntity`. Adapters implement the following methods:

| Method | Contract |
| ------ | -------- |
| `name()` | Plugin name, **globally unique** (duplicate registration is rejected) |
| `source()` | Plugin package origin (path / virtual identifier; nullable) |
| `type()` | Plugin type tag (e.g., `"python"`, `"tcp"`); official JS is `"js"` |
| `isVirtual()` | Virtual plugin flag (Worker and other non-package entities) — used to distinguish in performance metrics/alerts |
| `postMessage(message)` | Receives messages delivered by the runtime — **JSON string or POJO**; the adapter decides whether to serialize (Strings are consumed per the message contract; POJOs can be serialized to their own format or processed field-by-field) |
| `ping()` | Heartbeat: initiates a probe, returns a round-trip nanosecond future; **returns null when an in-flight ping exists** (does not re-initiate) |
| `start()` | Starts the execution unit (called by runtime after registration) |
| `stopAndWait()` | Stops and waits for exit (forced termination after timeout) — **adapter is responsible for logical unloading** |
| `reload(code)` | Reload (implementations where not applicable may ignore) |

### Message Contract (postMessage JSON)

| Message | Semantics |
| ------- | --------- |
| `{"t":"LOAD"}` | Lifecycle: delivered after registration completes; adapter should start plugin logic here |
| `{"t":"DISABLE"}` / `{"t":"RELOAD"}` | Lifecycle: delivered before stop / reload |
| `{"t":"cb","p":"<cbId>","r":<data>,"eventId":"<eventId>"}` | Callback: event data / command execution / command completion / async result. `eventId` is a **unique-per-event id** generated at event dispatch; `r` also carries `_eventId` — when the JS side completes an event (`event.complete`), it should **pass back** `eventId` unchanged; the runtime uses it to precisely match the current wait (prevents pend overwrite when the same `cbId` triggers multiple concurrent events) |
| `{"t":"DEBUG","p":"ping"}` | Heartbeat probe: implementation should report pong (via the adapter's own channel, completing the `ping()` future) |

Completion reports (event `event.complete`, completion `command.tabComplete`, async results) are relayed to the runtime via the task channel (SyncCallbackHelper contract). Adapters may define their own internal message formats as long as they satisfy the interface semantics.

## Submitting Game Tasks

Adapters submit game tasks via the runtime API (**the only shared message interface**, equivalent to JS plugins' `$_send('task', ...)`):

```java
String result = YeowRuntime.inst().submitTask(entity, json);   // JSON string
JsonObject msg = new JsonObject(); /* ... */ 
String r2 = YeowRuntime.inst().submitTask(entity, msg);        // POJO used directly (zero serialization)
```

- `json`: `{"type":"player.get","params":{...},"cb":"<id>","priority":"high"}`
- With `cb` → asynchronous (returns null immediately), result delivered via `postMessage` callback `{"t":"cb","p":"<cbId>","r":<data>}`; `cbId` is generated and managed by the adapter
- Without `cb` → synchronous blocking, returns result JSON
- Both `postMessage` and `submitTask` accept **JSON strings or POJOs**: POJOs are **used directly** (avoiding serialization overhead) — gson `JsonObject` executes with zero conversion; ordinary POJOs are converted once by the runtime

**Other channels and the permission model are JS plugin-specific** (`service` / `fs` / `http` / `timer` / `log` / `debug` / `lifecycle` and declarative permissions) — adapters handle these according to their own situation: for example, CPython comes with a vast standard library and doesn't need the runtime to provide log / fs etc., but at the same time, the security model depends more on the adapter author's design.

## Registration API

After constructing the entity, the adapter calls (**synchronous, idempotent**):

```java
YeowRuntime.inst().registerPluginEntity(entity);   // Register + start + deliver LOAD
```

The runtime handles: duplicate name checking, registry maintenance, profile metrics integration (heartbeat/task/event collection), and service/event/task cleanup on unload (`/yeow unload` etc. uses the same `stopAndWait`).

### Example (Pseudocode)

```java
public class YeowPython extends JavaPlugin {
    @Override public void onEnable() {
        for (var pkg : listPythonPackages()) {
            var entity = new PythonPluginEntity(name, source, script);
            YeowRuntime.inst().registerPluginEntity(entity);
        }
    }
}

class PythonPluginEntity implements PluginEntity {
    // name/source/type("python")/isVirtual(false)
    // postMessage: delivers to CPython interpreter thread message queue (parses {"t":...} and dispatches)
    // ping: signals interpreter thread and waits for round-trip; returns null when in-flight
    // stopAndWait: stops interpreter, executes Python-side unload hooks, waits for exit
}
```

## Unload and /yeow Management Commands

- **Logical unloading is implemented by the adapter**: `stopAndWait()` / `reload()` must satisfy the interface semantics (wait for exit, forced termination on timeout)
- `/yeow` management commands currently **are not aware of adapter plugins** — adapter plugins (e.g., Yeow-Python itself) manage their own plugins' unload/reload commands
- The unique-name constraint applies uniformly to all entities (including entities registered by adapter plugins)

## Dependencies and Access

Adapter plugins declare `depend: [Yeow]` in `plugin.yml` and access the registration API via `YeowRuntime.inst()` (the runtime is a Paper-series plugin instance, also obtainable via `Bukkit.getPluginManager().getPlugin("Yeow")`).

## Checklist (Qualified Adapter)

- [ ] Implements all `PluginEntity` methods; `type()` returns a meaningful tag
- [ ] `postMessage` is thread-safe and non-blocking; correctly dispatches LOAD/DISABLE/RELOAD lifecycle
- [ ] `ping()` correctly manages in-flight (returns null semantics); future stays pending when no response
- [ ] `stopAndWait()` completes logical unloading and forces termination after timeout
- [ ] Registers via `YeowRuntime.inst().registerPluginEntity(entity)`; handles name conflicts gracefully
- [ ] Platform-specific package structure / engine wrapping is designed by the adapter (not governed by this specification)
