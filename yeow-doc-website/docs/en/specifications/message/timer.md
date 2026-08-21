# Timer Channel

The underlying implementation of the timer mechanism. `setTimeout` / `setInterval` work through this channel.

## Call Format

```json
{ "type": "<type>", "cb": "<callbackId>", "delay": <ms> }
```

---

## Operations

### `timeout`

Delayed execution (underlying `setTimeout`).

- `delay`: milliseconds (**lower bound 0**)
- After it expires, a callback message is delivered to JS through `cb` (one-shot)

### `interval`

Repeated execution (underlying `setInterval`).

- `delay`: milliseconds (**lower bound 1** — `scheduleAtFixedRate`'s period must be >0)
- A callback message is delivered to JS through `cb` every `delay` milliseconds
- Until JS calls `clearInterval` to stop it (see below)

### `clear`

Cancels a timer task (underlying `clearTimeout` / `clearInterval`).

```json
{ "type": "clear", "cb": "<callbackId>" }
```

- `cb`: the callback ID registered by the cancelled timer (matches the one in the `timeout` / `interval` request)
- The runtime must cancel the corresponding Java timer task and release its registration — **locally deregistering the callback alone is not enough to stop `interval`'s periodic delivery** (it would create a permanently idle zombie task until the plugin is unloaded)
