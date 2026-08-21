# FS Channel

File system operations.

> **Permissions**: The fs channel's nodes are distinguished by **access-scope naming segments** (`plugin` / `server` / `outer`). `plugin` nodes (plugin data directory) are allowed by default; `server` / `outer` nodes are denied by default, and the plugin must declare `fs:server.*` / `fs:outer.*` (the whole group) or specific nodes (e.g. `fs:server.readFile`) in the `computedPermissions` of `yeow.json`. `fs:*` wildcards the entire fs channel. An undeclared call returns `Permission denied: fs:server.readFile`.

## Concept: Message Nodes

The `t` field is a **message node**, formatted as `segment.operation`:

- `plugin.readFile`, `server.readFile`, `outer.readFile` are three **distinct nodes**
- The naming segments (`plugin` / `server` / `outer`) are **business/access-scope names**, of the same nature as `player` in `task:player.get`; permissions are only considered by the full node (e.g. `fs:plugin.readFile`)
- The runtime does not understand the meaning of the naming segments; it only performs node-level wildcard matching (see below)

## Call Format

```json
{ "t": "<segment>.<operation>", "p": { "path": "<path>", "data": "<data>" }, "cb": "<callbackId>" }
```

| Node prefix             | Path base                          | Permission                            |
| ----------------------- | ---------------------------------- | ------------------------------------- |
| `plugin.readFile` etc.  | `plugins/<pluginName>/`            | No declaration needed (allowed by default) |
| `server.readFile` etc.  | Server root (Java process working directory) | Requires `fs:server.*` or `fs:server.<op>` |
| `outer.readFile` etc.   | Any path (relative paths based on server root) | Requires `fs:outer.*` or `fs:outer.<op>` |

`path` is a path relative to the corresponding base directory; `server` nodes prevent escaping the server root, while `outer` nodes have no scope restriction.

### Async Mode

If the payload contains a `cb` field (callback ID), the operation executes **asynchronously**: `$send` immediately returns `null`, the operation runs on a separate IO thread, and the result is delivered through the `cb` channel when complete.

```json
// async request
{ "t": "plugin.readFile", "p": { "path": "config.json" }, "cb": "cb_42" }
// immediately returns null
// ... executes on the IO thread ...
// result delivered via cb: { "t": "cb", "p": "cb_42", "r": {"data": "..."} }
```

If there is **no** `cb` field, the operation executes **synchronously**, and `$send` blocks until the operation completes and returns the result directly.

---

## Operations List

The following operation names are available under all three node prefixes `plugin` / `server` / `outer` (e.g. `plugin.readFile`, `server.readFile`, `outer.readFile`).

### `readFile`

- **p**: `{ "path": "<path>" }`
- **Return**: `{ "data": "<content>" }`

### `writeFile`

- **p**: `{ "path": "<path>", "data": "<content>" }`
- **Return**: `"true"` (string)

Overwrite write.

### `appendFile`

- **p**: `{ "path": "<path>", "data": "<content>" }`
- **Return**: `"true"` (string)

Append write; the file is automatically created if it does not exist.

### `exists`

- **p**: `{ "path": "<path>" }`
- **Return**: `"true"` | `"false"` (string)

### `isDirectory`

- **p**: `{ "path": "<path>" }`
- **Return**: `"true"` | `"false"` (string)

### `delete`

- **p**: `{ "path": "<path>" }`
- **Return**: `"true"` | `"false"` (string)

Deletes a file or directory (recursive delete).

### `mkdir`

- **p**: `{ "path": "<path>" }`
- **Return**: `"true"` (string)

Recursively creates directories.

### `stat`

- **p**: `{ "path": "<path>" }`
- **Return**: `{ "isFile": <bool>, "isDirectory": <bool>, "size": <int>, "mtimeMs": <int>, "ctimeMs": <int> }`
- Path does not exist → `{ "err": "not found: <path>" }`

### `list`

- **p**: `{ "path": "<path>" }`
- **Return**: `["<name1>", "<name2>", ...]`

Lists directory contents (**entry names**, without path prefix).

### `readBase64` / `writeBase64` / `appendBase64`

- **p**: `{ "path": "<path>", "data": "<base64>" }` (write/append require data)
- **Return**: `{ "data": "<base64>" }` (read) / `"true"` (write/append)

Base64-encoded binary read, write, and append. `appendBase64` is the same as `appendFile`, opening the file with `CREATE + APPEND`.

### `systemPaths` (only the `outer` prefix)

- **p**: no arguments needed
- **Return**: `{ "home": "<user home directory>", "desktop": "<desktop path>", "temp": "<system temp directory>" }`

Gets common system paths (JVM properties, no IO). `desktop` = `<home>/Desktop` (may not exist), `temp` = `java.io.tmpdir`. This node is only available under the `outer` prefix (`outer.systemPaths`); calling it under other prefixes returns an error.

### `getServerPath` (only the `outer` prefix)

- **p**: no arguments needed
- **Return**: `{ "path": "<absolute server root path>" }`

Returns the absolute path of the server root (Java process working directory) — `server`-prefix nodes and relative paths such as assets extraction are all based on it. Only available under the `outer` prefix (`outer.getServerPath`), requiring `fs:outer.*` or `fs:outer.getServerPath` permission.

## Streaming Read/Write Operations

Stateful handles: `openRead/openWrite → read/write ×n → end/close`. **Backpressure = explicit response** — the caller waits for each operation to return before issuing the next chunk. The runtime buffers 256 KiB (to reduce syscall overhead of cross-thread round trips); the single-chunk size is controlled by the caller (`read`'s `maxBytes` defaults to 1 MiB, capped at 1 MiB). Handles are managed per-plugin and are automatically closed on plugin unload / hot reload.

| Operation   | Request                  | Return                          | Description |
|-------------|--------------------------|---------------------------------|-------------|
| `openRead`  | `{ path, start?, end? }` | `{ "id", "size" }`              | Opens a read handle (`size` = file size in bytes); non-file → err. `start`/`end` are a byte-offset range (start inclusive, end inclusive; omitted = whole file; `end < start` → err) |
| `read`      | `{ id, maxBytes? }`      | `{ "data": <b64> }` or `{ "eof": true }` | Reads one chunk; EOF returns `{eof: true}` |
| `openWrite` | `{ path, flags? }`       | `{ "id" }`                      | Opens a write handle (parent directories auto-created). `flags`: `w` overwrite (default) / `a` append / `wx` exclusive create (exists → err); unknown flags → err |
| `write`     | `{ id, data: <b64> }`    | `"true"`                        | Writes one chunk (returns once buffered) |
| `end`       | `{ id }`                 | `"true"`                        | Flushes the buffer and closes the write handle |
| `close`     | `{ id }`                 | `"true"`                        | Closes any handle |

Unknown/closed handle → `{ "err": "unknown fr/fw handle: ..." }`.

---

## Path Safety

The implementation **must** intercept any request attempting to escape the base directory (containing `../` or an absolute path starting with `/`) — the `plugin`-level base is `plugins/<pluginName>/`, and the `server`-level base is the server root. The `outer` level has no such restriction.

**Runtime config directory protection**: The implementation **must** reject all fs **write operations** (`writeFile` / `appendFile` / `writeBase64` / `appendBase64` / `delete` / `mkdir`) that modify the runtime config directory (e.g. `plugins/Yeow/runtime/`) (intercepted consistently at all levels) — that directory holds the runtime's `config.yml` and `approve.json`, which plugins must not be able to tamper with through the fs API. Reads are unrestricted.
