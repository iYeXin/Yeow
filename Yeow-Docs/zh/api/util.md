# util API（gzip + 字节转换）

```ts
import { gzipCompress, gzipDecompress, stringToBytes, bytesToString } from 'yeow-api';
```

util 通道提供 **gzip 压缩/解压** 与 **UTF-8 字符串 ↔ 字节** 转换。输入输出一律为 `Uint8Array` / `string`——**不暴露 base64**（底层经 util 通道以 base64 承载，引擎原生转换）。

## gzip

### gzipCompress(data, level?) / gzipCompressSync(data, level?)

压缩。`data` 为 `Uint8Array`（字节），或 `string`（视为 UTF-8 文本，自动字节化）。`level` 0-9（默认引擎默认级别）。

```ts
gzipCompress(data: Uint8Array | string, level?: number): Promise<Uint8Array>
gzipCompressSync(data: Uint8Array | string, level?: number): Uint8Array
```

```ts
const buf = new Uint8Array([...]);           // 任意字节
const packed = await gzipCompress(buf);      // 压缩
const packedFast = gzipCompressSync('big text...', 1);  // 快速压缩（string 输入）
```

### gzipDecompress(data) / gzipDecompressSync(data)

解压。非 GZIP 数据报错；**解压输出上限 256 MiB**（防压缩炸弹），超限报错。

```ts
gzipDecompress(data: Uint8Array | string): Promise<Uint8Array>
gzipDecompressSync(data: Uint8Array | string): Uint8Array
```

```ts
const original = await gzipDecompress(packed);
```

## 字符串 ↔ 字节

### stringToBytes(text) / stringToBytesAsync(text)

UTF-8 字符串 → 字节（同步版阻塞 JS 线程，异步版经 ioExecutor）。

```ts
stringToBytes(text: string): Uint8Array
stringToBytesAsync(text: string): Promise<Uint8Array>
```

### bytesToString(bytes) / bytesToStringAsync(bytes)

字节 → UTF-8 字符串（非法序列替换为 U+FFFD，不报错）。

```ts
bytesToString(bytes: Uint8Array): string
bytesToStringAsync(bytes: Uint8Array): Promise<string>
```

## 典型用法

```ts
import { gzipCompress, gzipDecompress, bytesToString, stringToBytes } from 'yeow-api';
import { fs } from 'yeow-api';

// 压缩后写入文件
const packed = await gzipCompress(stringToBytes('hello 你好'), 6);
await fs.writeFileBase64('data.bin.gz', packed.toBase64());

// 读取并解压
const raw = await gzipDecompress(await fs.readFileBase64('data.bin.gz'));
console.log(bytesToString(raw)); // hello 你好
```

## 注意

- **无流式接口**：一次性整体处理；超大数据的压缩请自行分块（如按 1 MiB 分片）。
- 输入上限 64 MiB（base64 字符数，≈48 MiB 原始字节），超出报错。
- 压缩是 CPU 操作：异步版（`...Async` / `await`）在 ioExecutor 上执行，不阻塞 JS 线程与游戏线程。
