# Lifecycle Channel

Plugin lifecycle acknowledgement and resource reclamation notifications.

## `unloadDone`

Notifies the runtime that the plugin has finished unloading.

- **Request**: `{ "type": "unloadDone" }`
- **Return**: no return value

The plugin sends this message after all `__yeowUnloadCbs` callbacks have finished executing. Once received, the runtime should safely shut down the plugin thread.

---

## `gc-collect`

Notifies the runtime that a particular resource is no longer referenced by the JS side and can be released.

- **Request**: `{ "type": "gc-collect", "ids": ["a1b2c3d4e5_1", "f6a7b8c9d0_2"] }`
- **Return**: no return value

**How it works:**

1. The JS side pushes resource identifiers that are no longer in use into `__yeowGcQueue` via `FinalizationRegistry` (or an equivalent mechanism)
2. After each message is processed (once the microtask queue is drained), the runtime checks `__yeowGcQueue`
3. If there are ids pending reclamation, it sends a `gc-collect` message through the `lifecycle` channel
4. Upon receiving it, the implementation releases the corresponding resources

**Conventions:**

- Resource ids are **opaque handles** (e.g. `a1b2c3d4e5_1`) and **do not carry resource-kind information** — the runtime should not parse the id content, but should maintain an `id → release logic` registry mapping (registered when the resource is created, looked up to release at gc-collect time)
- The `ids` array may be empty (meaning no resources pending reclamation; may be skipped)
- The implementation **may** defer `gc-collect` until the next `$send` call instead of handling it immediately after every message
