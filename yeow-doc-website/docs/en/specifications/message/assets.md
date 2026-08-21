# Assets Channel

Reads the plugin's built-in asset files located in the `assets/` directory inside the plugin JAR/package.

> **Permissions**: The assets channel has **no permission interception** (it only reads packaged assets, or extracts them into the **plugin's own data directory**). The extraction destination is forcibly confined to `plugins/<pluginName>/` (returns an error if it goes out of bounds).

## Call Format

```json
{ "t": "<operation>", "p": { "path": "<path>", "dest": "<dest>" }, "cb": "<callbackId>" }
```

`path` is a path relative to `assets/` (e.g. `config.json` refers to `assets/config.json`).

### Async Mode

If the payload contains a `cb` field, the operation executes **asynchronously**: `$send` immediately returns `null`, the operation runs on a separate IO thread, and the result is delivered through the `cb` channel when complete. If there is no `cb`, the operation runs synchronously.

---

## Operations List

### `read`

- **p**: `{ "path": "<path>" }`
- **Return**: `{ "data": "<content>" }`

### `readBase64`

- **p**: `{ "path": "<path>" }`
- **Return**: `{ "data": "<base64>" }`

### `extract`

- **p**: `{ "path": "<path>", "dest": "<dest>" }` — **`dest` is required**, resolved relative to and confined within the plugin data directory (`plugins/<pluginName>/`)
- **Return**: `{ "path": "<path relative to server root>" }`

Extracts an asset file to the file system. `dest` is required (returns an error if omitted).

### `extractDir`

- **p**: `{ "path": "<path>", "dest": "<extractPath>"? }`
- **Return**: `{ "path": "<path relative to server root>" }`

Extracts a resource **directory tree** to the file system (recursive, preserving the internal relative structure). `path` points to a directory under `assets/` (e.g. `native/`); `dest` is optional (defaults to `plugins/<pluginName>/assets/<path>`), likewise confined within the plugin data directory.

---

## Development Mode

In development mode, the implementation should read asset files from local filesystem paths (rather than from within the JAR package) to support hot reload.
