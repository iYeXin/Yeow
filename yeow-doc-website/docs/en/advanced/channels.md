# Environment Capabilities and Channels

> Environment capability injection: `$send` wrapper (the underlying bridge `$_send` is an internal implementation, held in the init.js closure and not exposed globally), channel descriptions, runtime configuration (`plugins/Yeow/runtime/config.yml`).

## Environment Capability Injection

PluginThread registers a single underlying function in the JS context; **init.js holds it in a closure and immediately removes it from the global object** — plugin code can only use the wrapped `$send`:

| Function                      | Signature                            | Description                                        |
| ----------------------------- | ------------------------------------ | -------------------------------------------------- |
| `$_send(channel, jsonString)` | `(string, string) => string \| null` | The sole JS→Java communication entry (internal implementation, not exposed globally) |

Supported message channels:

| Channel     | Purpose                                            | Handling Location              |
| ----------- | -------------------------------------------------- | ------------------------------ |
| `task`      | Game tasks (requests/get blocks/teleport, etc.)    | Main thread scheduler          |
| `timer`     | Timers (setTimeout/setInterval)                    | Plugin thread Timer thread pool |
| `fs`        | File system read/write                             | Plugin thread handles directly |
| `http`      | HTTP server/client                                 | Plugin thread handles directly |
| `assets`    | Plugin built-in resource reading                   | Plugin thread handles directly |
| `service`   | Service registration/request/subscribe/publish     | ServiceManager                 |
| `debug`     | Error reporting / heartbeat ping-pong              | Plugin thread handles directly |
| `log`       | Console logging (auto-adds `[PluginName]` prefix) | Plugin thread handles directly |
| `env`       | Runtime environment info + microsecond timestamp   | Plugin thread handles directly |
| `lifecycle` | Lifecycle confirmation (unloadDone)                | Plugin thread handles directly |

### `$send` Wrapper Layer

init.js wraps `$_send` into `$send(channel, payload)`, automatically performing JSON conversion. **`$_send` only exists within the init.js closure** — plugin code (including dependency packages) that calls `$_send` directly will receive a `ReferenceError`:

```js
// $send handles JSON automatically (the only usable entry point for plugins)
$send('task', {type: 'player.get', params: {identifier: 'uuid'}});
```

### Channel Descriptions

**task channel** — Game tasks, routed through the main thread scheduler:

```json
{
    "type": "player.get",
    "params": {"identifier": "uuid"},
    "cb": "cb_1",         // Async callback ID (optional)
    "priority": "high"     // Priority (optional)
}
```

Synchronous tasks (no `cb`) block and wait for the result; asynchronous tasks (with `cb`) resolve via callback Promise.

**timer channel** — Replaces `$timeout`/`$interval`:

```json
{"type": "timeout", "cb": "cb_1", "delay": 1000}
{"type": "interval", "cb": "cb_2", "delay": 5000}
```

**log channel** — Console logging auto-adds the `[PluginName]` prefix:

```js
console.log('hello');     // → [MyPlugin] hello
console.warn('warning');  // → [MyPlugin] warning
```

**reportError channel** — Manually report errors to dev-server source-map resolution:

```js
import { logError } from 'yeow-api';
try { riskyOp(); } catch (e) { logError(e, 'custom context'); }
```

**lifecycle channel** — Lifecycle confirmation (used internally by plugins):

```
$send('lifecycle', {type: 'unloadDone'})    // Confirmation of disable or hot-reload completion
```

The JS side sends `$send('lifecycle')` to the Java side for confirmation after the `onUnload` callback has finished executing. Upon receiving the confirmation, the Java side sets `running = false`, and the message loop exits naturally.

### Wrapper Layer

init.js wraps `$_send` into a standard JS API:

```
$_send(channel, jsonString)   ← The only Java-native function (held in init.js closure, not exposed globally)
    ↓
init.js wrapper layer
    ├── $send(channel, object)   ← Automatic JSON conversion (the only usable entry point for plugins)
    ├── console.log/warn/error   ← Auto-adds [pluginName] prefix
    ├── setTimeout / clearTimeout
    ├── setInterval / clearInterval
    ├── fetch                    ← HTTP fetch (Promise)
    └── $hm                      ← Message dispatcher
```

## Runtime Configuration

For the complete reference of `plugins/Yeow/runtime/config.yml` (auto-generated on first startup), including the Folia section and warning configuration, see [Operations - Runtime Configuration](/operations#runtime-configuration); for warning thresholds and dynamic scaling, see [Runtime Warning Guide](/runtime-warning).
