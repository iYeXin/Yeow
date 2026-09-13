# Binary Transport

> **Status: disabled by default.** The runtime currently transports messages as JSON only: `BinaryCodec.ENABLED = false`, `init.js`'s `$send`/`$hm` execute the JSON branch only, and `$_send(null, null)` / the resident buffer are never called. The binary codec (C / JS / Java) is **kept intact and isolated**, gated by the single switch `BinaryCodec.ENABLED` (injected as the global `$binary`). This page documents the implementation for reference when re-enabling it.
>
> The rationale and history are recorded in `binary-format.md` at the repo root: the end-to-end gain did not justify the maintenance cost, and per-node materialization is bounded by the QuickJS public API.

Messages between JS and Java are carried in a per-context resident buffer using a binary encoding; encoding failures fall back to JSON. This page describes the implementation. Plugin development does not require it: the usage and semantics of `$send` and `yeow-api` are unchanged.

## Buffer and threading

- Each context allocates a **16 KiB `DirectByteBuffer`** (little-endian), exposed via `QuickJSContext.buffer()` and registered with native at creation time through `nativeRegisterBuffer` (C side: `YeowCtx.buf` / `buf_cap`).
- The buffer is **read and written only by the JS thread that created the context**. Other threads (main / IO) never touch it; they only enqueue raw Java objects, and encoding happens on the JS thread just before dispatch.
- `QuickJSContext` enforces an owner-thread guard on every method other than `interrupt()`; a foreign-thread call throws.

## Timeline

Upcall (JS → Java):

```
JS $send
  ├─ __yeowWrite(channel, payload)      encode into the buffer (fall back to JSON on failure)
  ├─ $_send(null, null)                 Java (JS thread) reads the buffer → JsonObject
  └─ $_send returns a status code
       0 → null
       1 → __yeowRead()                 decode the upcall result from the buffer
       string → JSON.parse              fallback
```

Downcall (Java → JS):

```
main/IO:  postMessage(obj) → MsgQueue<Object> (raw object)
JS thread: take() → encode into the buffer → $hm(null) → __yeowRead() → _hm(obj)
```

The upcall request uses binary only when `payload` is not `null`/`undefined` and `__yeowWrite` succeeds; otherwise `$_send(channel, JSON)`.

## Binary format (version 2)

Little-endian; no alignment, no hashing; keys are raw UTF-8.

```
header (12B): u32 magic(0x59454F42) | u16 version(=2) | u8 mode | u8 reserved | u32 bodyLen
mode 0 (binary): u16 channelLen | channel(utf8) | value
mode 1 (json)  : varint len | utf8
mode 2 (null)  : -

value := tag [payload]
  T_NULL=0  T_FALSE=1  T_TRUE=2
  T_I32=3 (4B)  T_I64=4 (8B)  T_F64=5 (8B)
  T_STR=6   varint len | bytes
  T_BYTES=7 varint len | raw
  T_OBJ=8   ( T_KEY key value )* T_OBJ_END=9
  T_ARR=10  value*         T_ARR_END=11
  T_KEY=12  varint len | key bytes(UTF-8)
```

- Objects/arrays are delimited by start/end markers; no element counts are written. Writing is a single forward recursion; parsing is a tag-driven recursive state machine.
- Object entries start with an explicit `T_KEY` marker: a bare varint key length whose low 7 bits equal 9 (e.g. the 9-byte key `blockType`) would otherwise be indistinguishable from `T_OBJ_END`.
- String/byte/key lengths use LEB128 varints.
- The runtime currently uses `mode 0` only; `mode 1` and `mode 2` are defined in the codec but not wired.

## Implementations

- C: `quickjs-wrapper/native/src/binary.c` / `binary.h`; installed at context creation as `__yeowWrite(channel, obj) -> boolean` and `__yeowRead() -> any`.
- Java: `yeow-runtime/jvm/core/.../yeow/transport/BinaryCodec.java` (`encodeBinary` / `decodeBinary` / `decodeChannel`). Both sides must match exactly.

## Wiring

| Location | Role |
| --- | --- |
| `init.js` `$send` | Writes the request into the buffer and handles the status code; falls back to JSON |
| `init.js` `$hm` | string arg → `JSON.parse`; `null` → `__yeowRead()` |
| `PluginThread` / `WorkerThread` | The `$_send` callback decodes once (`decodeChannel` + `decodeBinary`) into a `JsonObject` reused by all branches; the message loop `encodeBinary`s each queue object, falling back to `gson.toJson`; `encodeResult` writes the upcall result and returns a status code |
| `MsgQueue` | `toJs` is a `BlockingQueue<Object>` |
| `RuntimeCore` / `SyncCallbackHelper` | `submitTask` / `submitTasks` return raw objects; `cbMessageObject` builds callback envelopes |
| `PaperScheduler` / `FoliaScheduler` | `finish` / `fail` / `purge*` complete the future with the raw object (no serialization) |
| `EventBridge` / `CommandBridge` / `Folia*` / `ServiceManager` | Event/command/service callbacks post raw objects |

Serialization happens in the communication layer; `TaskScheduler.submitGameSync`'s future is `CompletableFuture<Object>`.

## JSON fallback

JSON is used when:

- the encoded payload exceeds 16 KiB;
- the value contains something unencodable (function / symbol, etc.);
- a key or string exceeds implementation limits;
- a codec error occurs.

The fallback decision is made before the JNI call.

## Known differences and limits

- **`ArrayBuffer`**: `__yeowWrite` emits `T_BYTES` for values where `JS_GetArrayBuffer` yields a buffer, and `BinaryCodec` decodes `T_BYTES` to a **base64 string**; on the JSON path a `Uint8Array` becomes a numeric-key object and an `ArrayBuffer` becomes `{}`. The representations differ.
- **`undefined` property values**: `__yeowWrite` encodes an enumerable property whose value is `undefined` as `T_NULL` (`null`); `JSON.stringify` drops the key.
- **Custom `toJSON`** (e.g. `Date`): the binary path does not call `toJSON`.
- **16 KiB limit**: large payloads (inventory/NBT, chunk, base64 binary) fall back to JSON.
- The buffer is thread-private; cross-thread use is undefined behaviour.

## Debugging

- Whether the binary path is taken is decided inside `init.js`'s `$send`; a plugin calling `$send` receives only the final result and cannot infer the path from it.
- `$send(channel, payload, { json: true })` forces that one request onto the JSON text path (sent directly via `$_send(channel, JSON.stringify(payload))`, no resident buffer) and returns the result as a JSON string too — for same-size comparison against the binary path (e.g. benchmarks). The default remains binary with automatic JSON fallback.
- To check a specific message, temporarily instrument the branch where `BinaryCodec.encodeBinary` returns `false`, or the `gson.toJson` fallback branch in the message loop.
- `quickjs-wrapper/java/src/test/.../SmokeTest.java` covers `__yeowWrite` / `__yeowRead` round trips, overflow/unsupported fallback and the binary upcall result; `BinaryCodecTest` covers the Java codec.
