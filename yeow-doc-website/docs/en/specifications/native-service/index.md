# Native Service Protocol

Yeow Native Service is an executable launched as a subprocess that communicates with Yeow-Runtime via TCP to provide native capability extensions (such as machine learning, image processing, etc.).

## Startup Arguments

The executable receives two command-line arguments at startup:

```
<executable> <yeowPort> <serviceId>
```

| Parameter   | Description                          |
| ----------- | ------------------------------------ |
| `yeowPort`  | TCP listening port of Yeow-Runtime   |
| `serviceId` | Unique ID assigned to this service   |

**Working Directory**: The subprocess's default working directory is the **server root directory** (Java process working directory) — the subprocess can directly read/write server files using relative paths (e.g., `config.yml`, files under `plugins/`); the binary itself is extracted to a temporary directory for execution, which does not affect working directory semantics.

## Startup Flow

```
1. Yeow-Runtime spawns subprocess: svc.exe <yeowPort> <serviceId>
2. After startup, subprocess connects to TCP: connect("127.0.0.1", yeowPort)
3. Immediately sends ready message
4. Begins processing requests
```

The JS side can wait for readiness via the `ready()` method on the `NativeService` returned by `registerNativeService`:

```js
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

const svc = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});
await svc.ready(); // Promise resolve means TCP connection established and ready message received
```

> **Paths must be resolved via `getAssetsPath()`**: At build time, resources receive a namespace prefix (e.g., `image-svc.exe` → `assets/a1b2c3d4/image-svc.exe`); hardcoding the original path will fail to find the file at runtime.

If the process exits abnormally before sending the ready message, `ready()` will reject. The Error object contains:

| Property    | Type     | Description                               |
| ----------- | -------- | ----------------------------------------- |
| `message`   | `string` | Error description (includes exit code)     |
| `exitCode`  | `number` | Process exit code                          |
| `output`    | `string` | Combined stdout + stderr output            |

```js
try {
    await svc.ready();
} catch (e) {
    console.error(e.message);   // "Native service image-svc exited with code 1"
    console.error(e.exitCode);  // 1
    console.error(e.output);    // "error: cannot load library ...\nat main.go:42\n"
}
```

## Communication Protocol

The TCP connection uses a **framed protocol** (a breaking replacement for the old JSON line):

```
[u32 headerLen][header JSON (UTF-8)] [u32 chunkLen][chunk bytes] ... [u32 0]
```

- **header**: JSON carrying the type and metadata (`type` / `id` / `path` / `headers` / `contentType` / `eventPath` / `reason`, ...)
- **body**: a zero-terminated sequence of chunks carrying **raw bytes** (no base64); it supports streaming (the sender need not know the total length up front); an empty body is a single zero-length chunk
- All lengths are **big-endian u32**; header ≤ 64 KiB, body ≤ 256 MiB (oversized messages are rejected and the connection dropped)

Yeow-Runtime is the server (passive listener) and the subprocess is the client (active connector). JSON `number` values are not guaranteed to be `int`; receive them as floating-point and convert manually.

### 1. Ready Message (child → runtime)

The subprocess must send this immediately upon readiness (header JSON + empty body):

```json
{"type":"ready","serviceId":"mySvc_a1b2","servicePort":12345}
```

| Field         | Description                                            |
| ------------- | ------------------------------------------------------ |
| `serviceId`   | The serviceId matching the startup argument             |
| `servicePort` | Port the subprocess listens on internally (reserved, currently unused) |

### 2. Request (runtime → child)

When a Yeow plugin calls a service request (header JSON, immediately followed by the body chunks):

```json
{"type":"request","id":"svcreq_1","path":"/api/process","headers":{"content-type":"application/json"},"contentType":"application/json"}
```

| Field         | Description                                                       |
| ------------- | ----------------------------------------------------------------- |
| `id`          | Unique request ID, must be echoed in the response                 |
| `path`        | Request path                                                      |
| `headers`     | App-level request header key/value pairs (`content-type` is the typical entry) |
| `contentType` | `application/json` (default) or `application/octet-stream` (raw binary); a convenience alias for `headers['content-type']` |

The body is raw bytes: for JSON requests it is the UTF-8 text of the serialized object; for binary requests the JS side carries it as base64, the runtime decodes it and **forwards the raw bytes** (the subprocess always receives raw bytes).

### 3. Response (child → runtime)

```json
{"type":"response","id":"svcreq_1","headers":{"content-type":"application/json"},"contentType":"application/json"}
```
(immediately followed by the body chunks)

| Field         | Description                                                        |
| ------------- | ------------------------------------------------------------------ |
| `id`          | ID matching the request exactly                                    |
| `headers`     | App-level response header key/value pairs (`content-type` is the typical entry) |
| `contentType` | Body type; JS uses `resp.json()` for JSON and `resp.bytes()` for binary; a convenience alias for `headers['content-type']` |

The runtime hands the response body (raw bytes + headers + contentType) to the consumer, which reads it via a fetch-style `ServiceResponse` on the JS side.

### 4. Publish Event (child → runtime)

```json
{"type":"publish","eventPath":"status"}
```
(immediately followed by the JSON body chunks)

| Field       | Description                     |
| ----------- | ------------------------------- |
| `eventPath` | Event path                      |
| body        | Event body (JSON object, UTF-8) |

Event delivery semantics are unchanged: the runtime parses the JSON body and delivers it to subscribers matching `eventPath`.

### 5. Shutdown (runtime → child)

When the runtime stops a service (plugin uninstall / hot-reload / runtime shutdown), it pushes (header JSON + empty body):

```json
{"type":"shutdown","reason":"unregistered"}
```

| Field    | Description                                                      |
| -------- | ---------------------------------------------------------------- |
| `reason` | `unregistered` (uninstall) / `shutdown` (runtime shutdown)       |

After receiving this, the subprocess should **perform its own resource cleanup** (close files, flush persistence, stop internal threads) and exit the process — the runtime uses process exit as the completion signal; if it hasn't exited after 3 seconds, `destroy()` is called, and after another 3 seconds if still running, `destroyForcibly()` is used to force termination.

## Discovery and Communication Topology

```
Yeow-Runtime (TCP server)
  ↑ connect
  │
  ├─ svc1.exe ── TCP ──→ accepts requests, sends responses, publishes events
  ├─ svc2.exe ── TCP ──→ same
  └─ ...
```

Yeow-Runtime is a multiplexed relay: plugins interact with Native Services through `request` / `subscribe` / `publish`, and the runtime is responsible for forwarding. A single TCP server handles all Native Service connections (distinguished by the `serviceId` field).

## Exit Behavior

- When the subprocess exits:
  - Connection disconnects → Yeow-Runtime marks the service as unavailable; subsequent requests return `{"err":"service not ready"}`
  - Plugin unload / hot-reload → Runtime pushes a `shutdown` message, subprocess cleans up and exits (up to 6 seconds wait, then `destroyForcibly()` forces termination)

## Packaging and Deployment

Executables are placed in the plugin's `assets/` directory. At registration, platform-specific configurations are specified via the `platforms` parameter, **single file mode only**:

```json
{ "windows": "native/win/my-svc.exe" }
```

Or the equivalent object form `{ "windows": { "file": "native/win/my-svc.exe" } }`. Directory mode (`{dir, entry}`) has been removed — native binaries must be **self-contained** (statically linked, or dependencies packed into a single executable).

**Extraction directory: `<TEMP>/yeow-native-services/<serviceId>/`**
- Automatically cleaned up on each Runtime startup
- Automatically cleaned up and re-extracted on plugin hot-reload

## Mandatory Declaration & Verification (SHA-256)

Plugins or dependency packages **must** declare a `native` field in `yeow.config.json` to fix binary hashes (at build time the builder walks the main project + dependency packages, computes the SHA-256 of the packaged path and merges it into the `native` field of `yeow.json`; at plugin load it is stored as metadata). **Single file mode only** (`string` / `{file}`).

**Mandatory declaration (build + load layer)**: For a plugin requesting the `service:registerNative` permission, its merged `native` manifest must not be empty — if empty at build time, the **build fails** (missing declared files also fail); if empty at load time, **loading is refused**. Plugins not requesting that permission do not need to declare it.

**Registration checks (runtime, always executed)**: When registering a native service, three things are verified — serviceId declared, binary path declared, SHA-256 matches; if any is missing, **registration is refused** (`ready()` rejects, with an error pointing to undeclared or hash mismatch — the executable may have been tampered with).

**Untrusted switch (plugin loading layer)**: **Declaration ≠ trusted**. By default (`native-service-allow-untrusted: true`) plugins that declared native services load normally, but the console prints a prominent untrusted warning; with `false`, plugins requesting that permission are **rejected at load time** (console points back to `true`).

**Configuration persistence**:

- `native-service-allow-untrusted` in `config.yml` — read by the runtime; when an existing config lacks the field, defaults are merged on load and written back (smooth upgrade)
- Files are located in `plugins/Yeow/runtime/` (`config.yml`) — this directory is protected by fs write protection, and plugins cannot modify it through the fs API

Whether declared or not, risk logs are printed as usual (treated as untrusted). Online safety check (comparing against the officially maintained safety list) is planned: listed binaries will then load without this warning.