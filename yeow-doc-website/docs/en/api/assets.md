# Assets API

```js
import { getAssetsPath } from 'yeow-dev';   // Build-time virtual module
import { assets } from 'yeow-api';
```

Files under `assets/` directory are automatically packaged into JAR during build, read via this API at runtime.

> `getAssetsPath` imported from **`yeow-dev`** (build-time virtual module), not `yeow-api`: It injects namespace by caller's belonging dependency, only builder knows which package current code belongs to. `yeow-dev` published as empty package (can be not installed, type declarations provided by `yeow-api`).

## getAssetsPath(path)

Get JAR-internal path for resource files under `assets/` directory. Returned path can be passed to all APIs needing string path.

```ts
getAssetsPath('icon.png'): string
// → "assets/9f2c8a41/icon.png"

getAssetsPath('native/win/svc.exe'): string
// → "assets/9f2c8a41/native/win/svc.exe"
```

**Path rules**: During build each dependency (main project and qualifying npm packages) assigned a **unique namespace id** (8-digit hex), its `assets/` content **copied as-is** to `assets/<id>/` — files **not renamed**, `getAssetsPath` just adds `assets/<id>/` prefix before passed path.

- **File path** (e.g., `native/win/svc.exe`) → `assets/<id>/native/win/svc.exe`
- **Directory path** (ending with `/`, e.g., `native/win/`) → `assets/<id>/native/win/` (preserves trailing slash)
- Paths normalized (`./`, `..` resolved, won't escape `assets/<id>/`)

### Relative References (No Limitations)

Because files **not hash-renamed**, any relative references within `assets/` (including cross-directory) — whether from referenced file's content (config, scripts, `../` sibling references) or `{ dir, entry }` native services — are **always valid**. No more "directory should be self-contained" or "cross-top-level directory breaks" limitations:

```ts
// Layout: assets/native/win/{start.bat, app.js, modules/moduleA.js}
getAssetsPath('native/')           // → "assets/<id>/native/"
getAssetsPath('native/win/')       // → "assets/<id>/native/win/"
getAssetsPath('native/win/app.js') // → "assets/<id>/native/win/app.js"
getAssetsPath('native/win/modules/moduleA.js') // → "assets/<id>/native/win/modules/moduleA.js"
```

### Namespace Isolation

Each dependency's assets has independent `<id>` namespace, **same-name files don't conflict** (between main project and dependency packages, between dependency packages) — no more "dependency packages don't overwrite main project" rule needed.

### Dependency Identification (node_modules scanning)

Builder scans `node_modules` top-level directory (including `@scope/name`), identifies dependencies with `<name>-<version>` as key:

- **Identification condition**: Package has `assets/` directory, and `peerDependencies` contains `yeow-api` key
- **Main project**: Has `assets/` participates (always assigned id)
- **Compatibility**: npm / pnpm flat layout well supported; yarn hoisting differences may cause dependencies not in expected location, if issues please use npm or pnpm

### Directory Boundary

**`{ file }` mode only extracts single file** — that file's relative references to other files in directory will fail. For maintaining internal references use `{ dir, entry }` mode:

```ts
// ✅ dir points to top-level directory containing all dependencies, entry uses relative sub-path
{ dir: getAssetsPath('native/'), entry: 'win/start.bat' }
```

> During build esbuild intercepts `yeow-dev` virtual module: Scans each dependency's `assets/`, copies as-is to `dist/.assets/<id>/` (or `dist/.dev/.assets/<id>/`), injects namespace id by importer ownership, finally packages into JAR.

> **⚠ Paths must be obtained via `getAssetsPath()`**: Even though files not hash-renamed, path prefix `assets/<id>/` is build-time generated (id may change each build) — hardcoded original paths or `assets/...` literals **won't find files** at runtime. All assets API `path` parameters should pass `getAssetsPath()` return values.

## assets.read(path, options?) / assets.readSync(path, options?)

Read asset file. Same semantics as `fs.readFile`: **By default returns binary `Uint8Array`**, returns string when encoding explicitly specified.

```ts
assets.read(getAssetsPath('image.png')): Promise<Uint8Array>
assets.readSync(getAssetsPath('image.png')): Uint8Array

assets.read(getAssetsPath('template.txt'), 'utf8'): Promise<string>
assets.readSync(getAssetsPath('template.txt'), { encoding: 'utf8' }): string

assets.read(getAssetsPath('blob.bin'), 'base64'): Promise<string>
assets.readSync(getAssetsPath('blob.bin'), 'base64'): string
```

`options` can be `'utf8' | 'base64'` or `{ encoding: 'utf8' | 'base64' }`. Underlying protocol still uses `assets:read` (text) and `assets:readBase64` (binary Base64 carrying) nodes.

## assets.extract(path, dest) / assets.extractSync(path, dest)

Extract asset file to filesystem. **`dest` required**, calculated based on plugin data directory (`plugins/<pluginName>/`), final target must be within plugin directory.

```ts
assets.extract(getAssetsPath('config.json'), 'config.json'): Promise<string>
assets.extractSync(getAssetsPath('config.json'), 'config.json'): string
```

Returns extracted target path (**relative to server root**, e.g., `plugins/<pluginName>/config.json`; can combine with `fs.outer.getServerPath()` to form absolute path).

> **Permission**: assets channel **has no permission interception**; extraction target强制限定 to plugin data directory `plugins/<pluginName>/` (returns error if out of bounds).

## assets.extractDir(path, dest?) / assets.extractDirSync(path, dest?)

**Directory** extracted as whole to filesystem (recursive, maintains internal relative structure). `path` points to a directory under `assets/` (e.g., `native/`); `dest` optional, default `plugins/<pluginName>/assets/<path>`, also limited within plugin data directory.

```ts
assets.extractDir(getAssetsPath('native/')): Promise<string>
assets.extractDirSync(getAssetsPath('native/')): string
```

Returns extracted target directory path (**relative to server root**). Difference from `extract`: `extractDir` extracts entire directory tree (including nested subdirectories), suitable for resource sets needing complete internal reference preservation; `dest` still optional (`extract` required).

## Example

```js
import { getAssetsPath } from 'yeow-dev';
import { assets } from 'yeow-api';

// Read config (path resolved via getAssetsPath; text needs explicit utf8)
const config = assetsReadSync(getAssetsPath('config.yml'), 'utf8');

// Binary read (default Uint8Array)
const icon = assetsReadSync(getAssetsPath('icon.png'));

// Extract resources to filesystem
await assets.extract(getAssetsPath('icon.png'));

// Native Service (auto-injected namespace path)
const { serviceId } = await registerNativeService('renderer', {
    windows: getAssetsPath('native/renderer.exe'),
    linux: getAssetsPath('native/renderer'),
});
```