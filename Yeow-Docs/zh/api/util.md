# util API（Gzip + 字节转换）

```ts
import { Gzip, stringToBytes, bytesToString } from 'yeow-api';
```

util 模块提供 **gzip 压缩/解压**（一次性 + 流式分块）与 **UTF-8 字符串 ↔ 字节** 转换。输入输出一律为 `Uint8Array` / `string`——**不暴露 base64**（底层经 util 通道以 base64 承载，引擎原生转换）。

> 如需使用 **ZIP** 压缩/解压能力（本模块为单流 gzip，不含 ZIP 容器），参考 [yeow-fflate](https://www.npmjs.com/package/yeow-fflate)。

## 大小上限（可配置）

单次输入/解压输出上限默认 **256 MiB**（原始字节），可在 `plugins/Yeow/runtime/config.yml` 调整：

```yaml
util:
  max-input-bytes: 268435456    # 单次输入上限（原始字节，默认 256 MiB）
  max-output-bytes: 268435456   # gzip 解压输出上限（防压缩炸弹，默认 256 MiB）
```

## 一次性 gzip

### Gzip.compress(data, options?) / Gzip.compressSync(data, options?)

压缩。`data` 为 `Uint8Array`（字节）或 `string`（视为 UTF-8 文本）。

```ts
Gzip.compress(data: Uint8Array | string, options?: number | GzipCompressOptions): Promise<Uint8Array>
Gzip.compressSync(data: Uint8Array | string, options?: number | GzipCompressOptions): Uint8Array

interface GzipCompressOptions {
  level?: number;  // 0-9（默认引擎默认级别）
  raw?: boolean;   // true = 原始 deflate（无 GZIP 头/尾/CRC）
}
```

数字 `level` 简写仍可用：`Gzip.compress(data, 6)` ≡ `Gzip.compress(data, { level: 6 })`。

```ts
const packed = await Gzip.compress(buf, { level: 9 });
const rawPacked = await Gzip.compress(buf, { raw: true });  // 原始 deflate 流
```

### Gzip.decompress(data, options?) / Gzip.decompressSync(data, options?)

解压。非 GZIP 数据报错；输出超过 `util.max-output-bytes`（默认 256 MiB）报错。

```ts
Gzip.decompress(data: Uint8Array | string, options?: GzipDecompressOptions): Promise<Uint8Array>
Gzip.decompressSync(data: Uint8Array | string, options?: GzipDecompressOptions): Uint8Array

interface GzipDecompressOptions {
  raw?: boolean;   // true = 原始 deflate（无 GZIP 头/尾/CRC 校验）
}
```

> **raw deflate 注意**：原始 deflate 流**无完整性校验**（无 CRC/长度）——截断的数据会静默解出部分结果，调用方需自行保证数据完整（如外部校验和）。`raw` 压缩输出**不能**用非 raw 的 `decompress` 解，反之亦然（会报 invalid deflate data / 非 GZIP 数据）。

## 流式 gzip（分块输入）

大文件/大数据的压缩与解压应使用流式——**分块喂入，逐块取输出**，内存占用与块大小成正比，不要求一次性装载全部数据。

**背压机制：显式响应**——每个操作 `await` 结果后才发起下一块；处理速度由调用方消费速度决定。

**块大小建议 ≥256 KiB**。

### Gzip.createCompressor(options?) → GzipCompressor

```ts
Gzip.createCompressor(options?: GzipCompressOptions): Promise<GzipCompressor>
// options.raw = true → 原始 deflate 流（分块）
```

```ts
interface GzipCompressor {
  write(chunk: Uint8Array | string): Promise<Uint8Array>;  // 该块的压缩输出（可能为空）
  finish(): Promise<Uint8Array>;                            // 剩余输出（gzip 含 GZIP 尾 / raw 为 deflate 流尾）
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

> 流式（非 syncFlush）压缩的拼接输出与一次性 `Gzip.compress` **字节级一致**。

### Gzip.createDecompressor(options?) → GzipDecompressor

```ts
Gzip.createDecompressor(options?: GzipDecompressOptions): Promise<GzipDecompressor>
// options.raw = true → 原始 deflate 流（分块）
```

```ts
interface GzipDecompressor {
  write(chunk: Uint8Array | string): Promise<Uint8Array>;   // 该块可解出的输出（可能为空）
  finish(): Promise<Uint8Array>;                            // 标记输入结束，返回剩余输出
  close(): Promise<void>;
}
```

```ts
const dec = await Gzip.createDecompressor();
const out: Uint8Array[] = [];
for (const chunk of packedChunks) out.push(await dec.write(chunk));
out.push(await dec.finish());   // 数据不完整会 reject
await dec.close();
```

## 字符串 ↔ 字节

命名约定：**默认异步，同步加 `Sync` 后缀**（与 yeow-api 其余一致）。

### stringToBytes(text) / stringToBytesSync(text)

UTF-8 字符串 → 字节。`stringToBytes` 异步（ioExecutor），`stringToBytesSync` 同步。

### bytesToString(bytes) / bytesToStringSync(bytes)

字节 → UTF-8 字符串（非法序列替换为 U+FFFD，不报错）。异步版 `bytesToString` / 同步版 `bytesToStringSync`。

### 与 TextEncoder / TextDecoder 的关系

运行时通过 polyfill 提供**全局 `TextEncoder` / `TextDecoder`**（utf-8，Web 语义；详见 [运行时环境](../specifications/runtime/index.md#textencoder--textdecoder)）。二者为**同步** API。

**同步 UTF-8 编解码优先用 `TextEncoder` / `TextDecoder`**（性能最好：小载荷纯 JS 直转、零往返）；超阈值大载荷同样同步处理（经 util 通道，会短暂阻塞 JS 线程）。

> 需要**大规模非阻塞 UTF-8 编解码**时，使用 yeow-api 的异步 `stringToBytes` / `bytesToString`（ioExecutor 执行，不阻塞游戏线程）。

## 典型用法

```ts
import { Gzip, stringToBytesSync } from 'yeow-api';
import { createReadStream, createWriteStream, fs } from 'yeow-api';

// 文件流 → gzip 流（分块压缩）
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

// 一次性：同步字节转换（String→Uint8Array），再异步压缩
const packed = await Gzip.compress(stringToBytesSync('hello 你好'), 6);
const raw = await Gzip.decompress(packed);

// 异步字节转换（默认）
const bytes = await stringToBytes('hello 你好');
const text = await bytesToString(bytes);
```

## 注意

- 同步版（`...Sync`）阻塞 JS 线程；异步版与流式操作在 ioExecutor 上执行，不阻塞游戏线程。
- 流句柄需显式 `close()`（卸载/热重载时运行时自动关闭全部句柄）。
