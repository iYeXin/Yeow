# Service Channel

Service registration, query, unregistration, request, subscribe, and publish.

> **Permissions**: `service:registerNative` (spawning a native child process) is **denied by default**; the plugin must declare it in the `permissions` of `yeow.json`. The remaining service nodes (`register`/`registerNative`/`info`/`unregister`/`request`/`subscribe`/`unsubscribe`/`publish`/`response`/`awaitReady`/`registerNativeTerminate`) are allowed by default. An undeclared call returns `Permission denied: service:registerNative`.

## Overview

The `service` channel implements inter-plugin communication (Plugin Service) and native capability extension (Native Service). Both kinds of services are completely identical from the consumer's perspective.

## Operations List

### `register` — register a Plugin Service

- **Request**: `{ "t": "register", "refName": "<name>", "onRequest": "<cbId>", "public": <bool> }`
- **Return**: `{ "serviceId": "<id>", "token": "<tok>" }` \| `{ "err": "<msg>", "serviceId": "<id>" }`

| Field       | Required | Description                                                                          |
| ----------- | -------- | ------------------------------------------------------------------------------------ |
| `refName`   | Yes      | Service reference name                                                               |
| `onRequest` | Yes      | Callback ID that handles requests (requires `persistent: true`)                      |
| `public`    | Yes      | `true` is public (serviceId = refName), `false` is private (serviceId = refName_random) |

The returned `token` is used for authenticating `publish` calls and is **only returned on the first registration**.

**Duplicate registration**: If `public: true` and a service with the same name already exists, it returns `{ "err": "Service already registered: <id>", "serviceId": "<id>" }`. `onRequest` has no effect; the caller should use the returned `serviceId` to connect to the existing service as a caller (request / subscribe); the token is not exposed externally.

When a request arrives, it is delivered to `onRequest` through the `cb` channel (`body` is the native JSON value; a binary request is a `{ "contentType", "headers", "base64" }` carrier object):

```json
{
  "_svc": "request",
  "requestId": "svcreq_1",
  "consumer": "<consumerPlugin>",
  "path": "/api/hello",
  "headers": {"content-type": "application/json"},
  "contentType": "application/json",
  "body": {"key": "value"}
}
```

The service side must reply via the `response` operation.

### `registerNative` — register a Native Service

- **Request**: `{ "t": "registerNative", "refName": "<name>", "platforms": {"windows": <PlatformConfig>}, "public": <bool> }`

`PlatformConfig` supports **single file only** (directory mode has been removed):
- **String**: `"native/win/app.exe"`
- **Object (file)**: `{ "file": "native/win/app.exe" }`
- **Return**: `{ "serviceId": "<id>" }` \| `{ "err": "<msg>" }` \| `{ "err": "<msg>", "serviceId": "<id>" }`

Behavior:
1. **Mandatory verification**: `refName` and the selected binary's packaged path must both be declared in the plugin package's `native` manifest (otherwise returns err); a SHA-256 mismatch is also rejected
2. Select the corresponding binary path from `platforms` based on the current platform
3. Extract the binary from the plugin JAR's `assets/` to a temp directory
4. `spawn(binary, nativePort, serviceId)` to start the child process
5. Wait for the child process to connect over TCP and send a ready message

**Duplicate registration**: If `public: true` and a service with the same name already exists, it returns `{ "err": "Service already registered: <id>", "serviceId": "<id>" }` without spawning another process. The caller uses `serviceId` to connect to the existing service as a caller.

### `info` — query whether a service exists

- **Request**: `{ "t": "info", "serviceId": "<id>" }`
- **Return**: `{ "exists": <bool>, "kind": "plugin" | "native" | null }` (**synchronous return, no `cb`**)

`exists` indicates whether the service exists; `kind` is the service type, or `null` when it does not exist. The JS-side `getService` / `hasService` are implemented on top of this operation.

### `unregister` — unregister a service

- **Request**: `{ "t": "unregister", "serviceId": "<id>", "token": "<tok>"? }`
- **Return**: `{ "ok": true }` \| `{ "err": "<msg>" }` (**synchronous return**)

- **A Plugin Service must carry the owner `token`** (returned at registration); if missing or mismatched it returns `{ "err": "Permission denied: unregister requires the owner token for plugin service <id>" }`
- **A Native Service needs no `token`, but the caller must be the owner plugin**; otherwise it returns `{ "err": "Permission denied: only the owner may unregister native service <id>" }`
- If the service does not exist it returns `{ "err": "Service not found: <id>" }`

After unregistering, the service's subscriptions are cleaned up and pending requests are rejected (`Native service <id> terminated (unregistered)`); a Native child process is terminated and the owner's termination hook fires.

### `request` — request a service

- **Request**: `{ "t": "request", "serviceId": "<id>", "path": "<path>", "headers": {...}?, "contentType": "<ct>?", "body": <value>, "bodyEncoding": "base64"?, "timeout": <ms>?, "requestId": "<reqId>" }`
- **Return**: `null` (async)

`headers` is an **app-level key/value map** (e.g. `{"content-type": "application/json", "x-trace-id": "..."}`); `contentType` is a convenience alias for `headers['content-type']`, defaulting to `application/json`, and `body` is a JSON value. For binary: `contentType: "application/octet-stream"`, `body` is a base64 string and `bodyEncoding: "base64"` (the runtime decodes it and forwards raw bytes).

`timeout` (milliseconds) is optional; it defaults to the runtime config `service-request-timeout-ms` (30000). On timeout the consumer receives `respond(requestId, consumer, { "err": "Service request timed out after <ms>ms: <id>" })`.

`requestId` also serves as the callback ID. After the service processes the request, the **response body** is delivered through this ID:

```json
{ "t": "cb", "p": "<requestId>", "r": { "contentType": "<ct>", "headers": {...}, "base64": "<response bytes base64>" } }
```

If `r` contains an `err` field, the request failed. The JS side wraps the callback result in a fetch-style `ServiceResponse` (`json()` / `text()` / `bytes()` / `arrayBuffer()` / `base64()`; `headers` is the response header map and `contentType` is a convenience view of it).

**Plugin Service handling**:
- The ServiceManager locates the plugin thread hosting the service
- Delivers the request through that thread's `onRequestCb` (JSON delivered natively; binary as a `{contentType, headers, base64}` carrier object)
- The service side replies via the `response` operation (the JSON response body is serialized to bytes by the runtime)

**Native Service handling**:
- The ServiceManager sends the request to the child process over the **framed protocol** (header JSON + raw body, see [Native Service Specification](../native-service/index.md)); the frame header looks like `{"type":"request","id":"...","path":"...","headers":{...},"contentType":"..."}`
- The child process returns the response with the same protocol (frame header `{"type":"response","id":"...","headers":{...},"contentType":"..."}` + raw bytes)
- The ServiceManager delivers the response body (bytes + headers + contentType) to the consumer

**Pending requests**: When a service terminates while a request is pending (connection closed / process exited / unloaded / runtime shutdown), the runtime rejects all of that service's pending requests: `respond(requestId, consumer, { "err": "Native service <id> terminated (<reason>)" })`, and the consumer's Promise rejects.

### `registerNativeTerminate` — register a termination hook (service owner)

- **Request**: `{ "t": "registerNativeTerminate", "serviceId": "<id>", "cb": "<cbId>" }`
- **Return**: `"true"`

Only the plugin that owns the service may register this; calling it again overwrites the old callback. When the service terminates, it is delivered through the `cb` channel (**fires only once**):

```json
{ "t": "cb", "p": "<cbId>", "r": { "serviceId": "<id>", "reason": "<reason>", "exitCode": <int?>, "output": "<text?>" } }
```

`reason` values: `disconnected` (TCP disconnected) / `exited` (process exited) / `unregistered` (unloaded) / `shutdown` (runtime shutdown). When multiple termination events occur at once (e.g. process exit accompanied by connection disconnect), it is only delivered once.

### `awaitReady` — wait for a native service to be ready

- **Request**: `{ "t": "awaitReady", "serviceId": "<id>", "cb": "<cbId>" }`
- **Return**: `null` (async)

`cbId` corresponds to a temporary callback. When ready, `{ "ok": true }` is delivered through that callback; on failure, `{ "err": "<msg>" }` is delivered.

Only used for Native Services. Behavior at call time:

| State at call time                    | Behavior                                                   |
| ------------------------------------- | ---------------------------------------------------------- |
| Already ready                         | Immediately `respond(cbId, { ok: true })`                  |
| Waiting for readiness (process alive) | Registered to the wait queue, resolved upon receiving the `ready` TCP message |
| Process already exited                | Immediately `respond(cbId, { err: "Native service xxx exited..." })` |
| Service unloaded                      | Rejected at purgePluginServices / shutdown                 |

The wait queue is consumed when a ready message is received in `handleNativeSocket` (after the `nativeSocket` binding completes) or when the monitor thread detects that the process has exited.

### `response` — reply to a request (service side)

- **Request**: `{ "t": "response", "requestId": "<reqId>", "headers": {...}?, "contentType": "<ct>?", "body": <result>, "bodyEncoding": "base64"? }`
- **Return**: `null`

Only used for Plugin Services. After the service side receives a request, it replies to the consumer with this operation. `headers` is the app-level response header map (`contentType` is a convenience alias for its `content-type`); `contentType` defaults to `application/json` (`body` is a JSON value serialized to bytes by the runtime); to return binary use `bodyEncoding: "base64"`, `body` as a base64 string and `contentType: "application/octet-stream"`.

### `subscribe` — subscribe to events

- **Request**: `{ "t": "subscribe", "serviceId": "<id>", "eventPath": "<path>", "cb": "<cbId>" }`
- **Return**: `"true"`

`cb` is registered with `persistent: true`. When an event is published, it is delivered through this callback:

```json
{ "t": "cb", "p": "<cbId>", "r": { "serviceId": "<id>", "eventPath": "<path>", "body": <obj> } }
```

### `unsubscribe` — unsubscribe

- **Request**: `{ "t": "unsubscribe", "serviceId": "<id>", "eventPath": "<path>" }`
- **Return**: `"true"`

On plugin unload / hot-reload, the Runtime automatically unsubscribes all of that plugin's subscriptions.

### `publish` — publish an event (service side)

- **Request**: `{ "t": "publish", "token": "<tok>", "eventPath": "<path>", "body": <obj> }`
- **Return**: `"true"`

After the Runtime verifies the `token` validity, it delivers the event to all subscribers matching `eventPath`.

`token` is the service side's private credential, only returned on the first registration. **Publishing events is an internal responsibility of the service side** — external callers should not `publish` directly, but should trigger the service via `request` and let the service side decide whether to publish according to its business logic. Implementation-wise, re-registering the same public service does not return a token.

`publish` messages sent by a Native Service over TCP do not need a `token` (the Runtime automatically associates the token corresponding to the service).

---

## Lifecycle

- Plugin unload / hot-reload → Runtime calls `purgePluginServices(name)` to clean up all of that plugin's registrations (services, subscriptions, pending requests), and rejects that plugin's pending `awaitReady` and consumer pending requests
- Plugin Services naturally become invalid when their plugin thread is destroyed
- Native Service child processes are terminated with `destroyForcibly()`
- On service termination (disconnect / exit / unload / shutdown), the owner plugin's `registerNativeTerminate` hook is triggered (once), and all of that service's pending requests are rejected
