# Debug Channel

Debugging, error reporting, and connection diagnostics.

## `reportError`

Manually report a JS error.

- **Request**: `{ "t": "reportError", "p": { "message": "<msg>", "stack": "<stack>", "fileName": "<f>", "lineNumber": <int>, "columnNumber": <int>, "context": "<text>" } }`
- **Return**: `null`

In development mode, the runtime forwards errors to the development server for source-map resolution.

## `pong`

Debug ping response. The runtime delivers `DEBUG ping` heartbeats to JS through the message queue, and the JS side responds with this operation to measure round-trip latency.

- **Request**: `{ "t": "pong" }`
- **Return**: `null`

## `payload`

Arbitrary payload echo (benchmarking / round-trip latency measurement). Handled directly by the plugin thread; **the content is returned as-is** (zero interpretation).

- **Request**: `{ "t": "payload", "p": <any JSON> }` (`p` may be an object / array / string / number / null; may contain `cb`)
- **Return**: `<original payload>` (synchronous echo); when `cb` is present, the original payload is echoed back asynchronously through the callback
- Also supported on Worker threads (the echo is handled locally within the Worker)

## `command` (runtime-internal test node)

A runtime-internal test entry point that the JS side may submit. This node is unavailable in production. The specific commands are runtime-internal implementation details and are not listed in this document. No stability guarantees are provided. No plugin should rely on its internal behavior.

- **Request**: `{ "t": "command", "p": { "cmd": "<command name>", ...command arguments } }`

## `DEBUG` Messages (runtime → JS)

The runtime delivers `DEBUG`-type messages to JS through the message queue. The JS-side `$hm` handles them when `t === 'DEBUG'`.

Currently supported:

- `{ "t": "DEBUG", "p": "ping" }` → JS should immediately respond with `$send('debug', { t: 'pong' })`

**Heartbeat detection**: The runtime periodically (by default one window per second) sends a `DEBUG ping` to each plugin's JS thread and records the pong round-trip time. A single round trip exceeding `latency-warn-threshold-ms` (default 200ms) triggers a `heartbeat.timeout` warning; no response at all for `suspend-warn-seconds` (default 30s) escalates to `plugin.hung` (thread hang). The thresholds are configured in the `profile` section of the runtime `config.yml`; see the [Runtime Warning Guide](../../runtime-warning.md) for details.
