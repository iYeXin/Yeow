# Permissions & Native Service Trust

> **Complete reference for the security topic from the plugin developer perspective**. Quick start: **No permission declaration needed when only reading/writing your own plugin data directory**; declare here when you need sensitive capabilities (server files, HTTP, native processes, resource extraction). Platform specification (implementer perspective) permission model see [Platform Specification · Permission Model](specifications/README.md#permission-model).

## 1. Declarative Permissions

Yeow implements **declarative permissions** for **sensitive message nodes**: plugins declare required permissions in `yeow.config.json`'s `permissions` (automatically computed during build and written to `yeow.json`, read by runtime and **fixed** — cannot be changed after loading):

```json
{
    "name": "my-plugin",
    "permissions": [
        "fs:server.*",
        "http:requestAsync",
        "service:registerNative"
    ]
}
```

### Declaration Required by Default (undeclared calls return errors)

| Permission Node               | Coverage Range                                                                                      |
| ----------------------------- | --------------------------------------------------------------------------------------------------- |
| `fs:server.*` / `fs:outer.*` | fs channel `server` / `outer` prefix nodes (server root / any path); `fs:plugin.*` nodes (plugin data directory) **no declaration needed** |
| `http:*`                      | All HTTP operations (`http:request`, `http:requestAsync`, `http:listen`, `http:respond`, `http:close`) |
| `service:registerNative`      | Register native service (spawn child process)                                                      |

> **assets channel has no permission interception**: `assets` only reads packaged resources, or extracts to **this plugin's data directory** (target强制限定, returns error if out of bounds), so no permission declaration needed.

> **Node Concept**: Permissions are only considered by **message nodes** (e.g., `fs:plugin.readFile`, `fs:server.readFile`). Segments in node names (`plugin` / `server` / `outer`, `player` in `task:player.get`) are business/access scope naming, **not hierarchy** — permission matching does not consider naming segment meaning.

### Granularity Rules

- **Node-level**: Declaring `fs:server.readFile` only grants that node, other fs nodes remain denied
- **Group wildcard**: Declaring `fs:server.*` grants all nodes with `server` prefix
- **Channel wildcard**: Declaring `fs:*` grants entire fs channel (including server/outer)
- Undeclared call → Returns error (`Permission denied: <node>`), async API presents as Promise reject
- Other message nodes (e.g., `service:request`, `assets:read`) are allowed by default, no declaration needed

> **⚠ Permission Recommendation**: Directly declaring `fs:*` is **dangerous and unprofessional**. When only reading/writing your own plugin's config files, **no fs permission declaration needed** (`fs:plugin.*` nodes are allowed by default). When you truly need to access server files, **declare as precisely as possible** (e.g., `fs:server.readFile`, `fs:outer.systemPaths`), not group or channel wildcards.

> [!WARNING]
> Global `fetch` depends on `http:requestAsync` — when http permission is not declared, `fetch` returns `Permission denied: http:requestAsync`. Ensure you've declared `"http:*"` or `"http:requestAsync"` before using `fetch` / `request`.

> [!WARNING]
> **HTTP server requires both `http:listen` + `http:respond` nodes** — declaring only `http:listen` while missing `http:respond` causes server to start, requests reach callbacks, but `respond` is denied → response never sent → **client request hangs and times out** (curl timeout / CLOSE_WAIT, no anomalies in server logs). Declare `"http:*"`, or declare both `"http:listen"` and `"http:respond"`.

> After modifying `permissions`, rebuild required — development mode hot reload will **reload permissions together** (build-time `computedPermissions` refreshes with hot reload message); production environment requires full plugin reload (`/yeow reload` or restart server).

> **Unified gate**: All message nodes (`channel:op`) pass through a unified permission gate; `task:*` is owned by default (no declaration needed). A Worker can tighten this via `permissions.allow` / `permissions.deny` at `createWorker` time (see [Worker API · Permission Overrides](api/worker.md)): by default it inherits the main plugin's permissions, `deny` has the highest priority, and `allow` is a whitelist that **cannot escalate** (permissions not declared by the main plugin are ineffective via `allow`).

### Final Permissions (computedPermissions)

Build automatically merges declarations from main project and dependency packages (dedup + wildcard normalization: `fs:*` overrides `fs:server.*`, `fs:server.readFile` etc.; `fs:server.*` overrides `fs:server.readFile`), writes results to `yeow.config.json`'s `computedPermissions` field and packages into `yeow.json`. Declaring `fs:*` is **automatically expanded** to `fs:outer.*, fs:server.*` (semantically equivalent, giving server admins clear perception of impact scope).

Use `npm run permissions` to view calculation process and permission source distribution (which package each permission comes from):

```
── Permissions by source ─────────────────────────
  fs:server.*                 ← my-plugin-1.0.0
  http:*                      ← yeow-test-pkg-1.0.0

── Computed permissions (2) ─────────────────
  fs:server.*
  http:*
```

**Console Verification**: When runtime loads plugin, it prints permission list to server console (`Loaded plugin: <name> ... — permissions: ...`).

## 2. Native Service Trust Declaration (Mandatory)

Plugins (or dependency packages) **must** declare the `native` field in `yeow.config.json`, fixing native service binaries' SHA-256 — at build time the builder walks the main project and all dependency packages, computes the packaged hash under each namespace and merges the result into `yeow.json`; at runtime it stores this as plugin metadata and enforces verification when registering native services.

```json
{
    "native": [
        {
            "serviceId": "iyexin.image-svc.v1",
            "files": ["native/win/image-svc.exe"],
            "source": "https://github.com/iyexin/image-svc"
        }
    ]
}
```

- `serviceId`: Service name when registering `registerNativeService`; `files`: Original paths of binaries under **this package**'s `assets/`; `source`: Source link (optional)
- Main project and dependency packages each declare their own `native`; **the same `serviceId` merges during build** (files consolidated, keeping each namespace's paths)
- Build artifact `yeow.json`'s `native` format: `[{ "serviceId": "...", "files": [{ "<packaged path>": "<sha256>" }, ...], "source": "..." }]`
- **Single-file mode only** (`string` / `{file}`); directory mode (`{dir, entry}`) has been removed

**Mandatory declaration (build + load)**:

- **Build**: If the `service:registerNative` (or `service:*`) permission is requested but the merged `native` manifest is empty → **build fails**; missing declared files also fail
- **Load**: If that permission is requested but the manifest is empty → **loading is refused**
- **Plugins not requesting the `service:registerNative` permission do not need to declare `native`**

**Runtime behavior**:

- serviceId declared + binary path declared + SHA-256 matches → registration succeeds (log shows verification passed)
- serviceId undeclared / binary path undeclared / SHA-256 mismatch → **registration refused**, `registerNativeService`'s Promise rejects
- **Declaration ≠ trusted**: Regardless of declaration, a risk log is printed at registration (treated as untrusted) — declaration only pins content, it does not mean the source is trusted

### Untrusted Service Switch (Load with Warning by Default)

**All native services are treated as untrusted by default** (even with hash declaration). Plugins requesting the `service:registerNative` permission load normally, but the console prints a prominent untrusted warning (stating whether SHA-256 is pinned and whether a `native` declaration exists):

- Allowed to load → Plugin runs normally (`onLoad` executes), console warns it will run untrusted binaries as child processes
- **Configuration**: `plugins/Yeow/runtime/config.yml`'s `native-service-allow-untrusted` (default `true`; `false` = plugins requesting the `service:registerNative` permission are **refused loading**, console points to this config key). When an existing config lacks the field, the runtime merges defaults on load and writes back (smooth upgrade)
- **Runtime directory has fs write protection** — plugins cannot modify files in it via fs API (`config.yml`)

> **Developers**: Error handling and degradation examples (distinguishing "service already exists / executable tampered") see [Service API](api/service.md) and [Encapsulating Service Packages](package-service.md).

> **Future Outlook**: Online safety check (comparing against the officially maintained safety list) is planned — binaries whose hash hits the official safety list will load without this warning (in the current version all binaries are treated as untrusted and warned regardless).

## 3. Related Documentation

- **Platform Specification · Permission Model** (runtime implementer perspective): [specifications/README.md#permission-model](specifications/README.md#permission-model)
- **Dependency Package Permission Declaration** (how npm packages declare): [Writing Dependency Packages - Permissions](package-author.md)
- **Runtime Configuration** (`native-service-allow-untrusted` etc.): [Runtime Operations - Configuration](operations.md#runtime-configuration)