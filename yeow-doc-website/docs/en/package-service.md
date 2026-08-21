# Encapsulating Service Packages

> Independent topic split from [Writing Dependency Packages](package-author.md): How to encapsulate Services in npm packages (inter-plugin communication and native extension). Package basic structure (package.json / permissions / resources) see [Writing Dependency Packages](package-author.md).

Service is the most common encapsulation object in Yeow packages. Divided into three types based on **package's role** (caller / service provider) and **service source**:

| Type                         | Package's Role     | Registers Service? | Service Source                  | Typical Scenarios                                                  |
| ---------------------------- | ------------------ | :----------------: | ------------------------------- | ------------------------------------------------------------------ |
| **1. SDK (Caller Syntax Sugar)** | Pure caller        |         ❌         | Services already registered by other plugins | Services exposed by economy, territory, shop plugins              |
| **2. JS Service (Global Unique)** | Service provider + caller | ✅ (fail degraded) | Registered by this package (JS logic) | Universal functions needing global unique state (stats, chat formatting, cross-plugin data) |
| **3. Native Service**        | Service provider (child process) | ✅ (fail degraded) | Registered by this package (`assets/` binary) | Heavy computation like image processing, machine learning         |

**Combination Rule**: Types can be combined. Most typical is **Type 2 + 3** — package registers JS service as **facade** (external path convention, event publishing), internally starts native child process as **engine** (heavy computation). Type 2 fully includes Type 1's caller encapsulation (degraded back to Type 1 form after degradation).

## Type 1 — SDK (Caller Syntax Sugar)

Service provided by **other plugins** (usually that plugin imported Type 2/3 package, or directly registered service). This package only does call encapsulation:

- Does not call `registerService` / `registerNativeService`
- Does not touch `token`, does not publish events
- Externally exposes `serviceRequest` / `serviceSubscribe` encapsulation functions

```ts
// yeow-economy-sdk — Caller syntax sugar
import { serviceRequest, serviceSubscribe } from 'yeow-api';

export const ECONOMY_SERVICE = 'iyexin.economy.v1';   // serviceId agreed with provider

export function deposit(player: string, amount: number) {
    return serviceRequest(ECONOMY_SERVICE, '/deposit', { player, amount });
}

export function onBalanceChange(handler: (p: { player: string; balance: number }) => void) {
    return serviceSubscribe(ECONOMY_SERVICE, 'balanceChange', handler);
}
```

Key points:
- `serviceId` declared as constant, consistent with provider agreement
- Consumer's plugin must be **installed simultaneously** with service-providing plugin (otherwise request fails: service not found)

## Type 2 — JS Service (Global Unique)

Package implements service provider logic (pure JS), attempts registration at entry: **Success → This plugin becomes sole service instance; Failure (same-name public service exists) → Degraded to caller**, uses `err.serviceId` to access existing service:

> **Encapsulation Iron Rule (Server and Client Isolation)**: A service's encapsulation includes **server logic** (`onRequest`, `publish`) and **client logic** (`subscribe`, `request`). Since public services can only have one server at any time, package must **first attempt to register server**, then **encapsulate external interface as client identity**; regardless of registration success, externally exposed logic should be consistent. **Absolutely no exposing any interface that directly calls server capabilities (most typical is publishing events)** — registration failure can't get `token`, logic incomplete; even if `token` obtained, different plugin JS contexts cause fatal state inconsistency if externally published. Even if need is just pure event publishing, should use `serviceRequest(svcId, '/publishEvent', event)` with server cooperation. See [Service API Self-Contained Design](api/service.md#self-contained-design-for-public-services).

```ts
// yeow-stats — JS service + caller encapsulation
import { registerService, serviceRequest, servicePublish } from 'yeow-api';

let _serviceId: string;
let _svc: { serviceId: string; token: string } | null = null;

export async function initStats() {
    try {
        _svc = await registerService('iyexin.stats.v1', async (path, body) => {
            if (path === '/record') {
                const entry = await store.record(body.kind, body.value);
                if (_svc) servicePublish(_svc.token, 'record', entry);  // Server internal publish
                return { ok: true };
            }
            if (path === '/publishEvent') {          // Server cooperation for external "only publish event" need
                await store.emit(body.event, body.payload);
                if (_svc) servicePublish(_svc.token, body.event, body.payload);
                return { ok: true };
            }
            return { err: 'unknown path' };
        });
        _serviceId = _svc.serviceId;   // Became server, token stays in package
    } catch (e) {
        _serviceId = e.serviceId;      // Already exists: degraded to caller
    }
}

// Externally only expose call encapsulation — server and caller use same code path
export function record(kind: string, value: number) {
    return serviceRequest(_serviceId, '/record', { kind, value });
}

// Even if just "publish event", don't expose publish — use request, server internally publishes
export function publishEvent(event: string, payload: unknown) {
    return serviceRequest(_serviceId, '/publishEvent', { event, payload });
}
```

Key points:
- **Service unique**: When multiple plugins import same package, first registered plugin becomes server, rest automatically degraded to callers. All plugins' `record()` ultimately requests **same service instance**, behavior consistent
- **Global unique state**: Server's state (e.g., `store`) only exists in server plugin's JS context — context cannot be shared across plugins, this is the value of "global unique"
- After degradation, this package's call encapsulation still works (routed to existing service via `err.serviceId`), callers don't need to perceive

## Type 3 — Native Service

Package carries binary (`assets/`), uses `registerNativeService` to extract by platform and start child process, then encapsulates calls. Core pattern:

```ts
// yeow-image — Native service
import { registerNativeService, serviceRequest } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

export const IMAGE_SERVICE = 'iyexin.image-svc.v1';

export async function initRenderer(): Promise<ImageRenderer> {
    const { serviceId, ready, onTerminate } = await registerNativeService(IMAGE_SERVICE, {
        'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
        'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
        'macos-x64':   getAssetsPath('native/macos-x64/image-svc'),
        'macos-arm64': getAssetsPath('native/macos-arm64/image-svc'),
    });
    await ready();
    onTerminate((info) => { /* Child process terminated: log / switch degradation plan */ });

    return {
        serviceId,
        render(width, height, pixels) {
            return serviceRequest(serviceId, '/imageRender', { width, height, base64: pixels.toBase64() });
        },
    };
}
```

Key points:
- Registration failure (duplicate registration / platform unsupported) also rejects — after capture degrade according to Type 2 (`err.serviceId` access existing service), or directly throw error
- `onTerminate` only meaningful on **server** side (child process started by server; not triggered after degraded to caller)

### Native Service Error Handling and Degradation

`registerNativeService` / `ready()` rejection reasons need distinction (service already exists / executable tampered). **Unapproval handled at loading layer**: Plugins declaring native services are refused loading by default (console prompts one-time code `/yeow approve <code>`, automatically loads after approval), plugin doesn't run; if already approved, registration stage errors only remain following two types:

```ts
import { registerNativeService, serviceRequest, log } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

export async function initRenderer(): Promise<ImageRenderer | null> {
    try {
        const { serviceId, ready } = await registerNativeService(IMAGE_SERVICE, {
            'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
            'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
        });
        await ready();
        return { render: (w, h, px) => serviceRequest(serviceId, '/imageRender', { width: w, height: h, base64: px.toBase64() }) };
    } catch (e) {
        const msg = (e as Error).message;
        if (msg.includes('Service already registered')) {
            // Service already exists: access existing service as caller (normal degradation)
            const sid = (e as any).serviceId as string;
            return { render: (w, h, px) => serviceRequest(sid, '/imageRender', { width: w, height: h, base64: px.toBase64() }) };
        }
        if (msg.includes('hash mismatch')) {
            // Executable tampered (declaration doesn't match actual SHA-256): refuse to use
            log.error('Native binary tampered — refusing to load');
            return null;
        }
        log.error('Native service failed:', msg);
        return null;
    }
}
```

> Native service's **trust declaration (SHA-256) and approval mechanism** see [Permissions & Native Service Trust](permissions.md#2-native-service-trust-declaration).

## Combination — JS Facade + Native Engine (Type 2 + 3)

JS service as facade (external path convention, event publishing), native child process as engine (heavy computation). Package simultaneously serves as server (registering JS service) and child process manager:

```ts
// yeow-image-svc — Type 2 + 3 combination
import { registerService, registerNativeService, serviceRequest, servicePublish } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

let _serviceId: string;
let _svc: { serviceId: string; token: string } | null = null;
let _engine: { render(p: any): Promise<any> } | null = null;

export async function initImageService() {
    try {
        // ① First register JS facade (prerequisite for becoming server)
        _svc = await registerService('iyexin.image-svc.v1', async (path, body) => {
            if (path === '/render') {
                const result = await _engine!.render(body);          // Engine: native child process
                if (_svc) servicePublish(_svc.token, 'rendered', result);  // Server internal publish
                return result;
            }
            return { err: 'unknown path' };
        });
        _serviceId = _svc.serviceId;

        // ② After becoming server, then start native engine
        const native = await registerNativeService('iyexin.image-engine.v1', {
            'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
            'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
        });
        await native.ready();
        _engine = { render: (p) => serviceRequest(native.serviceId, '/render', p) };
    } catch (e) {
        // JS facade already exists → Overall degraded to caller
        _serviceId = e.serviceId;
    }
}

export function render(width: number, height: number, pixels: Uint8Array) {
    return serviceRequest(_serviceId, '/render', { width, height, base64: pixels.toBase64() });
}
```

> **Note**: First register JS facade, then start engine. If engine startup fails (e.g., platform unsupported) but facade already successfully registered, package should decide itself: Throw error to interrupt initialization (registered facade becomes orphan service, needs plugin restart to recover), or continue running in "no engine" mode and return error externally.

## How to Choose

| Your Scenario                             | Type      |
| ----------------------------------------- | --------- |
| Service provided by other plugins, only need to write call encapsulation | **1**     |
| Need global unique logic/state, shared by multiple plugins | **2**     |
| Need binary/heavy computation capability  | **3**     |
| JS facade + native engine                 | **2 + 3** |

---

Related: Service complete API see [Service API](api/service.md); Native child process TCP protocol see [Native Service Specification](specifications/native-service/index.md).