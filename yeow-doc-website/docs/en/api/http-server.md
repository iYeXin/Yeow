# HTTP Server API

[yeow-server](https://www.npmjs.com/package/yeow-server) (npm) provides the high-level `createServer`, while `yeow-api` provides the low-level `listen`/`respond`/`close`.

```js
import { createServer } from 'yeow-server';
import type { Server, RouteRequest } from 'yeow-server';
```

> **Permissions**: the http channel is **denied by default as a whole** — you must declare `"http:*"` in the `permissions` of `yeow.config.json`, or declare them per node:
>
> | Node | Purpose |
> |------|---------|
> | `http:listen` | Start an HTTP server (create a listening port) |
> | `http:respond` | Respond to requests (send status/response body) |
> | `http:close` | Close the server |

Creates an HTTP server with routing and automatic response support.

## createServer(port?)

Creates an HTTP server instance (synchronous).

```ts
createServer(port?: number): Server
```

`port` defaults to 0 (random port).

### Server instance

```ts
server.port: number             // the actual port number
server.use(mw)                  // register a generic middleware (onion model)
server.get(path, handler)       // register a GET route
server.post(path, handler)      // register a POST route
server.put(path, handler)       // register a PUT route
server.del(path, handler)       // register a DELETE route
server.mount(dir, prefix?)      // mount a static file directory (plugin data directory)
server.close()                  // close the server
```

**Execution order**: `use` / routes / `mount` form a middleware chain (onion model) in **registration order** — each layer can return a response (short-circuit) or call `next()` to go to the next layer; if a route handler returns nothing, it also continues to the following layers; if no layer produces a response → 404.

**Return value normalization** (consistent for middleware and routes):

| Return                                                          | Handling                                                            |
| --------------------------------------------------------------- | ------------------------------------------------------------------- |
| String                                                          | Text response (`{ body }`, UTF-8)                                   |
| `{ status, body, encoding?, headers }` (containing any field)   | Response options, used as-is (a `Uint8Array` `body` is binary directly; a string + `encoding: 'base64'` is base64-encoded binary) |
| **Any other plain object** (e.g. `{ ok: true }`)                | **Automatically JSON-serialized** (`body` + `content-type: application/json`) |

### Middleware (onion model)

```js
import { createServer } from 'yeow-server';

const app = createServer(8080);

// Logging middleware: log the request first, next() goes to the next layer, log the time after it returns
app.use(async (req, next) => {
    const t = Date.now();
    const result = await next();
    console.log(`${req.method} ${req.path} — ${Date.now() - t}ms`);
    return result;              // pass through the lower layer's response
});

// Auth middleware: return a response directly when the condition isn't met (short-circuit, no further layers)
app.use((req, next) => {
    if (!req.headers['x-token']) {
        return { status: 401, body: 'Unauthorized' };
    }
    return next();
});

app.get('/api/data', (req) => ({ ok: true }));   // automatic JSON serialization → {"ok":true} + application/json
```

- `next()` invokes the next layer in the chain and returns its response (`undefined` when there is no next layer)
- A middleware returning a response object/string → short-circuit return; returning `undefined` (or nothing) → equivalent to `next()`
- Type: `Middleware = (req, next) => string | ResponseBody | undefined | Promise<...>` (`NextFn`)

### Static file mounting (mount)

`mount(dir, prefix?)` mounts a directory under the **plugin data directory** (`plugins/<plugin-name>/`) as a static file service (base64 binary transfer + Content-Type inferred by extension). **Directory requests automatically try `index.html`** (`/` → `index.html`, `/a/` → `a/index.html`; `/a` first reads it as a file, and on failure tries `a/index.html`):

```js
import { createServer } from 'yeow-server';

const app = createServer(8080);

app.mount('web/');                  // /index.html → plugins/<plugin-name>/web/index.html
app.mount('assets/web/', '/static'); // /static/xxx → plugins/<plugin-name>/assets/web/xxx

app.get('/api/data', () => ({ body: 'ok' }));
```

**Detection + automatic extraction (from assets to the filesystem)**: static files are usually bundled in `assets/` (inside the .zip), while `mount` reads from the data directory — on first startup it detects a missing data directory and automatically extracts from `assets`, then mounts directly afterward:

```js
import { createServer } from 'yeow-server';
import { fs, assetsExtractDir } from 'yeow-api';

onLoad(async () => {
    // ① Detect: data directory web/ already exists (extracted last time) → skip; missing → auto-extract from assets
    if (!fs.existsSync('web/index.html')) {
        await assetsExtractDir('web', 'web');   // the web/ directory of assets → data directory web/
        console.log('web assets extracted');
    }

    // ② Mount the static directory (after detection + extraction)
    const app = createServer(8080);
    app.mount('web/');
});
```

- After a version update, if the assets content changed but the data directory already has old files, you can add your own version-marker comparison (e.g. write `fs.writeFileSync('web/.version', version)` after extraction and compare during detection) to decide whether to re-extract
- You can also respond without extraction: `assetsRead(path)` (Uint8Array) + `body` (see [Binary responses](#二进制响应uint8array--base64)) — suitable when you want to read from the package each time, files are small, and don't need to be accessed by other plugins/tools

### Bundled asset mounting (mountAssets)

`mountAssets(dir, prefix?)` mounts **bundled assets** (assets, inside the .zip) directly as a static file service — **no extraction to disk needed**; each request reads a single file from the .zip; directory requests also automatically try `index.html`:

```js
import { createServer } from 'yeow-server';
import { getAssetsPath } from 'yeow-dev';   // build-time virtual module

const app = createServer(8080);

// /index.html → bundled asset assets/<id>/web/index.html (read directly from the .zip)
app.mountAssets(getAssetsPath('web'));
app.mountAssets(getAssetsPath('web'), '/static');   // with a URL prefix
```

- `dir` should be obtained via `getAssetsPath()` (build-time injected namespace id), do not hardcode it
- Same path-traversal protection and Content-Type inference as `mount`
- **Slightly slower but acceptable**: Zip supports reading single files — each request decompresses the target file from the .zip; suitable for **small files / low-frequency access** (e.g. pages, icons, small scripts)
- **For large files or high-frequency access**, use `mount` (detect + extract to the data directory first), or cache the result of `assetsRead(path, 'base64')`

- `dir`: a directory under the plugin data directory (may have a trailing `/`); `prefix`: URL prefix (default `/`)
- Supports common types (html/css/js/json/svg/png/jpg/gif/webp/woff2/zip/pdf/wasm, etc.); unknown extensions fall back to `application/octet-stream`
- **Path-traversal protection**: request paths containing a `..` segment are rejected (continue to the next layer → 404)
- When a file doesn't exist, it continues to the next layer (can be taken over by subsequent routes/404)
- For static files, consider bundling them in `assets/` (`assetsRead(path, 'base64')` + caching) or mounting files that the server owner places in the data directory (`fs` channel is permission-free at the plugin level)

Route handlers receive a `RouteRequest` object:

```ts
{
    connId: string
    serverId: string
    method: string
    path: string
    query: string | undefined   // query string; undefined when there are no query params — be tolerant when parsing (e.g. (req.query ?? '').split('&'))
    headers: object
    body: string
    params: Record<string, string>   // path variables
}
```

### Path variables

Route paths support declaring variables with the `:name` syntax:

```js
app.get('/users/:id', (req) => {
    // GET /users/42  →  req.params.id === "42"
    // GET /users/abc →  req.params.id === "abc"
    return `User ${req.params.id}`;
});

app.get('/posts/:postId/comments/:commentId', (req) => {
    // GET /posts/10/comments/5 →  req.postId === "10", req.commentId === "5"
});
```

### handler return values

A handler can return:

- A string → automatically used as the body response, status 200
- `{status, body, headers}` → a custom response
- `undefined` → no automatic response; you can respond manually via `respond(...)`

**Handlers support async** (a returned Promise is automatically `await`ed, and only then is the response sent — the Promise is never sent back as the body); if a handler throws, a 500 is automatically responded:

## Example

```js
const app = createServer(8080);

app.get('/api/hello', (req) => {
    return { message: 'Hello World' };
});

app.get('/api/users/:id', (req) => {
    return `User ID: ${req.params.id}`;
});

app.post('/api/submit', (req) => {
    console.log('Received:', req.body);
    return { status: 201, body: 'Created' };
});

console.log(`Server on port ${app.port}`);
// close: app.close();
```

## Low-level API

If you need to manually control the response flow, use the low-level `listen` / `respond` / `close`:

```js
import { listen, respond, close } from 'yeow-api';

const { serverId, port } = listen((req) => {
    respond(req.serverId, req.connId, {
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ hello: 'world' }),
    });
}, 8080);

close(serverId);
```

### Binary responses (Uint8Array / base64)

`respond`'s `body` supports three forms (same semantics as `fs.writeFile`): **`Uint8Array` is used directly as binary**; a string defaults to UTF-8 text; a string + `{ encoding: 'base64' }` is treated as base64-encoded binary data.

> **Port notification**: the HTTP listening port must be **guaranteed usable by the plugin author** (not occupied, allowed through the server firewall) — please **inform the server owner of the listening port** in the plugin documentation/description, and have the server owner configure the firewall and port forwarding.

### Full closed loop: download a resource pack and send it to players

```js
import { createServer } from 'yeow-server';
import { assetsRead, Player, fs, eventOn } from 'yeow-api';

// ① Expose the resource pack download URL (cache the Uint8Array)
const app = createServer(17835);
app.get('/resourcepack', async (req) => {
    if (!cachedPack) cachedPack = await assetsRead('pack/resourcepack.zip');
    return { body: cachedPack, headers: { 'content-type': 'application/zip' } };
});

// ② The public address is configured by the server owner in the plugin data directory (plugins/<plugin-name>/config.json)
//    { "publicUrl": "https://mc.example.com:17835" }   ← server owner fills in a publicly reachable IP/domain + port
const cfg = JSON.parse(fs.readFileSync('config.json', 'utf8'));

// ③ Send the resource pack when a player joins (url points to the download address exposed above)
eventOn('playerJoin', async (e) => {
    await e.player.sendResourcePack(
        cfg.publicUrl + '/resourcepack',              // url —— the public address read from config
        hash,                                          // hash —— SHA-1 (optional, recommended for integrity verification)
        { text: '<yellow>Please download the server resource pack</yellow>' },  // prompt —— a Message object (a plain string is also supported)
        true,                                          // force —— whether to force it
    );
});
```

The `hash` of `sendResourcePack` is a **SHA-1 hexadecimal** string (optional but recommended; the client verifies integrity with it); `hash`/`prompt`/`force` may all be omitted. The `hash` must be provided by the plugin itself (e.g. computed on the resource pack file during the build and written into the config).

> **Public address configuration**: `url` must be reachable by the **player client** — the plugin author should have the **server owner fill in a publicly reachable IP or domain** in the config, along with an available port, and explain in the plugin documentation that the port must be opened in the firewall, and port forwarding is needed when behind a NAT/home broadband.

> **⚠ Production recommendation**: Resource packs **should ideally be distributed via a CDN** (e.g. object storage + CDN, GitHub Releases, etc.). **This example (served directly by the plugin's own HTTP server) is only for temporary scenarios** or when users use custom resource packs.
