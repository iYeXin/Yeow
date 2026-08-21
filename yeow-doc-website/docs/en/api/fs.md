# File System API

```js
import { fs } from 'yeow-api';
```

## Access Scope (Node Naming Segments)

fs API nodes distinguished by **access scope naming segments**, relative paths calculated based on corresponding base directory:

| Node Prefix                | Base Directory                      | Permissions                                  |
| -------------------------- | ----------------------------------- | -------------------------------------------- |
| `fs.*` (plugin segment, default) | `plugins/<pluginName>/`        | **No declaration needed**                    |
| `fs.server.*`              | Server root directory (Java process working directory) | Requires `fs:server.*` or node-level `fs:server.<op>` |
| `fs.outer.*`               | Any path (relative paths still based on server root) | Requires `fs:outer.*` or node-level `fs:outer.<op>` |

Plugin segment automatically prevents `../` traversal; server segment also prevents escaping server root; outer segment has no scope restriction (absolute/relative paths both work).

```js
import { fs } from 'yeow-api';

fs.readFileSync('config.json', 'utf8');   // fs:plugin.readFile: plugins/<pluginName>/config.json
fs.server.readFileSync('eula.txt', 'utf8'); // fs:server.readFile: server root/eula.txt
fs.outer.readFileSync('/etc/hosts', 'utf8'); // fs:outer.readFile: any path
```

> **Permissions**: Plugin segment nodes (`fs.*`) allowed by default. `fs.server.*` / `fs.outer.*` denied by default, must declare in `yeow.config.json`'s `permissions` (automatically computed into `computedPermissions` after build): `"fs:server.*"` (whole group) or node-level (e.g., `"fs:server.readFile"`). `"fs:*"` wildcards entire fs channel (including server/outer). Undeclared call returns error `Permission denied: fs:server.<op>` (async API presents as Promise reject). See [Permissions & Native Service Trust](../permissions.md) for details.

## Encoding Semantics (Node-style)

- `readFile` **by default returns binary `Uint8Array`**; only returns string when explicitly specifying `'utf8'` / `'base64'`.
- `writeFile` / `appendFile` accepts `string | Uint8Array`:
  - `string` by default writes in **UTF-8**; when specifying `'base64'`, treats string as Base64-encoded binary data, decodes then writes;
  - `Uint8Array` always writes as raw bytes (encoding option ignored).
- Underlying protocol still carries binary data as Base64 (`readBase64` / `writeBase64` / `appendBase64` nodes), public API doesn't expose Base64 dedicated methods.

## fs.stat(path) / fs.statSync(path)

Get file status (type/size/timestamp). Errors if path doesn't exist.

```ts
fs.stat('config.json'): Promise<FileStat>
fs.statSync('config.json'): FileStat

interface FileStat {
  isFile: boolean       // Regular file
  isDirectory: boolean  // Directory
  size: number          // Byte count (directory is platform-related value, no practical meaning)
  mtimeMs: number       // Last modification time (epoch milliseconds)
  ctimeMs: number       // Creation time (epoch milliseconds)
}
```

```ts
const s = fs.statSync('big.bin');
console.log(s.size, s.mtimeMs);
```

## fs.readFile(path, options?) / fs.readFileSync(path, options?)

Read file. `options` can be `'utf8' | 'base64'` or `{ encoding: 'utf8' | 'base64' }`.

```ts
fs.readFile('image.png'): Promise<Uint8Array>               // Default: binary
fs.readFileSync('image.png'): Uint8Array
fs.readFile('config.json', 'utf8'): Promise<string>         // UTF-8 text
fs.readFileSync('config.json', { encoding: 'utf8' }): string
fs.readFile('blob.bin', 'base64'): Promise<string>          // Base64 string
fs.readFileSync('blob.bin', 'base64'): string
```

## fs.writeFile(path, data, options?) / fs.writeFileSync(path, data, options?)

Write file (overwrite). `data: string | Uint8Array`; `options` can be `'utf8' | 'base64'` or `{ encoding }`.

```ts
fs.writeFile('log.txt', 'hello'): Promise<void>             // String defaults to UTF-8
fs.writeFileSync('log.txt', 'hello'): void
fs.writeFile('out.bin', bytes): Promise<void>               // Uint8Array raw bytes
fs.writeFileSync('out.bin', bytes): void
fs.writeFile('from.b64', base64str, 'base64'): Promise<void> // Base64 decoded then written
```

## fs.appendFile(path, data, options?) / fs.appendFileSync(path, data, options?)

Append write, auto-creates if file doesn't exist. Data and encoding semantics completely consistent with `writeFile`:

```ts
fs.appendFile('log.txt', 'new line\n'): Promise<void>
fs.appendFileSync('log.txt', 'new line\n'): void
fs.appendFile('out.bin', bytes): Promise<void>
fs.appendFile('from.b64', base64str, 'base64'): Promise<void>
```

## Others

Default is async (`Promise`), synchronous version adds `Sync` suffix.

```ts
fs.exists(path): Promise<boolean>
fs.existsSync(path): boolean
fs.isDirectory(path): Promise<boolean>
fs.isDirectorySync(path): boolean
fs.deleteFile(path): Promise<boolean>
fs.deleteFileSync(path): boolean
fs.mkdir(path): Promise<void>
fs.mkdirSync(path): void
fs.list(path): Promise<string[]>
fs.listSync(path): string[]
```

`list` returns directory entry names (without path prefix, consistent with Node `fs.readdir`); `path` is directory path (throws error if doesn't exist or not directory).

> Async file operations don't block JS thread — operations execute in independent IO thread, notify via callback when complete.

## Other Naming Segments

`fs.server` and `fs.outer` expose completely same operation set as `fs`, only access scope and permissions differ:

```ts
fs.server.readFileSync('server.properties', 'utf8') // fs:server.readFile (server root)
fs.outer.writeFileSync('/tmp/data.txt', 'x')        // fs:outer.writeFile (any path)
```

### System Paths (outer segment)

`fs.outer.systemPaths()` gets common system paths (no parameters needed):

```ts
fs.outer.systemPaths(): Promise<{ home: string; desktop: string; temp: string }>
fs.outer.systemPathsSync(): { home: string; desktop: string; temp: string }
// → { home: "C:\\Users\\YeXin", desktop: "C:\\Users\\YeXin\\Desktop", temp: "C:\\Users\\YeXin\\AppData\\Local\\Temp" }
```

| Field     | Meaning                                |
| --------- | -------------------------------------- |
| `home`    | User home directory                    |
| `desktop` | Desktop directory (`<home>/Desktop`, may not exist) |
| `temp`    | System temp directory                  |

`desktop` / `temp` returned paths can be directly passed to outer segment operations (e.g., `fs.outer.writeFileSync(path.join(p.temp, 'x.txt'), ...)`). `systemPaths` belongs to outer segment node (`fs:outer.systemPaths`), requires `fs:outer.*` or `fs:outer.systemPaths` permission.

### Server Root Directory (outer segment)

`fs.outer.getServerPath()` returns **server root directory** (Java process working directory) absolute path — `fs.server.*` / assets extraction etc. relative paths all based on it:

```ts
fs.outer.getServerPath(): Promise<string>
fs.outer.getServerPathSync(): string
// → "C:\\Users\\YeXin\\server"
```

Belongs to outer segment node (`fs:outer.getServerPath`), requires `fs:outer.*` or `fs:outer.getServerPath` permission.

> **⚠ Permission Recommendation**: Directly declaring `fs:*` (entire fs channel) is **dangerous and unprofessional** — equivalent to giving server root and any path read/write permissions to plugin. When only reading/writing plugin's own config files **no fs permission declaration needed** (`fs:plugin.*` nodes allowed by default). When truly needing to access server files, **declare as precisely as possible** (e.g., `fs:server.readFile`, `fs:outer.systemPaths`), not group/channel wildcards. Even declaring `fs:*`, build's `computedPermissions` will automatically expand to `fs:outer.*, fs:server.*`.

## Streaming Read/Write (Large Files)

Large files should use streaming API — **stateful handles** maintain file position, read/write in chunks, memory usage proportional to chunk size (runtime buffers 256 KiB).

**Backpressure mechanism: Explicit response** — Each operation `await` result before initiating next chunk; chunk size specified by `read(maxBytes?)` or caller-controlled (recommended ≥256 KiB).

```ts
import { createReadStream, createWriteStream } from 'yeow-api';

// Read stream (default Uint8Array; can also use for await)
const r = await createReadStream('big.bin');        // fs:plugin.openRead
const chunk: Uint8Array | null = await r.read();    // Default 1 MiB; null = EOF
await r.close();

// Write stream
const w = await createWriteStream('out.bin');       // fs:plugin.openWrite
await w.write(chunk);                               // Wait for write complete (explicit backpressure response)
await w.end();                                      // Flush buffer and close
```

### Options and Encoding (Fixed at Creation)

```ts
createReadStream(path, options?: ReadStreamOptions): Promise<ReadStream>
createWriteStream(path, options?: WriteStreamOptions): Promise<WriteStream>

interface ReadStreamOptions {
  start?: number;      // Byte offset range (start inclusive, end inclusive; default = whole file)
  end?: number;
  encoding?: 'utf8' | 'base64';  // Default = Uint8Array; after specification read() returns string
}
interface WriteStreamOptions {
  flags?: 'w' | 'a' | 'wx';       // Open mode
  encoding?: 'utf8' | 'base64';   // String chunk encoding (default utf8)
}
```

```ts
// Read-only bytes 100..199 of file (e.g., log tail, shard reading)
const r = await createReadStream('log.txt', { start: 100, end: 199 });

// Read by UTF-8 text chunks (multi-byte characters crossing chunks correctly concatenated, won't truncate)
const t = await createReadStream('config.json', { encoding: 'utf8' });
const line: string | null = await t.read();

// Read by Base64 string chunks
const b64 = await createReadStream('blob.bin', { encoding: 'base64' });
const part: string | null = await b64.read();

// Write stream string chunk: Default UTF-8; 'base64' means decode first then write bytes
const w = await createWriteStream('out.txt', { encoding: 'utf8' });
await w.write('Chinese text');                     // Write UTF-8 bytes
await w.end();

const wb = await createWriteStream('out.bin', { encoding: 'base64' });
await wb.write(base64str);                         // Decode to bytes then write
await wb.end();

// Append mode (equivalent streaming version of appendFile)
const a = await createWriteStream('events.log', { flags: 'a' });
await a.write(line);
await a.end();

// Exclusive create: If file already exists creation fails (err) — Suitable for "first-write-only" initialization scenarios
const init = await createWriteStream('state.json', { flags: 'wx' });
```

> **Encoding fixed at creation, runtime doesn't support modification**: After read/write stream creation `encoding` is read-only, no `setEncoding()`; to switch encoding just create another stream.

| `flags` | Behavior |
| --- | --- |
| `'w'` (default) | Overwrite (creates if doesn't exist) |
| `'a'` | Append (creates if doesn't exist) |
| `'wx'` | Exclusive create (errors if already exists) |

### ReadStream

```ts
interface ReadStream<Chunk = Uint8Array> {
  read(maxBytes?: number): Promise<Chunk | null>;  // null = EOF
  close(): Promise<void>;
  [Symbol.asyncIterator](): AsyncIterator<Chunk>;
}
```

```ts
const r = await createReadStream('big.bin');
for await (const chunk of r) { /* Process chunk by chunk */ }
await r.close();

// Text mode: for await gets string chunks
const t = await createReadStream('data.txt', { encoding: 'utf8' });
for await (const s of t) { /* string chunks */ }
```

### WriteStream

```ts
interface WriteStream {
  readonly encoding?: 'utf8' | 'base64';           // Fixed at creation
  write(chunk: Uint8Array | string): Promise<void>; // Write complete resolves (backpressure point)
  end(): Promise<void>;                             // Flush + close (after this cannot write)
  close(): Promise<void>;
}
```

### Permissions

Stream operations inherit fs permission nodes by access segment: `fs:plugin.openRead/read/openWrite/write/end/close` (plugin segment no declaration needed); `fs:server.*` / `fs:outer.*` need declaration (`fs:server.*` etc. wildcards already cover stream operations).

> Stream handles need explicit `close()` / `end()`; runtime automatically closes all residual handles on unload, hot-reload.

## Path Tools

Path joining and parsing (POSIX-style, platform-independent):

```js
import { path } from 'yeow-api';

path.join('a', 'b', 'c')       // "a/b/c"
path.basename('/a/b/c.txt')    // "c.txt"
path.dirname('/a/b/c.txt')     // "/a/b"
path.extname('data.json')      // ".json"
```

## Example

```js
// Read/write JSON config (plugin segment node, no permission needed) — Text needs explicit utf8
const cfg = JSON.parse(await fs.readFile('config.json', 'utf8'));
cfg.version = 2;
await fs.writeFile('config.json', JSON.stringify(cfg, null, 2)); // String defaults to utf8

// Server-level files (needs fs:server.*)
const props = fs.server.readFileSync('server.properties', 'utf8');

// Binary image: Default Uint8Array, direct read/write
const icon = await fs.readFile('icon.png');        // Uint8Array
await fs.writeFile('backup.png', icon);

// Explicit base64 string read/write
const b64 = await fs.readFile('blob.bin', 'base64');
await fs.writeFile('blob-copy.bin', b64, 'base64');
```