# util 通道规范

纯计算通道（gzip 压缩/解压——一次性 + 流式分块，UTF-8 ↔ 字节转换）。**无权限检查**（与 `timer` 同级：不碰文件/网络）。

## 二进制承载

通道协议上字节数据一律以 **base64 字符串**承载（JSON 安全）。JS 侧引擎原生 `Uint8Array.toBase64()` / `Uint8Array.fromBase64()` 负责转换（ES2023+），适配器层不暴露 base64——encode/decode 的语义是 **buffer ↔ 字符串**，base64 只是承载形式。

## 大小限制（config.yml 可配置）

- `util.max-input-bytes`（默认 **256 MiB**，原始字节）：单次输入上限（解码后精确校验；先按 base64 长度粗筛防分配炸弹）
- `util.max-output-bytes`（默认 **256 MiB**，原始字节）：`gzip.decompress` / 解压器输出上限（防压缩炸弹）

## 操作（一次性）

### `gzip.compress`

- 请求：`{ "t": "gzip.compress", "p": { "data": "<base64>", "level": 0-9 } }`
  - `level` 可选（0-9，默认 -1 = Deflater 默认级别；0 = 仅存储）
- 返回：`{ "data": "<base64>" }`（压缩后字节）
- 错误：`level` 越界 → `{ "err": "level must be 0-9" }`

### `gzip.decompress`

- 请求：`{ "t": "gzip.decompress", "p": { "data": "<base64>" } }`
- 返回：`{ "data": "<base64>" }`（解压后字节）
- 错误：非 GZIP 数据 / CRC 失败 → `{ "err": ... }`；输出超过 `util.max-output-bytes` → `{ "err": "gunzip output exceeds ..." }`

### `encode.utf8`（字符串 → 字节）

- 请求：`{ "t": "encode.utf8", "p": { "data": "<UTF-8 字符串>" } }`
- 返回：`{ "data": "<base64>" }`（字符串的 UTF-8 字节）

### `decode.utf8`（字节 → 字符串）

- 请求：`{ "t": "decode.utf8", "p": { "data": "<base64>" } }`
- 返回：`{ "data": "<UTF-8 字符串>" }`（非法 UTF-8 序列替换为 U+FFFD，不报错）

## 操作（流式分块）

有状态句柄：`create → write×n → finish → close`。**背压 = 显式响应**——调用方等每个操作返回后才发起下一块；`write` 返回可能为空（deflater 窗口未满，输出延迟到后续块/finish）。单块输入受 `util.max-input-bytes` 限制。

### 压缩器

| 操作 | 请求 | 返回 |
|---|---|---|
| `gzip.compressor.create` | `{ level?: 0-9 }` | `{ "id" }` |
| `gzip.compressor.write` | `{ id, data: <b64> }` | `{ "data": <b64> }`（该块压缩输出，可能为空） |
| `gzip.compressor.finish` | `{ id }` | `{ "data": <b64> }`（剩余输出，含 GZIP 尾） |
| `gzip.compressor.close` | `{ id }` | `"true"` |

流式（非 syncFlush）拼接输出与一次性 `gzip.compress` **字节级一致**（syncFlush 会在块边界插入 7 字节 flush marker，不使用）。

### 解压器

| 操作 | 请求 | 返回 |
|---|---|---|
| `gzip.decompressor.create` | `{}` | `{ "id" }` |
| `gzip.decompressor.write` | `{ id, data: <b64> }` | `{ "data": <b64> }`（该块可解出的输出，可能为空） |
| `gzip.decompressor.finish` | `{ id }` | `{ "data": <b64> }`（标记输入结束，读至 GZIP 流 EOF；数据不完整 → err） |
| `gzip.decompressor.close` | `{ id }` | `"true"` |

流句柄 per-plugin 管理：未知/已关闭句柄 → `{ "err": "unknown gc/gd handle: ..." }`；插件卸载/热重载时运行时自动关闭全部句柄。

## 同步 / 异步

与其他通道一致：请求带 `cb` 字段时异步执行（`ioExecutor`，结果经 `{ "t": "cb", "p": "<cbId>", "r": <结果> }` 回投）；不带 `cb` 时同步执行（阻塞 JS 线程至完成）。

## 通用错误格式

```json
{ "err": "<error message>" }
```
