# Guide to Writing Yeow Dependency Packages

Yeow plugins can encapsulate shared logic and resources as npm packages for other plugins to reuse. Typical scenarios:

- Encapsulating Services (three package types: SDK / JS service / native service, see "[Encapsulating Service Packages (Three Types)](#encapsulating-service-packages-three-types)" below)
- Encapsulating Native Services (e.g., `yeow-image` packages `image-svc.exe` + registration/invocation logic)
- Shared utility functions (e.g., `yeow-command`, `yeow-server`)
- Shared configuration templates, resource files

---

## Package Structure

```
yeow-image/
├ package.json          ← Package metadata (key fields see below)
├ tsconfig.json         ← TS configuration (optional)
├ assets/               ← Resources distributed with package (exe, configs, images, etc.)
│  └ image-svc.exe
└ src/
   └ index.ts          ← Package entry (main/types points here)
```

## package.json

```json
{
    "name": "yeow-image",
    "version": "0.1.0",
    "type": "module",
    "main": "./src/index.ts",
    "types": "./src/index.ts",
    "files": ["src/", "assets/"],
    "peerDependencies": {
        "yeow-api": "^0.4.0"
    },
    "devDependencies": {
        "yeow-api": "^0.4.0"
    },
    "license": "MIT"
}
```

### Key Fields

| Field              | Description                                                                                                                                                                                        |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `main` / `types`  | Points to `src/index.ts` source code (built by main project esbuild directly bundling source, no pre-compilation needed)                                                                          |
| `files`            | **Must include `assets/`**, otherwise resources won't be distributed with package when published to npm                                                                                           |
| `peerDependencies` | Contract + **build-time identification marker**: `yeow-api` version range. Declares "consumer plugin must install yeow-api"; builder also uses this as identification condition (combined with `assets/`) to decide whether to package this package's resources into JAR and merge permissions (see "Dependency Identification" below) |
| `devDependencies`  | Development dependencies: Independent development `import 'yeow-api'` needs type definitions and IDE hints                                                                                       |

```bash
npm install --save-dev yeow-api
```

### Division of Two Declarations

| Declaration        | Scope                               | Purpose                                                                                                                                                                                                                                                                  |
| ------------------ | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `peerDependencies` | Consumer side (npm install + **build-time**) | Contract: Consumer's plugin must have yeow-api. When ranges don't overlap, npm installs independent yeow-api副本 for package — **multiple copies can safely coexist** (see "Build-time Automatic Handling" below). **Also serves as builder's dependency identification marker** — only packages with `assets/` and peer containing `yeow-api` participate in asset packaging and permission merging (missing declaration → resources silently not included in JAR) |
| `devDependencies`  | Development time (author side)      | Type/hints during independent development; **not published with package**, doesn't affect runtime                                                                                                                                                                                                                       |

**Version Strategy (two ranges, each with its own role, don't need to be same)**:

- `devDependencies` = **Development target version** — version used during development/type checking, can be narrow
- `peerDependencies` = **Widest compatible range** — npm 7+ installs **separate yeow-api copy** for consumer when ranges don't overlap (increases bundle size); wide range avoids duplicate installation. Old packages using only APIs common to all versions can write `^0.3.0 || ^0.4.0`; dependency package source code is checked by consumer's `tsc --noEmit` (build-time typecheck), as long as package only uses APIs available in all versions within range, wide range is naturally safe. **Even version incompatibility doesn't affect runtime**: Multiple yeow-api copies share lifecycle/event/GC global registry, can safely coexist (see below)

---

## Permission Declaration

Sensitive message nodes are denied by default, must be declared by **main project depending on this package** in `yeow.config.json`'s `permissions` field (automatically merged and computed into `computedPermissions` during build):

| Nodes Requiring Declaration                                           | Corresponding Capability in Package                                                                  |
| -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `fs:server.*` / `fs:outer.*` (or node-level `fs:server.readFile` etc.) | `fs.server.*` / `fs.outer.*` API (server root / any path); `fs.*` (plugin data directory) no declaration needed |
| `http:*` (or node-level `http:requestAsync` etc.)                    | `fetch`, `request`, HTTP server (`createServer`/`listen`)                                            |
| `service:registerNative`                                             | `registerNativeService` (spawn native child process)                                                 |

> `assets` channel (`assetsExtract` / `assetsExtractDir` extraction) **has no permission interception**: Extraction target is强制限定 to plugin data directory `plugins/<pluginName>/` (returns error if out of bounds), no declaration needed.

Rules:

- Node-level (`fs:server.readFile`), group wildcard (`fs:server.*`), and channel wildcard (`fs:*`) all work; undeclared call returns `Permission denied: <node>` (async API is Promise reject)
- Other nodes (`service:request`, `assets:read`, `fs.*` etc.) are allowed by default
- Permissions are read and fixed when plugin **loads**; after consumer modifies `permissions`, rebuild required and **full plugin reload** (`/yeow reload` or restart server) needed to take effect — if in development mode, hot reload updates permissions simultaneously

---

## Using getAssetsPath to Access Resources

Package internally uses `getAssetsPath()` to get JAR-internal paths for files in `assets/`:

```ts
// src/index.ts
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';   // Build-time virtual module

export const IMAGE_SERVICE = 'iyexin.image-svc.v1';

export async function registerImageService(): Promise<string> {
    const { serviceId, ready } = await registerNativeService(IMAGE_SERVICE, {
        windows: getAssetsPath('image-svc.exe'),
    });
    await ready();
    return serviceId;
}
```

> **Why import from `yeow-dev`?** `getAssetsPath` must know which dependency the calling code belongs to (to inject corresponding namespace id), and `yeow-dev` is a build-time virtual module — builder resolves by importer ownership. `yeow-dev` is published as empty package (can be not installed, type declarations provided by `yeow-api`).

**Build-time automatic handling:**

- Builder scans main project and all dependency packages' `assets/`, assigns unique namespace id (8-digit hex) to each dependency, copies content **as-is** to JAR `assets/<id>/`
- `getAssetsPath('image-svc.exe')` after build returns `"assets/<id>/image-svc.exe"` (real path)
- **No additional configuration needed** — build plugin automatically scans all dependency packages (see "Dependency Identification" below)

### Directory Resources

If entry script needs to reference sibling files in same directory (relative references within script), use directory-level path:

```ts
const svc = await registerNativeService('my-svc', {
    windows: { dir: getAssetsPath('native/'), entry: 'win/start.ps1' },
});
```

**No hashing**: All files **keep original names** (including nested subdirectories) — any relative references (`./`, `../`) within `assets/` (including cross-directory) are **always valid**, no more "directory should be self-contained" or "cross-top-level directory breaks" limitations.

### Directory Boundary

**`dir` points to top-level directory containing all dependencies**, `entry` uses relative sub-path:

```ts
// ✅ Extract entire native/, internal references complete
{ dir: getAssetsPath('native/'), entry: 'win/start.bat' }
```

**`{ file }` only extracts single file** — that file's references to other files in directory will fail (not extracted). For self-containment use `{ dir, entry }`.

---

## Build-time Automatic Handling

Main project `build.js` uses esbuild asset plugin (`yeow-assets.mjs`), **transparent** to dependency packages:

### 1. yeow-api Multiple Copy Coexistence (No Dedup)

Builder **does not force** `import 'yeow-api'` to unify to main project instance — installation decided by package manager according to semantic versioning rules: When peer ranges don't overlap (e.g., main project `^0.4.0` and dependency package `^0.3.0`), npm installs **independent yeow-api copy** for dependency package, two copies coexist in bundle.

> **Why safe**: yeow-api is pure encapsulation of underlying protocol (no breaking changes after protocol 1.0.0), multiple copies just repeat-encapsulate same protocol; lifecycle hooks (`onInit` / `onLoad` / `onUnload`) register in **shared global registry** (`__yeowInitCbs` etc. — read existing, never overwrite), event handlers and handle GC queue also shared — multiple copies' callbacks enter same registry, runtime INIT/LOAD dispatch **executes all**, won't mutually overwrite and lose (previously due to overwriting `__yeowInitCbs` manifested as `onLoad` not executing).

### 2. Asset Plugin

Scans main project + all dependency packages' `assets/`, deploys into JAR by namespace.

### Dependency Identification (node_modules scanning)

Builder scans `node_modules` top-level directory (including `@scope/name` two-level), identifies dependencies with `<name>-<version>` as key:

- **Identification condition**: Package has `assets/` directory, and `peerDependencies` contains `yeow-api` key
- **Main project**: Has `assets/` participates (always assigned id)
- **Same-name conflict**: Each dependency has independent namespace, same-name files don't overwrite each other
- **Compatibility**: npm / pnpm flat layout well supported; yarn hoisting differences may cause dependencies not in expected location, if issues please use npm or pnpm

### Dependency Package Permission Declaration (yeow.config.json)

Dependency packages can carry their own `yeow.config.json`, currently only need to declare `permissions`:

```json
{
    "permissions": ["fs:server.readFile", "http:*", "service:registerNative"]
}
```

**Each package only needs to declare its own permissions** — npm/pnpm packages are flat-distributed, node_modules top-level packages (direct dependencies and promoted transitive dependencies) all participate in calculation, but packages don't need to consider permissions needed by their dependencies: Permissions are unified by consumer's plugin during build, dependency packages just need to clearly declare their own permissions. Packages missing `yeow.config.json` or `permissions` field contribute no permissions.

### Final Permissions (computedPermissions)

Developer-declared `permissions` remain as-is, build automatically computes final effective permissions:

- **Merge**: Main project first, dependency packages appended in order, automatically deduplicated
- **Wildcard normalization**: When `X:*` exists (e.g., `fs:*`), other nodes in that channel (`fs:server.*`, `fs:server.readFile` etc.) automatically removed; when `X:segment.*` exists (e.g., `fs:server.*`), that prefix node (`fs:server.readFile`) automatically removed — wildcard already covers, no redundant declaration needed
- **`fs:*` expansion**: After declaring `fs:*`, computedPermissions automatically expands to `fs:outer.*, fs:server.*` (permission semantically equivalent — `plugin` segment nodes allowed by default, server/outer covered by respective wildcards) — gives developers and server admins clear perception of actual impact scope (any path + server root)
- **Write-back**: Result written to `yeow.config.json`'s `computedPermissions` field (preserving developer-declared `permissions`), packaged into `yeow.json` for runtime to read; build terminal prints synchronously

View calculation process and permission source distribution:

```bash
npm run permissions
```

```
── Permissions by source ─────────────────────────
  fs:server.*                 ← my-plugin-1.0.0
  fs:server.readFile          ← yeow-test-pkg-1.0.0
  http:*                      ← yeow-test-pkg-1.0.0
  service:registerNative      ← yeow-test-pkg-1.0.0

── Computed permissions (3) ─────────────────
  fs:server.*
  http:*
  service:registerNative
```

Each permission shows which package declared it, facilitating troubleshooting of permission gaps and redundancy.

### Native Service Trust Declaration (native)

When dependency package carries native service binary, recommend declaring `native` field in `yeow.config.json` to fix SHA-256:

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

- `files` are **this package**'s `assets/` binary original paths (same as paths used by `getAssetsPath`)
- Build automatically maps to packaged paths (`assets/<id>/...`) and computes SHA-256, writes to `yeow.json`'s `native` field; main project and dependency package declaring same `serviceId` merge (files consolidated)
- Runtime verifies hash when registering native service: mismatch → refuses to load (Promise reject); risk log printed regardless of declaration. See [Permissions & Native Service Trust](permissions.md#2-native-service-trust-declaration)

---

## Encapsulating Service Packages

**Three types** of Service encapsulation (inter-plugin communication / native extension) (SDK call encapsulation / JS service / native service) and combination patterns (JS facade + native engine) have been separated into independent article: [Encapsulating Service Packages](package-service.md). Native service's **trust declaration and approval mechanism** see [Permissions & Native Service Trust](permissions.md).

---

## Complete Example

Using `yeow-image` package as example (corresponding to `Yeow-Test/test/yeow-image`):

> **serviceId naming convention**: Public services may be loaded by multiple packages depending on it (same-name public service globally unique — duplicate registration refused, caller uses `err.serviceId` for degraded access, see above type 2). To avoid conflicts between different authors' packages, serviceId should specify `author.serviceName.version`, e.g., `iyexin.image-svc.v1`:

```ts
// src/index.ts
import { registerNativeService, serviceRequest } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

export const IMAGE_SERVICE = 'iyexin.image-svc.v1';

/**
 * Supported platforms: linux / windows / macos, subdivided by architecture (see "Supported Platforms" table below)
 */
export interface RenderResult {
    base64?: string;
    err?: string;
}

export interface ImageRenderer {
    serviceId: string;
    render(width: number, height: number, pixels: Uint8Array): Promise<RenderResult>;
}

export async function initRenderer(): Promise<ImageRenderer> {
    const { serviceId, ready } = await registerNativeService(IMAGE_SERVICE, {
        'linux-x64':   getAssetsPath('native/linux-x64/image-svc'),
        'linux-arm64': getAssetsPath('native/linux-arm64/image-svc'),
        'windows-x64': getAssetsPath('native/windows-x64/image-svc.exe'),
        'macos-x64':   getAssetsPath('native/macos-x64/image-svc'),
        'macos-arm64': getAssetsPath('native/macos-arm64/image-svc'),
    });
    await ready();

    return {
        serviceId,
        async render(width, height, pixels) {
            const base64 = pixels.toBase64(); // ES2026 native
            return serviceRequest(serviceId, '/imageRender', {
                width,
                height,
                base64,
            }) as Promise<RenderResult>;
        },
    };
}
```

**Supported platforms**: Keys support `operatingSystem` or `operatingSystem-architecture` granularity, exact match (including architecture) prioritized, falls back to operating system if not found:

```
assets/
├ native/
│  ├ linux-x64/image-svc
│  ├ linux-arm64/image-svc
│  ├ windows-x64/image-svc.exe
│  ├ macos-x64/image-svc
│  └ macos-arm64/image-svc
```

| Key                                   | Description                   |
| ------------------------------------- | ----------------------------- |
| `windows` / `windows-x64`             | Windows (x64 or any architecture) |
| `linux` / `linux-x64` / `linux-arm64` | Linux x86_64 / ARM64          |
| `macos` / `macos-x64` / `macos-arm64` | macOS Intel / Apple Silicon   |

> **Recommend providing at least `windows-x64` + `linux-x64` + `linux-arm64`**: Most Paper servers deploy on Linux x64 VPS, Linux ARM (Raspberry Pi/NAS/ARM cloud host), or Windows x64. When missing current platform configuration, registration returns error `No binary for platform: <os> (<os>-<arch>)`.

Consumer in main plugin:

```json
// Main plugin package.json
{
    "dependencies": {
        "yeow-api": "^0.4.0",
        "yeow-image": "^0.0.1"   // Example
    }
}
```

```ts
// Main plugin src/index.ts
import { initRenderer } from 'yeow-image';

onLoad(async () => {
    const renderer = await initRenderer();
    const result = await renderer.render(2, 2, pixels);
});
```

> **Dependency package declares permissions**: yeow-image should declare `permissions: ['service:registerNative']` in its own `yeow.config.json`

> **Encapsulation recommendation**: Encapsulate all details like `serviceId`, ready-wait etc. within package, externally only expose high-level operation functions (e.g., `renderer.render()`). Consumers don't need to know about Native Service's existence.

---

## Checklist

- [ ] `files` includes `assets/`
- [ ] `main` / `types` points to `src/index.ts`
- [ ] `peerDependencies` declares yeow-api version range (widest compatible range; missing declaration → assets/permissions not participating in build)
- [ ] `devDependencies` declares development target version yeow-api (independent development type checking)
- [ ] When using fs/http/registerNative, declare in own `yeow.config.json`
- [ ] Resources obtained via `getAssetsPath()`, not hardcoded