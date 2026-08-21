# Util Channel Specification

Pure computation channel (gzip compress/decompress — one-shot + streaming chunks, UTF-8 ↔ byte conversion). **No permission checks** (same tier as `timer`: no file/network access).

## Binary Transport

Channel protocol carries byte data as **base64 strings** (JSON-safe). JS side uses engine-native `Uint8Array.toBase64()` / `Uint8Array.fromBase64()` for conversion (ES2023+); the adapter layer does not expose base64 — encode/decode semantics are **buffer ↔ string**, base64 is just the transport format.

## Size Limits (config.yml configurable)

- `util.max-input-bytes` (default **256 MiB**, raw bytes): Single input limit (precise validation after decode; rough pre-check by base64 length to prevent allocation bombs)
- `util.max-output-bytes` (default **256 MiB**, raw bytes): `gzip.decompress` / decompressor output limit (anti-compression-bomb)

## Operations (One-Shot)

### `gzip.compress`

- Request: `{ "t": "gzip.compress", "p": { "data": "<base64>", "level": 0-9, "raw": <bool> } }`
  - `level` optional (0-9, default -1 = Deflater default level; 0 = store only)
  - `raw` optional (default false) — true = **raw deflate** (no GZIP header/trailer/CRC)
- Returns: `{ "data": "<base64>" }` (compressed bytes)
- Error: `level` out of range → `{ "err": "level must be 0-9" }`

### `gzip.decompress`

- Request: `{ "t": "gzip.decompress", "p": { "data": "<base64>", "raw": <bool> } }`
  - `raw` optional (default false) — true = raw deflate decompression (no CRC/length check; truncation ends silently)
- Returns: `{ "data": "<base64>" }` (decompressed bytes)
- Error: Non-GZIP data / CRC failure → `{ "err": ... }`; raw data format error → `{ "err": "invalid deflate data: ..." }`; output exceeds `util.max-output-bytes` → `{ "err": "gunzip output exceeds ..." }`

### `encode.utf8` (string → bytes)

- Request: `{ "t": "encode.utf8", "p": { "data": "<UTF-8 string>" } }`
- Returns: `{ "data": "<base64>" }` (string's UTF-8 bytes)

### `decode.utf8` (bytes → string)

- Request: `{ "t": "decode.utf8", "p": { "data": "<base64>" } }`
- Returns: `{ "data": "<UTF-8 string>" }` (invalid UTF-8 sequences replaced with U+FFFD, no error)

## Operations (Streaming Chunked)

Stateful handles: `create → write×n → finish → close`. **Backpressure = explicit response** — caller waits for each operation to return before sending the next chunk; `write` may return empty (deflater window not full, output deferred to subsequent chunks/finish). Single chunk input limited by `util.max-input-bytes`.

### Compressor

| Operation | Request | Returns |
|---|---|---|
| `gzip.compressor.create` | `{ level?: 0-9, raw?: bool }` | `{ "id" }` |
| `gzip.compressor.write` | `{ id, data: <b64> }` | `{ "data": <b64> }` (compressed output for this chunk, may be empty) |
| `gzip.compressor.finish` | `{ id }` | `{ "data": <b64> }` (remaining output; gzip includes GZIP trailer, raw is deflate stream end) |
| `gzip.compressor.close` | `{ id }` | `"true"` |

Streaming (non-syncFlush) concatenated output is **byte-identical** to one-shot `gzip.compress` (syncFlush inserts 7-byte flush markers at chunk boundaries, not used).

### Decompressor

| Operation | Request | Returns |
|---|---|---|
| `gzip.decompressor.create` | `{ raw?: bool }` | `{ "id" }` |
| `gzip.decompressor.write` | `{ id, data: <b64> }` | `{ "data": <b64> }` (output decompressible from this chunk, may be empty) |
| `gzip.decompressor.finish` | `{ id }` | `{ "data": <b64> }` (marks input end, reads to stream EOF; incomplete gzip data → err; raw has no validation) |
| `gzip.decompressor.close` | `{ id }` | `"true"` |

Stream handles are managed per-plugin: unknown/closed handle → `{ "err": "unknown gc/gd handle: ..." }`; runtime auto-closes all handles on plugin unload/hot-reload.

## Synchronous / Asynchronous

Consistent with other channels: requests with `cb` field execute asynchronously (`ioExecutor`, result delivered via `{ "t": "cb", "p": "<cbId>", "r": <result> }`); without `cb` execute synchronously (blocks JS thread until complete).

## Generic Error Format

```json
{ "err": "<error message>" }
```
