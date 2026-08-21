# Service Channel

Service registration, request, subscribe, and publish.

> **Permissions**: `service:registerNative` (spawning a native child process) is **denied by default**; the plugin must declare it in the `permissions` of `yeow.json`. The remaining service nodes (`register`/`request`/`subscribe`/`publish`/`response`/`awaitReady`/`registerNativeTerminate`) are allowed by default. An undeclared call returns `Permission denied: service:registerNative`.

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

When a request arrives, it is delivered to `onRequest` through the `cb` channel:

```json
{
  "_svc": "request",
  "requestId": "svcreq_1",
  "consumer": "<consumerPlugin>",
  "path": "/api/hello",
  "body": "{\"key\":\"value\"}"
}
```

The service side must reply via the `response` operation.

### `registerNative` — register a Native Service

- **Request**: `{ "t": "registerNative", "refName": "<name>", "platforms": {"windows": <PlatformConfig>}, "public": <bool> }`

`PlatformConfig` can be:
- **String**: `"native/win/app.exe"` — single file, backward compatible
- **Object (file)**: `{ "file": "native/win/app.exe" }` — explicit single file
- **Object (dir+entry)**: `{ "dir": "native/win/", "entry": "start.ps1" }` — extract the entire directory to the temp space and run the entry file
- **Return**: `{ "serviceId": "<id>" }` \| `{ "err": "<msg>" }` \| `{ "err": "<msg>", "serviceId": "<id>" }`

Behavior:
1. Select the corresponding binary path from `platforms` based on the current platform
2. Extract the binary from the plugin JAR's `assets/` to a temp directory
3. `spawn(binary, nativePort, serviceId)` to start the child process
4. Wait for the child process to connect over TCP and send a ready message

**Duplicate registration**: If `public: true` and a service with the same name already exists, it returns `{ "err": "Service already registered: <id>", "serviceId": "<id>" }` without spawning another process. The caller uses `serviceId` to connect to the existing service as a caller.

### `request` — request a service

- **Request**: `{ "t": "request", "serviceId": "<id>", "path": "<path>", "body": <obj>, "requestId": "<reqId>" }`
- **Return**: `null` (async)

`requestId` also serves as the callback ID. After the service processes the request, the result is delivered through this ID:

```json
{ "t": "cb", "p": "<requestId>", "r": <result> }
```

If `result` contains an `err` field, the request failed.

**Plugin Service handling**:
- The ServiceManager locates the plugin thread hosting the service
- Delivers the request through that thread's `onRequestCb`
- The service side replies via the `response` operation

**Native Service handling**:
- The ServiceManager sends the request to the child process over TCP
- The child process processes it and returns the response over TCP
- The ServiceManager converts the response format and delivers it to the consumer

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

- **Request**: `{ "t": "response", "requestId": "<reqId>", "body": <result> }`
- **Return**: `null`

Only used for Plugin Services. After the service side receives a request, it replies to the consumer with this operation.

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
