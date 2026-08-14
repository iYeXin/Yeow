# util 通道规范

纯计算通道（gzip 压缩/解压 + UTF-8 ↔ 字节转换）。**无权限检查**（与 `timer` 同级：不碰文件/网络），**无流式接口**（一次性整体处理）。

## 二进制承载

通道协议上字节数据一律以 **base64 字符串**承载（JSON 安全）。JS 侧引擎原生 `Uint8Array.toBase64()` / `Uint8Array.fromBase64()` 负责转换（ES2023+），适配器层不暴露 base64——encode/decode 的语义是 **buffer ↔ 字符串**，base64 只是承载形式。

## 操作

### `gzip.compress`

- 请求：`{ "t": "gzip.compress", "p": { "data": "<base64>", "level": 0-9 } }`
  - `level` 可选（0-9，默认 -1 = Deflater 默认级别；0 = 仅存储）
- 返回：`{ "data": "<base64>" }`（压缩后字节）
- 错误：`level` 越界 → `{ "err": "level must be 0-9" }`

### `gzip.decompress`

- 请求：`{ "t": "gzip.decompress", "p": { "data": "<base64>" } }`
- 返回：`{ "data": "<base64>" }`（解压后字节）
- 错误：非 GZIP 数据 / CRC 失败 → `{ "err": ... }`；**输出超过 256 MiB**（防压缩炸弹）→ `{ "err": "gunzip output exceeds ..." }`

### `encode.utf8`（字符串 → 字节）

- 请求：`{ "t": "encode.utf8", "p": { "data": "<UTF-8 字符串>" } }`
- 返回：`{ "data": "<base64>" }`（字符串的 UTF-8 字节）

### `decode.utf8`（字节 → 字符串）

- 请求：`{ "t": "decode.utf8", "p": { "data": "<base64>" } }`
- 返回：`{ "data": "<UTF-8 字符串>" }`（非法 UTF-8 序列替换为 U+FFFD，不报错）

## 大小限制

- 输入 `data`（base64 字符数）上限 **64 MiB**（≈48 MiB 原始字节）→ 超出报错
- `gzip.decompress` 输出上限 **256 MiB**（原始字节）→ 超出报错

## 同步 / 异步

与其他通道一致：请求带 `cb` 字段时异步执行（`ioExecutor`，结果经 `{ "t": "cb", "p": "<cbId>", "r": <结果> }` 回投）；不带 `cb` 时同步执行（阻塞 JS 线程至完成）。

## 通用错误格式

```json
{ "err": "<error message>" }
```
