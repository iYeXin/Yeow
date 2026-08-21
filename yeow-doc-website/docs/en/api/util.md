# util API (Gzip + byte conversion)

```ts
import { Gzip, stringToBytes, bytesToString } from 'yeow-api';
```

The util module provides **gzip compression/decompression** (one-shot + streaming in chunks) and **UTF-8 string ↔ byte** conversion. Inputs and outputs are always `Uint8Array` / `string` — **base64 is not exposed** (under the hood base64 is carried through the util channel and converted natively by the engine).

> If you need **ZIP** compression/decompression capabilities (this module is single-stream gzip and does not include a ZIP container), see [yeow-fflate](https://www.npmjs.com/package/yeow-fflate).

## Size limits (configurable)

The per-call input/decompression output limit defaults to **256 MiB** (raw bytes), adjustable in `plugins/Yeow/runtime/config.yml`:

```yaml
util:
  max-input-bytes: 268435456    # per-call input limit (raw bytes, default 256 MiB)
  max-output-bytes: 268435456   # gzip decompression output limit (anti-compression-bomb, default 256 MiB)
```

## One-shot gzip

### Gzip.compress(data, options?) / Gzip.compressSync(data, options?)

Compress. `data` is a `Uint8Array` (bytes) or a `string` (treated as UTF-8 text).

```ts
Gzip.compress(data: Uint8Array | string, options?: number | GzipCompressOptions): Promise<Uint8Array>
Gzip.compressSync(data: Uint8Array | string, options?: number | GzipCompressOptions): Uint8Array

interface GzipCompressOptions {
  level?: number;  // 0-9 (default engine default level)
  raw?: boolean;   // true = raw deflate (no GZIP header/trailer/CRC)
}
```

The numeric `level` shorthand still works: `Gzip.compress(data, 6)` ≡ `Gzip.compress(data, { level: 6 })`.

```ts
const packed = await Gzip.compress(buf, { level: 9 });
const rawPacked = await Gzip.compress(buf, { raw: true });  // raw deflate stream
```

### Gzip.decompress(data, options?) / Gzip.decompressSync(data, options?)

Decompress. Non-GZIP data errors out; output exceeding `util.max-output-bytes` (default 256 MiB) errors out.

```ts
Gzip.decompress(data: Uint8Array | string, options?: GzipDecompressOptions): Promise<Uint8Array>
Gzip.decompressSync(data: Uint8Array | string, options?: GzipDecompressOptions): Uint8Array

interface GzipDecompressOptions {
  raw?: boolean;   // true = raw deflate (no GZIP header/trailer/CRC validation)
}
```

> **raw deflate note**: raw deflate streams have **no integrity validation** (no CRC/length) — truncated data silently decompresses to a partial result, so the caller must ensure data integrity itself (e.g. an external checksum). Raw-compressed output **cannot** be decompressed with the non-raw `decompress`, and vice versa (it errors with invalid deflate data / non-GZIP data).

## Streaming gzip (chunked input)

Compression and decompression of large files/data should use streaming — **feed in chunks, take output chunk by chunk**; memory usage is proportional to the chunk size, and there's no need to load all data at once.

**Backpressure: explicit response** — each operation `await`s its result before initiating the next chunk; processing speed is determined by the caller's consumption speed.

**Recommended chunk size ≥256 KiB**.

### Gzip.createCompressor(options?) → GzipCompressor

```ts
Gzip.createCompressor(options?: GzipCompressOptions): Promise<GzipCompressor>
// options.raw = true → raw deflate stream (chunked)
```

```ts
interface GzipCompressor {
  write(chunk: Uint8Array | string): Promise<Uint8Array>;  // that chunk's compressed output (may be empty)
  finish(): Promise<Uint8Array>;                            // remaining output (gzip includes GZIP trailer / raw is the deflate stream end)
  close(): Promise<void>;
}
```

```ts
const comp = await Gzip.createCompressor({ level: 6 });
const out: Uint8Array[] = [];
for (const chunk of chunks) out.push(await comp.write(chunk));
out.push(await comp.finish());
await comp.close();
```

> The concatenated output of streaming (non-syncFlush) compression is **byte-identical** to the one-shot `Gzip.compress`.

### Gzip.createDecompressor(options?) → GzipDecompressor

```ts
Gzip.createDecompressor(options?: GzipDecompressOptions): Promise<GzipDecompressor>
// options.raw = true → raw deflate stream (chunked)
```

```ts
interface GzipDecompressor {
  write(chunk: Uint8Array | string): Promise<Uint8Array>;   // the output that can be extracted from that chunk (may be empty)
  finish(): Promise<Uint8Array>;                            // mark end of input, return the remaining output
  close(): Promise<void>;
}
```

```ts
const dec = await Gzip.createDecompressor();
const out: Uint8Array[] = [];
for (const chunk of packedChunks) out.push(await dec.write(chunk));
out.push(await dec.finish());   // rejects if the data is incomplete
await dec.close();
```

## String ↔ bytes

Naming convention: **async by default, synchronous with a `Sync` suffix** (consistent with the rest of yeow-api).

### stringToBytes(text) / stringToBytesSync(text)

UTF-8 string → bytes. `stringToBytes` is async (ioExecutor), `stringToBytesSync` is synchronous.

### bytesToString(bytes) / bytesToStringSync(bytes)

Bytes → UTF-8 string (invalid sequences are replaced with U+FFFD, no error). Async `bytesToString` / sync `bytesToStringSync`.

### Relationship with TextEncoder / TextDecoder

The runtime provides **global `TextEncoder` / `TextDecoder`** via polyfill (utf-8, Web semantics; see [Runtime Environment](../specifications/runtime/index.md#textencoder--textdecoder)). Both are **synchronous** APIs.

**For synchronous UTF-8 encoding/decoding, prefer `TextEncoder` / `TextDecoder`** (best performance: small payloads are pure JS direct conversion with zero round-trips); oversized payloads above the threshold are also handled synchronously (via the util channel, briefly blocking the JS thread).

> When you need **large-scale non-blocking UTF-8 encoding/decoding**, use yeow-api's async `stringToBytes` / `bytesToString` (run on the ioExecutor, not blocking the game thread).

## Typical usage

```ts
import { Gzip, stringToBytesSync } from 'yeow-api';
import { createReadStream, createWriteStream, fs } from 'yeow-api';

// File stream → gzip stream (chunked compression)
const r = await createReadStream('big.bin');
const w = await fs.createWriteStream('big.bin.gz');
const comp = await Gzip.createCompressor();
for await (const chunk of r) {
  const packed = await comp.write(chunk);
  if (packed.length) await w.write(packed);
}
await w.write(await comp.finish());
await comp.close();
await w.end();

// One-shot: sync byte conversion (String→Uint8Array), then async compression
const packed = await Gzip.compress(stringToBytesSync('hello 你好'), 6);
const raw = await Gzip.decompress(packed);

// Async byte conversion (default)
const bytes = await stringToBytes('hello 你好');
const text = await bytesToString(bytes);
```

## Notes

- The synchronous versions (`...Sync`) block the JS thread; the async versions and streaming operations run on the ioExecutor and do not block the game thread.
- Stream handles must be explicitly `close()`d (on unload/hot-reload the runtime automatically closes all handles).
