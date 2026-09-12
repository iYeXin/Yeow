# Service API

Inter-plugin communication and native capability extension.

```ts
import {
  Service, PluginService, NativeService, ServiceReply,
  registerService, registerNativeService, getService, hasService,
} from 'yeow-api';
```

## Three Scenarios (by Complexity)

Depending on "who provides the service and what role your package plays", there are three scenarios with increasing complexity. Most cases only need the first two — you do **not** start with "public service self-containment / unique-instance degradation" (that is a Scenario 3 problem).

| Scenario                                                                   | Service source          | Your package       | Complexity | Typical                            |
| -------------------------------------------------------------------------- | ----------------------- | ------------------ | :--------: | ---------------------------------- |
| **1. The plugin itself provides a public service**                         | This plugin             | Provider (+caller) |    Low     | Economy plugin core                |
| **2. A dependency package only wraps calls and event listening**           | Other plugins           | Pure caller        |   Medium   | Caller SDK                         |
| **3. A dependency package embeds a service and also exposes an interface** | This dependency package | Provider + caller  |    High    | Globally-unique public service SDK |

> In Scenarios 1 and 2 the service provider is unique and well-defined, so there is no "unique instance" problem; only Scenario 3 needs the self-containment rules below.

### Scenario 1: The plugin itself provides a public service

The most direct: the plugin registers the service in `onLoad`, then uses the returned `PluginService` handle to request / publish / subscribe (see the economy plugin core in "Example" below).

```ts
import { PluginService, registerService, onLoad } from 'yeow-api';

let economy: PluginService;

onLoad(async () => {
  economy = await registerService('myplugin.economy.v1', (path, body) => {
    switch (path) {
      case '/balance': return { balance: db.balance(body.player) };
      case '/deposit':
        db.deposit(body.player, body.amount);
        economy.publish('deposit', body);   // publish inside the server
        return { ok: true };
      default: return { err: 'unknown path' };
    }
  });
});

// Inside this plugin use the handle directly; other plugins call getService('myplugin.economy.v1')
// await (await economy.request('/balance', { body: { player } })).json()
```

- The service follows the plugin lifecycle: automatically cleaned up on uninstall / hot-reload.
- No token isolation, no degradation — this plugin is the sole provider.

### Scenario 2: A dependency package only wraps calls and event listening

The dependency package (imported by multiple plugins) is purely a **caller**: obtain a consumer handle via `getService`, wrap `request` / `subscribe`; it does not register, does not hold a token, and cannot `publish`.

```ts
import { Service, getService } from 'yeow-api';

const SERVICE_ID = 'iyexin.economy.v1';
let svc: Service;

export async function initEconomyClient() {
  svc = await getService(SERVICE_ID);   // throws if the service does not exist (error.serviceId)
}

export async function balance(player: string): Promise<number> {
  return (await svc.request('/balance', { body: { player } })).json();
}

export function onDeposit(handler: (body: any) => void) {
  return svc.subscribe('deposit', handler);
}
```

- The service must be provided by another plugin; `getService` throws when it does not exist.
- The consumer handle has no token: it cannot `publish`, nor `unregister` a plugin service.

### Scenario 3: A dependency package embeds a service and also exposes an interface (most complex)

The dependency package **both** registers the service (provider) and exposes a call interface to consumers (caller). Because a public service is **globally unique**, this is where "self-contained design" is required.

**The boundary between provider and caller must be clear:**

| Role                      | Capabilities                                                                                 |
| ------------------------- | -------------------------------------------------------------------------------------------- |
| **Provider** (registrant) | Holds `token`, handles `onRequest`, calls `publish` **inside the service** by business logic |
| **Caller** (external)     | Can only `request` and `subscribe`                                                           |

**Each plugin runs in an independent JS context and cannot share service state** — if two plugins each register a same-name service, `onRequest` is delivered to only one and the other is silently ignored. Hence: **one service, everyone else is a caller**. The typical dependency-package structure:

1. **Attempt to register**: a successful `registerService` makes this the sole service instance (owner `PluginService` handle with `token` and `onRequest`); on failure (already exists) use `error.serviceId` to **degrade** via `getService` to the existing service (consumer handle).
2. **Only expose the call interface**: whether or not it holds the server, consumers call the same `request` / `subscribe` wrappers, hiding serviceId/token details.

```ts
import { PluginService, registerService, getService } from 'yeow-api';

let _svc: PluginService;   // success = owner handle (with token); after degradation = consumer handle (no token)

export async function initEconomy() {
  try {
    _svc = await registerService('iyexin.economy.v1', async (path, body) => {
      if (path === '/deposit') {
        await db.deposit(body.player, body.amount);
        _svc.publish('deposit', { player: body.player, amount: body.amount });   // publishing always inside the server
        return { ok: true };
      }
      return { err: 'unknown path' };
    });
  } catch (e) {
    _svc = (await getService(e.serviceId)) as PluginService;   // degrade to caller
  }
}

export async function deposit(player: string, amount: number) {
  return (await _svc.request('/deposit', { body: { player, amount } })).json();
}
```

> **Never expose provider capabilities (especially `publish`)** to the outside: on failed registration you do not get a `token` at all; even if it leaked, contexts are independent and an external `publish` bypasses the server causing **fatal state inconsistency**. Even if the need is merely "broadcast an event" (e.g., broadcast a balance change after `deposit`), have the caller `svc.request('/publishEvent', { body: event })` and let the server `publish` internally.
>
> **`token` is the provider's private credential**, returned only once at registration; do not pass it around.
>
> **Do not "check `hasService` and then register"**: checking and registering are **not atomic** and race under concurrency; wrap registration in `try-catch` and degrade when "already exists" is caught (see "Get & Check Services" below).

> **serviceId naming convention:** the public service's serviceId is `refName`. To avoid conflicts between authors/versions, use `author.serviceName.version`, e.g., `iyexin.economy.v1`.

> More dependency-package examples (pure caller / embedded JS service / embedded native service / JS facade + native engine) see [Encapsulating Service Packages](../package-service.md).

> For the concrete dependency-package types (pure-caller SDK / embedded JS service / embedded native service / JS facade + native engine) and full code examples, see [Encapsulating Service Packages](../package-service.md).

## Service Handles (OOP)

`registerService` / `registerNativeService` return **handle objects**; subsequent request, subscribe, publish, and unregister all go through handle methods. Request / subscribe capabilities are defined on the abstract `Service` base class:

| Member                          | Type                       | Description                                                        |
| ------------------------------- | -------------------------- | ------------------------------------------------------------------ |
| `id`                            | `string`                   | serviceId                                                          |
| `kind`                          | `'plugin' \| 'native'`     | Service type                                                       |
| `request(path, options?)`       | `Promise<ServiceResponse>` | Request service (see [Request Service](#request-service))          |
| `subscribe(eventPath, handler)` | `() => void`               | Subscribe to events (see [Subscribe Events](#subscribe-events))    |
| `unregister()`                  | `Promise<void>`            | Unregister service (see [Unregister Service](#unregister-service)) |

`ServiceResponse.ok` is fetch-compatible; `ServiceReply` lets the service side's `onRequest` carry response headers.

### PluginService extends Service

| Member                      | Description                                                                                                                             |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `token?`                    | Owner token; only held by the owner handle returned from `registerService`, `undefined` on the consumer handle returned by `getService` |
| `publish(eventPath, body?)` | Publish event. **Requires token**; calling on a consumer handle throws                                                                  |
| `unregister()`              | Requires token; calling on a consumer handle returns a rejected `Promise`                                                               |

### NativeService extends Service

| Member                 | Description                                                                                                       |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `ready()`              | `Promise<void>`: waits until the native process's TCP connection is established and the ready message is received |
| `onTerminate(handler)` | Registers a service termination hook, see [Termination Hook](#termination-hook-onterminate) below                 |

## Plugin Service

### Registration

```ts
import { registerService, ServiceReply } from 'yeow-api';

const svc = await registerService('myService', (path, body) => {
    switch (path) {
        case '/api/add': return { sum: body.a + body.b };
        case '/api/echo': return new ServiceReply({ you_sent: body }, { 'x-echo': 'true' });
        default: return { err: 'unknown path' };
    }
}, true);
```

| Parameter   | Default | Description                                                         |
| ----------- | ------- | ------------------------------------------------------------------- |
| `refName`   | —       | Service reference name. When `public=true` also serves as serviceId |
| `onRequest` | —       | Request handling callback. `(path, body) => result`                 |
| `isPublic`  | `true`  | Whether public. When `false` Runtime assigns unique serviceId       |

`registerService` returns a `PluginService` (owner handle): `svc.id` is the serviceId and `svc.token` is the publish/unregister authentication credential (**service provider's private credential, don't expose externally**). `onRequest` returning a `ServiceReply` carries response headers (`new ServiceReply(body, headers?)`); returning a plain value is equivalent to a JSON body.

**Duplicate registration:** If `isPublic: true` and same-name service already exists, Promise rejects, `Error` has `serviceId` field (existing service's ID). At this time your `onRequest` won't take effect — should catch error, switch to `await getService(err.serviceId)` to access existing service. See above "Scenario 3 (self-contained design)".

### Publish Events

```ts
svc.publish('playerJoin', { name: player.name, time: Date.now() });
```

`publish` requires the owner `token`; the consumer handle returned by `getService` has no token, and calling it throws.

## Native Service

### Registration

`registerNativeService` returns `Promise<NativeService>`. `await svc.ready()` returns Promise:

- **resolve** — Native process TCP connection established, ready message received
- **reject** — Process abnormal exit, Error object carries `exitCode` (exit code) and `output` (stdout/stderr) fields; or service unloaded/not found

`svc.onTerminate(handler)` — Register service termination hook, see below [Termination Hook](#termination-hook-onterminate).

> **Permission**: `service:registerNative` denied by default — must declare `"service:registerNative"` in `yeow.config.json`'s `permissions`, otherwise registration returns `Permission denied`. Plugin Service (`registerService`) allowed by default.

`platforms` supports two formats (**single file only**; directory mode has been removed):

```ts
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

// 1. String
const svc = await registerNativeService('myNative', {
    windows: getAssetsPath('native/win/my-svc.exe'),
    linux: getAssetsPath('native/linux/my-svc'),
    macos: getAssetsPath('native/macos/my-svc'),
});
await svc.ready(); // Wait for process ready

// 2. Single file object (equivalent to string)
const svc2 = await registerNativeService('myNative', {
    windows: { file: getAssetsPath('native/win/my-svc.exe') },
    linux:   { file: getAssetsPath('native/linux/my-svc') },
});
await svc2.ready();
```

> **Mandatory declaration and hash verification**: The plugin (or dependency package) **must** declare the `native` field in `yeow.config.json` (serviceId + binary file). At build time the SHA-256 is computed and written to `yeow.json`, then stored as metadata when the plugin is loaded.
>
> - **Mandatory declaration (build / load layer)**: If the `service:registerNative` permission is requested but the `native` manifest is empty → **build fails** (builder) / loading is **refused**
> - **Registration checks (runtime)**: During `registerNativeService`, if the serviceId is undeclared, the binary path is undeclared, directory mode is used, or the SHA-256 doesn't match → **registration refused**, Promise rejects
> - **Untrusted warning**: **Declaration ≠ trusted** — all native services are still treated as untrusted, and plugins requesting this permission print a prominent warning at load time; with `native-service-allow-untrusted: false` loading is refused
>
> Error reasons can be distinguished from `ready()`'s reject message:
>
> - `Native service '<id>' is not declared ...` / `Native binary '<path>' is not declared ...` — Undeclared (missing `native` in `yeow.config.json`, or missing that service/file)
> - `hash mismatch ... refused to load` — Executable tampered
>
> Complete try-catch degradation example see [Writing Dependency Packages](../package-author.md#native-service-error-handling-and-degradation).

**Supported platforms**: Keys support `operatingSystem` or `operatingSystem-architecture` granularity. **Exact match (including architecture) prioritized, falls back to operating system if not found**:

| Key                                   | Description                                      |
| ------------------------------------- | ------------------------------------------------ |
| `windows` / `windows-x64`             | Windows (x64 or any architecture)                |
| `linux` / `linux-x64` / `linux-arm64` | Linux x86_64 / ARM64 (Raspberry Pi, ARM servers) |
| `macos` / `macos-x64` / `macos-arm64` | macOS Intel / Apple Silicon                      |

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

`platforms` corresponds to paths relative to the `assets/` directory for each platform. Only **single files** are supported: the runtime extracts just that file to a temp directory, so the native binary must be **self-contained** (statically linked, or dependencies packed into a single executable). Directory mode has been removed. Build-time files under `assets/` packaged with plugin into JAR.

> **Duplicate registration:** When `isPublic: true` and same-name service already exists, Promise rejects, `Error` has `serviceId` field — service process won't be re-launched. Caller catches error then uses `err.serviceId` to obtain a handle via `await getService(err.serviceId)` and access the existing service instance as caller (`svc.request` / `svc.subscribe`), instead of registering again. See above "Scenario 3 (self-contained design)".

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

The `NativeService` returned by `registerNativeService` provides an `onTerminate(handler)` method that registers a service termination callback. Trigger timing:

| reason value   | Trigger scenario                                                                |
| -------------- | ------------------------------------------------------------------------------- |
| `disconnected` | Child process TCP connection disconnected (process crash, network interruption) |
| `exited`       | Child process exited (`exitCode` non-0 usually accompanied by reason 1)         |
| `unregistered` | Service unloaded (plugin disabled / hot-reload / `unregister()`)                |
| `shutdown`     | Runtime shutdown                                                                |

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

> **How does caller perceive service termination?** A pending `svc.request(...)` Promise rejects when the service terminates (error message contains reason). Subscribed events won't actively notify termination — caller can handle after receiving request failure.

### Executable Protocol

Executable receives `<yeowPort> <serviceId>` two startup parameters, communicates with Runtime via TCP using a **framed protocol** (header JSON + raw body, supporting streaming and raw binary). See [Native Service Specification](../specifications/native-service/index.md) for details.

> **Binary data transmission:** When `body` in `svc.request(path, { body })` is a `Uint8Array`, it is sent as raw binary (the runtime forwards it unchanged to the child process); responses are uniformly carried by a fetch-style `ServiceResponse` — use `await resp.json()` for JSON, `await resp.bytes()` / `await resp.arrayBuffer()` for binary.

## Get and Check Services

```ts
import { getService, hasService } from 'yeow-api';

const svc = await getService('iyexin.economy.v1'); // throws if absent (error.serviceId)
const exist = await hasService('iyexin.economy.v1'); // display / diagnostics only
```

- `getService(id)` — Get a handle by id; throws when absent (`error.serviceId`). The returned handle is a **consumer handle** (no token): when `kind === 'plugin'` it is a `PluginService`, but `publish` / `unregister` throw; when `kind === 'native'` it is a `NativeService`.
- `hasService(id)` — Check whether the service exists.

> **Warning: never "check with `hasService` then register"!** The check and the registration are **not atomic** — under concurrency they race: two plugins may both observe absence, then one registers successfully while the other is rejected. Registration must be wrapped in `try-catch` around `registerService`, and on "already exists" you must degrade via `error.serviceId` to join the existing service. `hasService` is only for display/diagnostics.

## Request Service

`svc.request(path, options)` returns `Promise<ServiceResponse>`, similar to fetch. `options`:

| Field         | Description                                                                                               |
| ------------- | --------------------------------------------------------------------------------------------------------- |
| `headers`     | App-level request headers (`Record<string, string>`); `content-type` is the typical entry                 |
| `contentType` | Convenience alias for `headers['content-type']`                                                           |
| `body`        | JSON value, or `Uint8Array` (raw binary, decoded by the runtime and forwarded as-is)                      |
| `timeout`     | Timeout in milliseconds; defaults to the runtime config (30000, tunable via `service-request-timeout-ms`) |

```js
const resp = await svc.request('/api/add', { body: { a: 1, b: 2 } });
const result = await resp.json();     // → { sum: 3 }

// Binary
const bin = await svc.request('/render', { body: new Uint8Array([...]) });
const bytes = await bin.bytes();      // Uint8Array
const ab = await bin.arrayBuffer();
```

`ServiceResponse`: `ok` / `status` / `contentType` / `headers` + `json()` / `text()` / `bytes()` / `arrayBuffer()` / `base64()` (`body` is reserved for a future readable stream). When the request body is a `Uint8Array` it is sent as raw binary, otherwise it is sent as a JSON value.

**`request` may throw:**

- Service not found
- Timeout — 30s by default, tunable via the runtime config `service-request-timeout-ms`, or overridden per-call with `options.timeout`; error message `Service request timed out after <ms>ms: <id>`
- Service internal error
- Service unloaded / terminated — error message `Native service <id> terminated (<reason>)`

Plugin Service and Native Service usage completely consistent — caller doesn't perceive service type.

## Subscribe Events

```js
const unsubscribe = svc.subscribe('status', (body, eventPath) => {
    console.log(body, eventPath);
});

unsubscribe();
```

Returns `() => void` cancel function. Runtime automatically cleans up subscriptions on plugin unload / hot-reload. Event subscription semantics are unchanged.

## Unregister Service

```js
await svc.unregister();
```

- **Plugin Service** requires the owner `token` (the owner handle returned by `registerService`); calling on the consumer handle returned by `getService` throws (`unregister requires the owner token ...`)
- **Native Service** requires no `token`, but the caller must be the owner plugin

## Example

```js
// Register Plugin Service
const svc = await registerService('chatSvc', (path, body) => {
    if (path === '/translate') return { translated: `[EN] ${body.text}` };
    return { err: 'unknown' };
});

const unsub = svc.subscribe('message', (body, eventPath) => {
    console.log(`[${body.author}]: ${body.text}`);
});

const translated = await (await svc.request('/translate', { body: { text: 'Bonjour' } })).json();

svc.publish('message', { author: 'Bot', text: 'Hello!' });

unsub();
```

```js
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

// Register Native Service + wait ready (path must be resolved via getAssetsPath)
const svc = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});

try {
    await svc.ready();
    console.log('Native service is ready');
} catch (e) {
    console.error('Native service failed to start:', e.message);
    return;
}

const result = await (await svc.request('/render', { body: { width: 1024, height: 1024 } })).json();
```

### Error Handling and Degradation (registerNativeService)

`ready()` may reject for multiple reasons (service already exists / executable tampered). Note: requesting the `service:registerNative` permission only controls the load-time warning/refusal (see above); registration-stage errors are only the following two types:

```js
import { registerNativeService, getService, log } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

try {
    const svc = await registerNativeService('iyexin.image-svc.v1', {
        windows: getAssetsPath('native/win/image-svc.exe'),
    });
    await svc.ready();
} catch (e) {
    const msg = e.message;
    if (msg.includes('Service already registered')) {
        // Service already exists: Use err.serviceId to access existing service as caller (normal degradation)
        const sid = e.serviceId;
        const svc = await getService(sid);
        await svc.request('/ping', { body: {} });
    } else if (msg.includes('hash mismatch')) {
        // Executable tampered (declaration doesn't match actual SHA-256): Refuse to use, check binary source
        log.error('Native binary tampered — refusing to load');
    } else {
        log.error('Native service failed:', msg);
    }
}
```
