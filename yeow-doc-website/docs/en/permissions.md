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

## 2. Native Service Trust Declaration

Plugins (or dependency packages) can declare `native` field in `yeow.config.json`, **fixing native service binary's SHA-256** — automatically computed during build and written to `yeow.json`; runtime verifies when registering native service, if hash doesn't match → **refuses to load** (Promise reject).

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

- `serviceId`: Service name when registering `registerNativeService`; `files`: Original paths of binaries under this package's `assets/`; `source`: Source link (optional)
- Both main project and dependency packages can declare; **same `serviceId` merges during build** (files consolidated)
- Build artifact `yeow.json`'s `native` format: `[{ "serviceId": "...", "files": [{ "<packaged path>": "<sha256>" }, ...], "source": "..." }]`

**Runtime Behavior**:

- Declaration exists and matches → Normal load (log shows verification passed)
- Declaration exists but doesn't match (file replaced/tampered) → **Refuses to load service**, `registerNativeService` Promise rejects
- **Regardless of declaration**, loading native service always prints risk log: undeclared → Warns "no trusted SHA-256 declaration, treated as untrusted"; declared → Shows verification result
- **Trust declaration only valid for single-file mode** (`string` / `{file}`); directory mode (`{dir, entry}`) currently doesn't support declaration and verification

### Approval (Required by Default)

**By default, plugins declaring native services require approval to load** (currently all native services are treated as unsafe, even with hash declaration). When plugin loads and detects `native` declaration without approval → **refuses to load this plugin**, server console prints prominent prompt (one-time approval code):

```
/yeow approve <code>    # code is 6-digit 36-hex one-time code (only visible in console)
                        # After approval, automatically loads rejected plugin, no manual reload needed
```

- Refuse to load → Plugin doesn't run (`onLoad` won't execute), console prompt includes `/yeow approve <code>`
- **One-time code mechanism**: Each time loading is refused, generates random 6-digit 36-hex code (only appears in console log) — plugin itself isn't loaded, cannot read logs then `dispatchCommand` to auto-approve; code is invalidated after use
- **Configuration**: `plugins/Yeow/runtime/config.yml`'s `native-service-require-approval` (default `true`; `false` = approve by default). **Runtime modification takes effect immediately** (config.yml is trusted source)
- **Approval Storage**: `plugins/Yeow/runtime/approve.json` (plugin name → approval timestamp). **Runtime directory has fs write protection** — plugins cannot modify files in it via fs API (config.yml / approve.json)

> **Developers**: Error handling and degradation examples (distinguishing "service already exists / executable tampered") see [Service API](api/service.md) and [Encapsulating Service Packages](package-service.md).

> **Future Outlook**: Yeow official or community may maintain a list of known safe SHA-256 hashes — if binary hash matches the list, plugin may be marked as safe when published, no risk prompt or approval required when loading.

## 3. Related Documentation

- **Platform Specification · Permission Model** (runtime implementer perspective): [specifications/README.md#permission-model](specifications/README.md#permission-model)
- **Dependency Package Permission Declaration** (how npm packages declare): [Writing Dependency Packages - Permissions](package-author.md)
- **Runtime Configuration** (`native-service-require-approval` etc.): [Runtime Operations - Configuration](operations.md#runtime-configuration)