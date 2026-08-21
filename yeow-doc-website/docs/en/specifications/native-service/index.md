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

The JS side can wait for readiness via the `ready()` method returned by `registerNativeService`:

```js
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

const { serviceId, ready } = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});
await ready(); // Promise resolve means TCP connection established and ready message received
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
    await ready();
} catch (e) {
    console.error(e.message);   // "Native service image-svc exited with code 1"
    console.error(e.exitCode);  // 1
    console.error(e.output);    // "error: cannot load library ...\nat main.go:42\n"
}
```

## Communication Protocol

The TCP connection uses the **JSON line** protocol (one complete JSON object per line, separated by `\n`). Yeow-Runtime acts as the server (passive listener), and the subprocess acts as the client (active connector). Implementations should ensure the read buffer is large enough to accommodate the maximum expected line size (e.g., Go's `bufio.Scanner` defaults to 64KB, which must be expanded to support large payloads like base64-encoded data).

In received JSON objects, values that should be `int` are not guaranteed to be `int`; it is recommended to receive them as **floating-point** and convert manually.

### 1. Ready Message (child → runtime)

The subprocess must send this immediately upon readiness:

```json
{"type":"ready","serviceId":"mySvc_a1b2","servicePort":12345}
```

| Field         | Description                                            |
| ------------- | ------------------------------------------------------ |
| `serviceId`   | The serviceId matching the startup argument             |
| `servicePort` | Port the subprocess listens on internally (reserved, currently unused) |

### 2. Request (runtime → child)

When a Yeow plugin calls a service request:

```json
{"type":"request","requestId":"svcreq_1","path":"/api/process","body":{"key":"value"}}
```

| Field       | Description                                     |
| ----------- | ----------------------------------------------- |
| `requestId` | Unique request ID, must be echoed in the response |
| `path`      | Request path                                     |
| `body`      | Request body (JSON object)                       |

### 3. Response (child → runtime)

```json
{"type":"response","requestId":"svcreq_1","body":{"result":"ok"}}
```

| Field       | Description                    |
| ----------- | ------------------------------ |
| `requestId` | ID matching the request exactly |
| `body`      | Response body (JSON object)    |

### 4. Publish Event (child → runtime)

```json
{"type":"publish","eventPath":"status","body":{"health":0.95}}
```

| Field       | Description                 |
| ----------- | --------------------------- |
| `eventPath` | Event path                   |
| `body`      | Event body (JSON object)    |

### 5. Shutdown (runtime → child)

When the runtime stops a service (plugin uninstall / hot-reload / runtime shutdown), it pushes:

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

Executables are placed in the plugin's `assets/` directory. At registration, platform-specific configurations are specified via the `platforms` parameter:

**Single file mode (file/string):**
```json
{ "windows": "native/win/my-svc.exe" }
```

**Directory + entry mode (dir+entry):**
```json
{ "windows": { "dir": "native/win/", "entry": "start.ps1" } }
```
In this mode, all files in the directory specified by `dir` are extracted to a temporary directory, then the `entry` file is executed.
Suitable for complex native services with multi-file dependencies (e.g., Python scripts, Node.js projects).

**Extraction directory: `<TEMP>/yeow-native-services/<serviceId>/`**
- Automatically cleaned up on each Runtime startup
- Automatically cleaned up and re-extracted on plugin hot-reload

## Trust Statement and Approval (SHA-256)

Plugins or dependency packages can declare a `native` field in `yeow.config.json` to fix binary hashes (the SHA-256 of the packaged path is computed at build time and written to the `native` field in `yeow.json`). **Declarations only apply to single file mode** (`string` / `{file}`); directory mode (`{dir, entry}`) is not yet supported.

**Approval (plugin loading layer)**: By default (`native-service-require-approval: true`), all native services are considered untrusted. Plugins that declare native services are **rejected at load time** — the console prints a prominent prompt containing a one-time approval code (6-digit base-36, visible only in the console; since the plugin isn't loaded, the code cannot be predicted or auto-approved): after an administrator runs `/yeow approve <code>`, the plugin is **automatically loaded**.

**Hash verification (runtime)**: After the plugin is loaded, when registering a native service, the SHA-256 of the selected binary (single file mode) is verified: if it doesn't match the declaration → **rejected** (`ready()` rejects, with an error containing the declared/actual hash — the executable may have been tampered with).

**Configuration and approval persistence**:

- `native-service-require-approval` in `config.yml` is the **trust source** — the runtime takes effect immediately upon modification
- Files are located in `plugins/Yeow/runtime/` (`config.yml`, `approve.json`) — this directory is protected by fs write protection, and plugins cannot modify it through the fs API

When not declared/not approved, risk logs are printed as usual (treated as untrusted). In the future, Yeow official or community may maintain a list of known safe SHA-256 hashes: binaries that hit the list may be marked as safe at plugin load time without warnings.