# Service Mechanism

> Service Mechanism (from a mechanism perspective): Plugin Service (inter-plugin communication) and Native Service (native capability extension). For **API usage**, see [Service API](/api/service); for **wrapping services in dependency packages**, see [Dependency Packages Wrapping Services](/package-service); for **subprocess TCP protocol**, see [Native Service Specification](/specifications/native-service/index).

## Service Mechanism (Service)

### Plugin Service — Inter-Plugin Communication

Plugin A registers a service, Plugin B calls it:

```
Plugin B                         ServiceManager                 Plugin A
  svc.request(path, { body })
    → service channel request
      → registry locates the owning plugin of the service
        → delivers {_svc:"request", path, headers, body} via onRequestCb callback
          → Plugin A's onRequest(path, body) handles it
          → $send('service', {t:"response", requestId, headers, body})
      → respond(requestId, consumer)
    → Plugin B's Promise resolves
```

**Publish/Subscribe**: The service provider publishes events using `svc.publish(eventPath, body)`, and subscribers receive them using `svc.subscribe(eventPath, handler)`. `token` is the publish authentication credential (returned upon registration), held only by the owner `PluginService` handle.

### Native Service — Native Capability Extension

Plugins register via `registerNativeService`, and the runtime extracts the executable and spawns a subprocess, returning a `NativeService` handle:

```
const svc = await registerNativeService(refName, platforms)
  → Selects the binary based on the current platform (os + arch, exact match falls back to os)
  → Extracts from JAR assets/ to a temporary directory (namespace path resolved via getAssetsPath)
  → spawn(binary, nativePort, serviceId)
  → Subprocess connects via TCP → sends {"type":"ready"} → svc.ready() resolves
  → Requests go through a framed protocol (header JSON + raw body): request → response
```

- **Platform granularity**: `windows-x64` / `linux-arm64` etc., exact match takes priority, falls back to `windows` / `linux`
- **Single instance**: When `isPublic: true`, only one process is started per service name (one registration is retained); duplicate registrations are rejected (returning `err` + `serviceId`); callers use `err.serviceId` to join the existing service as the caller identity
- **Trustworthiness and approval**: Native binaries support SHA-256 trustworthiness declarations; plugins requesting native service permission load directly by default with an untrusted warning — see [Permissions and Native Service Trustworthiness](/permissions)
- **Protocol**: see [Native Service Specification](/specifications/native-service/index)

For API usage, see [Service API](/api/service).
