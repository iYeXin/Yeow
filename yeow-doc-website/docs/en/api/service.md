# Service API

Inter-plugin communication and native capability extension.

```js
import { registerService, registerNativeService, serviceRequest, serviceSubscribe, servicePublish } from 'yeow-api';
import type { ServiceResult, NativeServiceResult } from 'yeow-api';
```

## Self-Contained Design for Public Services

> This is Yeow Service's core design principle, please read this section before writing plugins/npm packages.

**Public service is a self-contained "service provider", boundary between service and caller must be clear:**

| Role | Capabilities |
| ---- | ------------ |
| **Service Provider** (registrant) | Holds `token`, handles `onRequest` requests, calls `publish` to publish events **within service** according to business logic |
| **Caller** (external) | Can only request service via `request` (`serviceRequest`), or subscribe to service events via `subscribe` |

**A service's encapsulation consists of two parts:**

| Part | Capabilities |
| ---- | ------------ |
| **Server Logic** (service provider) | `onRequest` handles requests, `publish` publishes events |
| **Client Logic** (caller) | `request` requests service, `subscribe` subscribes to events |

**Since public services can only have one server at any time** (duplicate registration refused, other plugins automatically degraded to callers), when encapsulating npm packages must **isolate server and client**, typical code structure:

1. **Attempt to register server** — Success becomes sole service instance (holds `token` and `onRequest`); failure degrades, uses `err.serviceId` to access existing service
2. **Encapsulate external interface as client identity** — Externally only expose `serviceRequest` / `serviceSubscribe` encapsulation functions, hide serviceId/token details

**Regardless of registration success, logic exposed to client should be consistent** — Consumer's plugin doesn't care whether it holds server, calls same set of interfaces.

**Absolutely no exposing any interface that directly calls server capabilities (most typical is publishing events):**

- **When registration unsuccessful can't get `token`** — External interfaces depending on `publish` directly have logic gap (call has no effect or errors)
- **Even if design not rigorous lets external get `token`** — Different plugins run in independent JS contexts, caller directly publishing events causes **fatal state inconsistency**: Event's business meaning (who has permission to publish, what to publish) only meaningful in server context, external publishing equals bypassing server directly tampering with business state

**Most extreme example**: Even if need is just **purely publishing an event** (e.g., broadcast balance change after `deposit` success), should use `serviceRequest(svcId, '/publishEvent', event)` in external encapsulation implementation, with server cooperating to handle that path and `publish` — publishing always occurs within server.

Specific rules:

1. **`token` is service provider's private credential**, only returned once at registration, should not be exposed externally.
2. **Duplicate registration rejects Promise**: When `isPublic: true` and same-name service already exists, `registerService` / `registerNativeService` thrown `Error` carries `serviceId` field (existing service's ID). Caller should catch error, and use `err.serviceId` to access existing service instance **as caller identity**.
3. **npm package encapsulation pattern**: Public services usually come from one npm dependency package (multiple plugins will import). Divided into three types by package's role (SDK / JS service / native service, combinable) — see below [Service Package Types](#service-package-typesnpm). Below is a "JS service" (Type 2) example, package is both service provider and caller:

```ts
// yeow-economy package internal — Registration + encapsulation
import { registerService, serviceRequest, servicePublish } from 'yeow-api';

let _serviceId: string;
let _svc: { serviceId: string; token: string } | null = null;

export async function initEconomy() {
  try {
    _svc = await registerService('iyexin.economy.v1', async (path, body) => {
      // Service provider: After receiving request, internally publishes event according to business logic
      if (path === '/deposit') {
        await db.deposit(body.player, body.amount);
        if (_svc) servicePublish(_svc.token, 'deposit', { player: body.player, amount: body.amount });
        return { ok: true };
      }
      return { err: 'unknown path' };
    });
    _serviceId = _svc.serviceId;   // Token stays in package, not externally passed
  } catch (e) {
    // Already exists: Access existing service instance as caller
    _serviceId = e.serviceId;
  }
}

// Externally only expose request encapsulation
export function deposit(player: string, amount: number) {
  return serviceRequest(_serviceId, '/deposit', { player, amount });
}
```

> Type 1 (pure SDK) only needs to remove above `registerService` part, change `_serviceId` to constant; Type 3 (native service) change `registerService` to `registerNativeService` + `await ready()`. Combination pattern (JS facade + native engine) see [package-author.md](../package-author.md).

> **Why can't "duplicate registration equals sharing"?** Each plugin runs in independent JS context, cannot share service state. If two plugins each register same-name service, `onRequest` only delivers to one, other plugin's handler silently ignored, behavior unpredictable. Therefore designed as: **Service unique, rest are all callers**.

> **serviceId naming convention:** Public service's serviceId is `refName` (when `isPublic: true`). To avoid conflicts between different authors/different versions, please use `author.serviceName.version` format, e.g., `iyexin.economy.v1`.

## Service Package Types (npm)

npm packages encapsulating Services divided into three types by **role**, combinable:

| Type | Package's Role | Registers Service | Service Source | Typical Scenarios |
| ---- | -------------- | :----------------: | -------------- | ----------------- |
| **1. SDK (Caller Syntax Sugar)** | Pure caller | ❌ | Services already registered by other plugins | Services exposed by economy, territory, shop plugins |
| **2. JS Service (Global Unique)** | Service provider + caller | ✅ Fail degraded | This package's JS logic | Universal functions needing global unique state |
| **3. Native Service** | Service provider (child process) | ✅ Fail degraded | This package's `assets/` binary | Heavy computation like image processing, machine learning |

- **Type 1**: Doesn't register service, doesn't hold token, only encapsulates `serviceRequest` / `serviceSubscribe`. Service must be provided by other plugins
- **Type 2**: Entry attempts registration, success becomes sole service instance (token/onRequest/event publishing within package); same-name service already exists registration refused, uses `err.serviceId` **degraded to caller** to access existing service. Externally only exposes request encapsulation
- **Type 3**: `registerNativeService` extracts `assets/` binary by platform and spawns child process; degradation logic same as Type 2
- **Combination (2 + 3)**: Type 2's service provider logic can embed Type 3's native engine — package registers JS service as **facade** (path convention, event publishing), internally manages native child process as **engine** (heavy computation)

> Complete code examples (including Types 1/2/3 and combination patterns) see [package-author.md](../package-author.md) "Encapsulating Service Packages (Three Types)".

## Plugin Service

### Registration

```js
const { serviceId, token } = await registerService('myService', (path, body) => {
    switch (path) {
        case '/api/add': return { sum: body.a + body.b };
        case '/api/echo': return { you_sent: body };
        default: return { err: 'unknown path' };
    }
}, true);
```

| Parameter   | Default | Description                                           |
| ----------- | ------- | ----------------------------------------------------- |
| `refName`   | —       | Service reference name. When `public=true` also serves as serviceId |
| `onRequest` | —       | Request handling callback. `(path, body) => result`   |
| `isPublic`  | `true`  | Whether public. When `false` Runtime assigns unique serviceId |

`registerService` returns `{ serviceId, token }`. `token` used for `publish` authentication (**service provider's private credential, don't expose externally**).

**Duplicate registration:** If `isPublic: true` and same-name service already exists, Promise rejects, `Error` has `serviceId` field (existing service's ID). At this time your `onRequest` won't take effect — should catch error, switch to `serviceRequest(err.serviceId, ...)` to access existing service. See above [Self-Contained Design](#self-contained-design-for-public-services).

### Publish Events

```js
servicePublish(token, 'playerJoin', { name: player.name, time: Date.now() });
```

## Native Service

### Registration

`registerNativeService` returns `{ serviceId, ready, onTerminate }`. `ready()` returns Promise:

- **resolve** — Native process TCP connection established, ready message received
- **reject** — Process abnormal exit, Error object carries `exitCode` (exit code) and `output` (stdout/stderr) fields; or service unloaded/not found

`onTerminate(handler)` — Register service termination hook, see below [Termination Hook](#termination-hook-onterminate).

> **Permission**: `service:registerNative` denied by default — must declare `"service:registerNative"` in `yeow.config.json`'s `permissions`, otherwise registration returns `Permission denied`. Plugin Service (`registerService`) allowed by default.

`platforms` supports three formats:

```js
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

// 1. String (backward compatible)
const { serviceId, ready } = await registerNativeService('myNative', {
    windows: getAssetsPath('native/win/my-svc.exe'),
    linux: getAssetsPath('native/linux/my-svc'),
    macos: getAssetsPath('native/macos/my-svc'),
});
await ready(); // Wait for process ready

// 2. Single file object
const { serviceId, ready } = await registerNativeService('myNative', {
    windows: { file: getAssetsPath('native/win/my-svc.exe') },
    linux:   { file: getAssetsPath('native/linux/my-svc') },
});

// 3. Directory + entry (extract entire directory to temp directory, run entry file)
const { serviceId, ready } = await registerNativeService('myNative', {
    windows: { dir: getAssetsPath('native/'), entry: 'win/start.ps1' },
    linux:   { dir: getAssetsPath('native/'), entry: 'linux/start.sh' },
    macos:   { dir: getAssetsPath('native/'), entry: 'macos/start.sh' },
}, true);
```

> **Trust verification and approval**: Plugin (or dependency package) declares `native` field in `yeow.config.json`, build computes binary SHA-256 writes to `yeow.json`.
>
> - **Approval (plugin loading layer)**: By default, plugins declaring native services are refused loading (console prints one-time code `/yeow approve <code>`, automatically loads after approval) — plugin doesn't run, `onLoad` won't execute
> - **Hash verification (runtime)**: When registering native service verifies selected binary SHA-256, mismatch (executable tampered) → **refuses to load**, `ready()` rejects
>
> Error reasons can be distinguished from `ready()`'s reject message:
>
> - `Service already registered: <id>` — Service already exists (use `err.serviceId` for degraded access, no approval needed)
> - `hash mismatch ... refused to load` — Executable tampered
>
> Complete try-catch degradation example see [Writing Dependency Packages](../package-author.md#native-service-error-handling-and-degradation).

**Supported platforms**: Keys support `operatingSystem` or `operatingSystem-architecture` granularity. **Exact match (including architecture) prioritized, falls back to operating system if not found**:

| Key | Description |
| --- | ----------- |
| `windows` / `windows-x64` | Windows (x64 or any architecture) |
| `linux` / `linux-x64` / `linux-arm64` | Linux x86_64 / ARM64 (Raspberry Pi, ARM servers) |
| `macos` / `macos-x64` / `macos-arm64` | macOS Intel / Apple Silicon |

```js
const svc = await registerNativeService('iyexin.image-svc.v1', {
    'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
    'linux-arm64': getAssetsPath('native/linux-arm64/image-svc'),
    'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
    'macos-x64':   getAssetsPath('native/macos-x64/image-svc'),
    'macos-arm64': getAssetsPath('native/macos-arm64/image-svc'),
});
```

> **Recommend providing at least `windows-x64` + `linux-x64` + `linux-arm64`**: Most Paper servers deploy on Linux x64 VPS, Linux ARM (Raspberry Pi/NAS/ARM cloud host), or Windows x64. When missing current platform configuration, registration returns error `No binary for platform: <os> (<os>-<arch>)`.

When `dir` uses `getAssetsPath()`, returns `assets/<id>/native/` — files **not hash-renamed**, `entry` and all files it references (including nested subdirectories, `../` sibling directories) keep original names, reference relationships intact.

> **`dir` should point to top-level directory containing all dependencies**, `entry` uses relative sub-path:
>
> ```ts
> // ❌ Only extract win/, start.bat referencing ../shared/ will break
> { dir: getAssetsPath('native/win/'), entry: 'start.bat' }
>
> // ✅ Extract entire native/ (self-contained), internal references complete
> { dir: getAssetsPath('native/'), entry: 'win/start.bat' }
> ```
>
> More boundaries see [Assets API](assets.md#boundaries-and-precautions).

`platforms` corresponds to paths relative to `assets/` directory for each platform. Build-time files under `assets/` packaged with plugin into JAR.

> **Duplicate registration:** When `isPublic: true` and same-name service already exists, Promise rejects, `Error` has `serviceId` field — service process won't be re-launched. Caller catches error then uses `err.serviceId` to access existing service instance as caller (`serviceRequest` / `serviceSubscribe`), instead of registering again. See above [Self-Contained Design](#self-contained-design-for-public-services).

> **serviceId naming convention:** Public service's serviceId is `refName`, to avoid conflicts between different authors' packages, should specify `author.serviceName.version`, e.g., `iyexin.image-svc.v1`:
>
> ```js
> import { registerNativeService } from 'yeow-api';
> import { getAssetsPath } from 'yeow-dev';
>
> const svc = await registerNativeService('iyexin.image-svc.v1', {
>     windows: getAssetsPath('native/windows/image-svc.exe'),
>     linux:   getAssetsPath('native/linux/image-svc'),
>     macos:   getAssetsPath('native/macos/image-svc'),
> });
> ```

> **Recommended usage:** Use `getAssetsPath()` to obtain resource paths, not hand-written strings. It injects namespace by caller's belonging dependency, ensuring paths remain correct after publishing npm package:
>
> ```js
> import { registerNativeService } from 'yeow-api';
> import { getAssetsPath } from 'yeow-dev';
> 
> const svc = await registerNativeService('mySvc', {
>     windows: getAssetsPath('native/win/my-svc.exe'),
>     linux:   getAssetsPath('native/linux/my-svc'),
> });
> ```
> 
> Especially important when encapsulated as npm public library — binaries in your `assets/` directory will be correctly hash-copied to final JAR, won't overwrite other packages' resources due to package name or path conflicts.

At registration binaries extracted to `%TEMP%/yeow-native-services/<serviceId>/` (automatically cleaned up previous residues at each startup).

Native Service **doesn't return token** — Native process auto-authenticates via TCP connection, events automatically associated with service by Runtime based on connection.

### Termination Hook `onTerminate`

`registerNativeService` returned object provides `onTerminate(handler)` method, registers service termination callback. Trigger timing:

| reason value | Trigger scenario |
| ------------ | ---------------- |
| `disconnected` | Child process TCP connection disconnected (process crash, network interruption) |
| `exited` | Child process exited (`exitCode` non-0 usually accompanied by reason 1) |
| `unregistered` | Service unloaded (plugin disabled / hot-reload) |
| `shutdown` | Runtime shutdown |

```ts
const svc = await registerNativeService('iyexin.image-svc.v1', {
    windows: getAssetsPath('image-svc.exe'),
});

svc.onTerminate((info) => {
    log.warn(`Image service terminated: ${info.reason}, exitCode=${info.exitCode}`);
    // Re-register or switch to degradation plan
});
```

Callback parameter `NativeTerminateInfo`: `{ serviceId, reason, exitCode?, output? }`. `output` is child process stdout+stderr combined output (if content exists). Only service holder (owner plugin) can register, repeated calls replace previous handler.

> **How does caller perceive service termination?** Pending `serviceRequest` Promise will reject when service terminates (error message contains reason). Subscribed events won't actively notify termination — caller can handle after receiving request failure.

### Executable Protocol

Executable receives `<yeowPort> <serviceId>` two startup parameters, communicates with Runtime via TCP using JSON line protocol. See [Native Service Specification](../specifications/native-service/index.md) for details.

> **Binary data transmission:** Request/response `body` is JSON, doesn't support directly transmitting binary. When need to transmit binary data, use Base64 encoding: `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()` (ES2026, engine native support).

## Request Service

```js
const result = await serviceRequest(serviceId, '/api/add', { a: 1, b: 2 });
// → { sum: 3 }
```

Returns Promise. If service not found or request fails, Promise rejects. If service terminates during request pending (disconnect/exit/unload), pending requests also rejected, error message format: `Native service <serviceId> terminated (<reason>)`.

Plugin Service and Native Service usage completely consistent — caller doesn't perceive service type.

## Subscribe Events

```js
const unsubscribe = serviceSubscribe(serviceId, 'status', (body, eventPath) => {
    console.log(body, eventPath);
});

unsubscribe();
```

Returns `() => void` cancel function. Runtime automatically cleans up subscriptions on plugin unload / hot-reload.

## Java Plugin Calling (Java Integration Interface)

Other **Java plugins** can also call services registered by Yeow plugins, or subscribe to their events (via runtime API, callback is Java `Consumer`):

```java
var rt = (YeowRuntime) Bukkit.getPluginManager().getPlugin("Yeow");

// Request-response (JS side registerService's onRequest handling)
rt.requestService("my-plugin.svc.v1", "/status", new JsonObject(), result -> {
    System.out.println(result);   // gson parse object; failure is {"err": ...}
});

// Subscribe to service events (publish triggered; close() cancels)
AutoCloseable sub = rt.subscribeService("my-plugin.svc.v1", "status", payload -> {
    System.out.println(payload);  // {serviceId, eventPath, body}
});
sub.close();
```

> Detailed protocol see [Java Plugin Integration Specification](../specifications/java-api.md).

## Example

```js
// Register Plugin Service
const svc = await registerService('chatSvc', (path, body) => {
    if (path === '/translate') return { translated: `[EN] ${body.text}` };
    return { err: 'unknown' };
});

const unsub = serviceSubscribe(svc.serviceId, 'message', (path, body) => {
    console.log(`[${body.author}]: ${body.text}`);
});

const translated = await serviceRequest(svc.serviceId, '/translate', { text: 'Bonjour' });

servicePublish(svc.token, 'message', { author: 'Bot', text: 'Hello!' });

unsub();
```

```js
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

// Register Native Service + wait ready (path must be resolved via getAssetsPath)
const { serviceId, ready } = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});

try {
    await ready();
    console.log('Native service is ready');
} catch (e) {
    console.error('Native service failed to start:', e.message);
    return;
}

const result = await serviceRequest(serviceId, '/render', { width: 1024, height: 1024 });
```

### Error Handling and Degradation (registerNativeService)

`ready()` may reject for multiple reasons (service already exists / executable tampered). Note: **Unapproval no longer appears in registration errors** — plugins declaring native services are refused at loading layer (console prompts `/yeow approve <code>`, automatically loads after approval), plugin doesn't run:

```js
import { registerNativeService, serviceRequest, log } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

try {
    const { serviceId, ready } = await registerNativeService('iyexin.image-svc.v1', {
        windows: getAssetsPath('native/win/image-svc.exe'),
    });
    await ready();
} catch (e) {
    const msg = e.message;
    if (msg.includes('Service already registered')) {
        // Service already exists: Use err.serviceId to access existing service as caller (normal degradation)
        const sid = e.serviceId;
        await serviceRequest(sid, '/ping', {});
    } else if (msg.includes('hash mismatch')) {
        // Executable tampered (declaration doesn't match actual SHA-256): Refuse to use, check binary source
        log.error('Native binary tampered — refusing to load');
    } else {
        log.error('Native service failed:', msg);
    }
}
```