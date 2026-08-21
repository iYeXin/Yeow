# Java Plugin Calling Yeow Plugin Services (Experimental)

Yeow runtime (Paper plugin) exposes integration interfaces to **other Java plugins**: calling services registered by Yeow plugins, subscribing to service events, and submitting game tasks (adapter entry point).

> Applicable when: Java plugin `plugin.yml` declares `depend: [Yeow]`, obtains `YeowRuntime` instance via `Bukkit.getPluginManager().getPlugin("Yeow")`.

## Calling Services (Request-Response)

```java
import yeow.YeowRuntime;
import com.google.gson.JsonObject;

var rt = (YeowRuntime) Bukkit.getPluginManager().getPlugin("Yeow");

JsonObject body = new JsonObject();
body.addProperty("cmd", "status");

rt.requestService("my-plugin.svc.v1", "/status", body, result -> {
    // result: gson-parsed object (Map/List/String/Number/Boolean)
    // Received {"err": <message>} when service doesn't exist / request fails
});
```

- `serviceId`: Service ID registered by a Yeow plugin via `registerService`
- `path`: Service-internal path (received by JS side `onRequest(path, body)`)
- Callback is invoked on the **runtime thread** (not the main thread); for main thread operations, forward via `Bukkit.getScheduler().runTask(...)`
- Internal implementation: `ServiceManager.requestJava` — request is routed to the service owner via the same path as JS callers; response callback is delivered directly

## Subscribing to Service Events

```java
AutoCloseable sub = rt.subscribeService("my-plugin.svc.v1", "status", payload -> {
    // payload: { "serviceId": "...", "eventPath": "...", "body": {...} }
});

sub.close();   // Unsubscribe
```

- `eventPath`: Event path published by the service via `publish(token, eventPath, body)` (exact match)
- Callback payload is the event data object (`serviceId` / `eventPath` / `body`)
- The same callback reference can subscribe to different services/paths; `close()` removes by callback reference

## Submitting Game Tasks

Both adapters and Java plugins can submit game tasks (equivalent entry point to `$_send('task', ...)`):

```java
String result = YeowRuntime.inst().submitTask(entity, json);   // Synchronous (no cb) / Asynchronous (with cb)
```

- Synchronous: Blocks and returns result JSON; Asynchronous: Returns null immediately, result delivered via `entity.postMessage` callback `{"t":"cb","p":"<cbId>","r":<data>}`
- `entity`: Plugin entity (for JS plugins it's `PluginThread`; Java plugin integration usually doesn't need to submit tasks — when needed, construct a custom `PluginEntity` per the [Adapter Specification](adapter/index.md))

## Constraints

- Must ensure the Yeow runtime instance exists
- Callbacks are invoked on the runtime thread — **do not** call Paper main thread APIs directly in callbacks (except thread-safe ones); forward to main thread when necessary
- Request/subscription lifecycles are not automatically cleaned up on plugin unload (Java plugins manage `AutoCloseable` themselves)
