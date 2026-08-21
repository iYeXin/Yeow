# Runtime Environment Capabilities

**Runtime environment overview** for Yeow plugin developers: Your plugin code runs in an **independent QuickJS single thread per plugin** (Java side), not a browser, nor full Node.js. This article explains "what capabilities you have, what you don't", and differences from standard browser / Node environments. Underlying details see [Runtime Environment Standard](specifications/runtime/index.md).

## Thread Model and Async

- Each plugin has one **QuickJS execution thread (single thread)**, events / commands / callbacks / timers all execute serially on this thread.
- Plugin code uses **`async` / `await`** for async: Blocking calls without `await` (e.g., `xxxSync`, synchronous property reads, synchronous `$send`) will **block the entire JS thread** — during which events / commands cannot be processed (may trigger `event.timeout` alert). **Event handlers / high-frequency scenarios must use async API** (`await xxx()`).
- Real IO (network, file, compression) executes on runtime side's `ioExecutor` / scheduler, doesn't occupy your JS thread.

## Globally Available (Built-in, No Import Needed)

| Capability                                                      | Description                                                                                                                                                        |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `$send(channel, payload)`                                       | **Only** JS→runtime communication entry (auto JSON serialization). Underlying `$_send` is internal implementation, **held by init.js closure and removed from global** — direct reference throws `ReferenceError`, can only use `$send`. |
| `fetch(url, init?)`                                             | HTTP client (Promise). Response is `Response`, providing `text()` / `json()` / `base64()` / `arrayBuffer()`. Requires `http:requestAsync` permission declaration.    |
| `TextEncoder` / `TextDecoder`                                   | **utf-8** encoding/decoding (Web semantics; environment requires existence). **Synchronous** API. utf-8 only, no gbk etc.                                           |
| `setTimeout` / `clearTimeout` / `setInterval` / `clearInterval` | Web timer semantics.                                                                                                                                               |
| `console.log/warn/error/info`                                   | Logging (auto-adds `[pluginName]` prefix).                                                                                                                         |
| `Promise`,`JSON`,`Map`/`Set`/`Symbol`/`Proxy`/`Reflect` etc.    | Standard ECMAScript.                                                                                                                                                |
| `Uint8Array.prototype.toBase64()` / `Uint8Array.fromBase64()`   | Engine native Base64 (ES2025 SecU8).                                                                                                                                |
| `__plugin`                                                      | Plugin metadata (name/version/author, read-only).                                                                                                                  |
| `__yeow*` (e.g., `__yeowInitCbs`, `__yeowGcQueue`)             | Runtime internal lifecycle hooks, generally **don't need direct contact**.                                                                                          |

> Most capabilities recommended to use via **yeow-api** (`player`/`world`/`fs`/`http`/`util`/`pdc`/…), not bare `$send`. Only globally `$send` / `fetch` / `TextEncoder` / `TextDecoder` / timers / `console` are "infrastructure".

## Differences from Browser Environment

| Browser Has                             | Yeow Runtime                                                        |
| --------------------------------------- | ------------------------------------------------------------------- |
| DOM / `window` / `document` / `canvas` | **None** — Pure logic execution environment, no pages               |
| `XMLHttpRequest` / `WebSocket`         | **No** global client; HTTP uses global `fetch` (or yeow-api `request`); |
| `crypto` (SubtleCrypto)                | **None**                                                            |
| `localStorage` / `IndexedDB`           | **None** — Persistence uses yeow-api `pdc` / `fs`                   |
| `atob` / `btoa`                        | **None** — Use native `Uint8Array.toBase64()` / `fromBase64()`      |

## Differences from Node.js Environment

| Node Has                       | Yeow Runtime                                                                          |
| ------------------------------ | ------------------------------------------------------------------------------------- |
| `require` / `module` (CJS)    | **None** — Yeow artifacts packaged by esbuild into single IIFE, source commonly uses `import` (ESM syntax, build-time bundled) |
| `process` / `global`           | **No** `process`; global uses `globalThis` / `__plugin`                               |
| `Buffer`                       | **None** — Always uses `Uint8Array` (Base64 native)                                   |
| `fs` / `http` / `path` native modules | **No Node modules** — Uses yeow-api's `fs` / `http` / `path`                     |
| `__dirname` / `__filename`     | **None** — Resource paths use yeow-api `assets`/`getAssetsPath` or `__plugin`          |
| Blocking synchronous IO        | **Not recommended** — Synchronous variants (`xxxSync`) block JS thread; high-frequency/event scenarios use async |

## Recommendations

- **Plugin development oriented**: Prioritize TS + yeow-api (API exports and global capability types complete).
- **Performance**: **Synchronous** UTF-8 encoding/decoding directly use `TextEncoder` / `TextDecoder` (best performance — small payloads pure JS direct conversion, zero roundtrips); large-scale **non-blocking** encoding/decoding use yeow-api's `stringToBytes` / `bytesToString` (async, ioExecutor execution).
- **Permissions**: Sensitive capabilities (HTTP, server files, plugin management etc.) need declaration in `yeow.config.json`, see [Permissions & Native Service Trust](permissions.md).