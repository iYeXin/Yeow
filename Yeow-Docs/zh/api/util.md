# util API（Gzip + 字节转换）

```ts
import { Gzip, stringToBytes, bytesToString } from 'yeow-api';
```

util 模块提供 **gzip 压缩/解压**（一次性 + 流式分块）与 **UTF-8 字符串 ↔ 字节** 转换。输入输出一律为 `Uint8Array` / `string`——**不暴露 base64**（底层经 util 通道以 base64 承载，引擎原生转换）。

## 大小上限（可配置）

单次输入/解压输出上限默认 **256 MiB**（原始字节），可在 `plugins/Yeow/runtime/config.yml` 调整：

```yaml
util:
  max-input-bytes: 268435456    # 单次输入上限（原始字节，默认 256 MiB）
  max-output-bytes: 268435456   # gzip 解压输出上限（防压缩炸弹，默认 256 MiB）
```

## 一次性 gzip

### Gzip.compress(data, level?) / Gzip.compressSync(data, level?)

压缩。`data` 为 `Uint8Array`（字节）或 `string`（视为 UTF-8 文本）。`level` 0-9（默认引擎默认级别）。

```ts
Gzip.compress(data: Uint8Array | string, level?: number): Promise<Uint8Array>
Gzip.compressSync(data: Uint8Array | string, level?: number): Uint8Array
```

### Gzip.decompress(data) / Gzip.decompressSync(data)

解压。非 GZIP 数据报错；输出超过 `util.max-output-bytes`（默认 256 MiB）报错。

```ts
Gzip.decompress(data: Uint8Array | string): Promise<Uint8Array>
Gzip.decompressSync(data: Uint8Array | string): Uint8Array
```

## 流式 gzip（分块输入）

大文件/大数据的压缩与解压应使用流式——**分块喂入，逐块取输出**，内存占用与块大小成正比，不要求一次性装载全部数据。

**背压机制：显式响应**——每个操作 `await` 结果后才发起下一块；处理速度由调用方消费速度决定。

**块大小建议 ≥256 KiB**。

### Gzip.createCompressor(options?) → GzipCompressor

```ts
interface GzipCompressor {
  write(chunk: Uint8Array | string): Promise<Uint8Array>;  // 该块的压缩输出（可能为空）
  finish(): Promise<Uint8Array>;                            // 剩余输出（含 GZIP 尾）
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

### Gzip.createDecompressor() → GzipDecompressor

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

### stringToBytes(text) / stringToBytesAsync(text)

UTF-8 字符串 → 字节。

### bytesToString(bytes) / bytesToStringAsync(bytes)

字节 → UTF-8 字符串（非法序列替换为 U+FFFD，不报错）。

## 典型用法

```ts
import { Gzip, stringToBytes } from 'yeow-api';
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

// 一次性
const packed = await Gzip.compress(stringToBytes('hello 你好'), 6);
const raw = await Gzip.decompress(packed);
```

## 注意

- 同步版（`...Sync`）阻塞 JS 线程；异步版与流式操作在 ioExecutor 上执行，不阻塞游戏线程。
- 流句柄需显式 `close()`（卸载/热重载时运行时自动关闭全部句柄）。
