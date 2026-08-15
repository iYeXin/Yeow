# 文件系统 API

```js
import { fs } from 'yeow-api';
```

## 访问范围（节点命名段）

fs API 的节点按**访问范围命名段**区分，相对路径基于对应基准目录计算：

| 节点前缀                  | 基准目录                           | 权限                                       |
| ------------------------- | ---------------------------------- | ------------------------------------------ |
| `fs.*`（plugin 段，默认） | `plugins/<插件名>/`                | **无需声明**                               |
| `fs.server.*`             | 服务器根目录（Java 进程工作目录）  | 需 `fs:server.*` 或节点级 `fs:server.<op>` |
| `fs.outer.*`              | 任意路径（相对路径仍基于服务器根） | 需 `fs:outer.*` 或节点级 `fs:outer.<op>`   |

plugin 段自动阻止 `../` 穿越；server 段同样阻止逃逸出服务器根；outer 段无范围限制（绝对/相对路径均可）。

```js
import { fs } from 'yeow-api';

fs.readFileSync('config.json', 'utf8');   // fs:plugin.readFile：plugins/<插件名>/config.json
fs.server.readFileSync('eula.txt', 'utf8'); // fs:server.readFile：服务器根/eula.txt
fs.outer.readFileSync('/etc/hosts', 'utf8'); // fs:outer.readFile：任意路径
```

> **权限**：plugin 段节点（`fs.*`）默认允许。`fs.server.*` / `fs.outer.*` 默认拒绝，须在 `yeow.config.json` 的 `permissions` 中声明（构建后自动计算进 `computedPermissions`）：`"fs:server.*"`（整组）或节点级（如 `"fs:server.readFile"`）。`"fs:*"` 通配整个 fs 通道（含 server/outer）。未声明调用返回错误 `Permission denied: fs:server.<op>`（异步 API 以 Promise reject 呈现）。详见 [权限与原生服务可信性](../permissions.md)。

## 编码语义（Node 风格）

- `readFile` **默认返回二进制 `Uint8Array`**；只有显式指定 `'utf8'` / `'base64'` 才返回字符串。
- `writeFile` / `appendFile` 接收 `string | Uint8Array`：
  - `string` 默认按 **UTF-8** 写入；指定 `'base64'` 时，把字符串当作 Base64 编码的二进制数据解码后写入；
  - `Uint8Array` 始终按原始字节写入（encoding 选项被忽略）。
- 底层协议仍以 Base64 承载二进制数据（`readBase64` / `writeBase64` / `appendBase64` 节点），公共 API 不暴露 Base64 专用方法。

## fs.stat(path) / fs.statSync(path)

获取文件状态（类型/大小/时间戳）。路径不存在报错。

```ts
fs.stat('config.json'): Promise<FileStat>
fs.statSync('config.json'): FileStat

interface FileStat {
  isFile: boolean       // 常规文件
  isDirectory: boolean  // 目录
  size: number          // 字节数（目录为平台相关值，无实际意义）
  mtimeMs: number       // 最后修改时间（epoch 毫秒）
  ctimeMs: number       // 创建时间（epoch 毫秒）
}
```

```ts
const s = fs.statSync('big.bin');
console.log(s.size, s.mtimeMs);
```

## fs.readFile(path, options?) / fs.readFileSync(path, options?)

读取文件。`options` 可为 `'utf8' | 'base64'` 或 `{ encoding: 'utf8' | 'base64' }`。

```ts
fs.readFile('image.png'): Promise<Uint8Array>               // 默认：二进制
fs.readFileSync('image.png'): Uint8Array
fs.readFile('config.json', 'utf8'): Promise<string>         // UTF-8 文本
fs.readFileSync('config.json', { encoding: 'utf8' }): string
fs.readFile('blob.bin', 'base64'): Promise<string>          // Base64 字符串
fs.readFileSync('blob.bin', 'base64'): string
```

## fs.writeFile(path, data, options?) / fs.writeFileSync(path, data, options?)

写入文件（覆盖）。`data: string | Uint8Array`；`options` 可为 `'utf8' | 'base64'` 或 `{ encoding }`。

```ts
fs.writeFile('log.txt', 'hello'): Promise<void>             // 字符串默认 UTF-8
fs.writeFileSync('log.txt', 'hello'): void
fs.writeFile('out.bin', bytes): Promise<void>               // Uint8Array 原始字节
fs.writeFileSync('out.bin', bytes): void
fs.writeFile('from.b64', base64str, 'base64'): Promise<void> // base64 解码后写入
```

## fs.appendFile(path, data, options?) / fs.appendFileSync(path, data, options?)

追加写入，文件不存在时自动创建。数据与编码语义和 `writeFile` 完全一致：

```ts
fs.appendFile('log.txt', 'new line\n'): Promise<void>
fs.appendFileSync('log.txt', 'new line\n'): void
fs.appendFile('out.bin', bytes): Promise<void>
fs.appendFile('from.b64', base64str, 'base64'): Promise<void>
```

## 其他

默认为异步（`Promise`），同步版本加 `Sync` 后缀。

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

> 异步文件操作不阻塞 JS 线程——操作在独立 IO 线程中执行，完成后通过回调通知。

## 其他命名段

`fs.server` 与 `fs.outer` 暴露与 `fs` 完全相同的操作集合，仅访问范围与权限不同：

```ts
fs.server.readFileSync('server.properties', 'utf8') // fs:server.readFile（服务器根）
fs.outer.writeFileSync('/tmp/data.txt', 'x')        // fs:outer.writeFile（任意路径）
```

### 系统路径（outer 段）

`fs.outer.systemPaths()` 获取常用系统路径（无需传参）：

```ts
fs.outer.systemPaths(): Promise<{ home: string; desktop: string; temp: string }>
fs.outer.systemPathsSync(): { home: string; desktop: string; temp: string }
// → { home: "C:\\Users\\YeXin", desktop: "C:\\Users\\YeXin\\Desktop", temp: "C:\\Users\\YeXin\\AppData\\Local\\Temp" }
```

| 字段      | 含义                                     |
| --------- | ---------------------------------------- |
| `home`    | 用户主目录                               |
| `desktop` | 桌面目录（`<home>/Desktop`，可能不存在） |
| `temp`    | 系统临时目录                             |

`desktop` / `temp` 返回的路径可直接传给 outer 段操作（如 `fs.outer.writeFileSync(path.join(p.temp, 'x.txt'), ...)`）。`systemPaths` 属于 outer 段节点（`fs:outer.systemPaths`），需要 `fs:outer.*` 或 `fs:outer.systemPaths` 权限。

### 服务器根目录（outer 段）

`fs.outer.getServerPath()` 返回**服务器根目录**（Java 进程工作目录）的绝对路径——`fs.server.*` / assets 解压等相对路径都以它为基准：

```ts
fs.outer.getServerPath(): Promise<string>
fs.outer.getServerPathSync(): string
// → "C:\\Users\\YeXin\\server"
```

属于 outer 段节点（`fs:outer.getServerPath`），需要 `fs:outer.*` 或 `fs:outer.getServerPath` 权限。

> **⚠ 权限建议**：直接声明 `fs:*`（整个 fs 通道）是**危险且不专业的**——等于把服务器根与任意路径的读写权交给插件。只读写插件自己的配置文件时**无需声明任何 fs 权限**（`fs:plugin.*` 节点默认允许）。确需访问服务器文件时，尽可能**精确声明**（如 `fs:server.readFile`、`fs:outer.systemPaths`），而非整组/通道通配。即使声明 `fs:*`，构建的 `computedPermissions` 也会自动展开为 `fs:outer.*, fs:server.*`。

## 流式读写（大文件）

大文件应使用流式 API——**有状态句柄**保持文件位置，分块读写，内存占用与块大小成正比（运行时缓冲 256 KiB）。

**背压机制：显式响应**——每个操作 `await` 结果后才发起下一块；块大小由 `read(maxBytes?)` 指定或调用方控制（建议 ≥256 KiB）。

```ts
import { createReadStream, createWriteStream } from 'yeow-api';

// 读流（默认 Uint8Array；也可 for await）
const r = await createReadStream('big.bin');        // fs:plugin.openRead
const chunk: Uint8Array | null = await r.read();    // 默认 1 MiB；null = EOF
await r.close();

// 写流
const w = await createWriteStream('out.bin');       // fs:plugin.openWrite
await w.write(chunk);                               // 等到写入完成（显式响应背压）
await w.end();                                      // 冲刷缓冲并关闭
```

### 选项与编码（创建时固定）

```ts
createReadStream(path, options?: ReadStreamOptions): Promise<ReadStream>
createWriteStream(path, options?: WriteStreamOptions): Promise<WriteStream>

interface ReadStreamOptions {
  start?: number;      // 字节偏移区间（start 含、end 含；缺省 = 全文件）
  end?: number;
  encoding?: 'utf8' | 'base64';  // 缺省 = Uint8Array；指定后 read() 返回 string
}
interface WriteStreamOptions {
  flags?: 'w' | 'a' | 'wx';       // 打开模式
  encoding?: 'utf8' | 'base64';   // 字符串 chunk 的编码（缺省 utf8）
}
```

```ts
// 只读文件的第 100..199 字节（如日志尾部、分片读取）
const r = await createReadStream('log.txt', { start: 100, end: 199 });

// 按 UTF-8 文本分块读取（跨块的多字节字符会正确拼接，不会截断）
const t = await createReadStream('config.json', { encoding: 'utf8' });
const line: string | null = await t.read();

// 按 Base64 字符串分块读取
const b64 = await createReadStream('blob.bin', { encoding: 'base64' });
const part: string | null = await b64.read();

// 写流字符串 chunk：默认 UTF-8；'base64' 表示先解码再写字节
const w = await createWriteStream('out.txt', { encoding: 'utf8' });
await w.write('中文文本');                     // 写入 UTF-8 字节
await w.end();

const wb = await createWriteStream('out.bin', { encoding: 'base64' });
await wb.write(base64str);                     // 解码为字节后写入
await wb.end();

// 追加模式（等效 appendFile 的流式版）
const a = await createWriteStream('events.log', { flags: 'a' });
await a.write(line);
await a.end();

// 排他创建：文件已存在则创建失败（err）——适合"仅首次写入"的初始化场景
const init = await createWriteStream('state.json', { flags: 'wx' });
```

> **编码创建时固定，运行时不支持修改**：读流/写流创建后 `encoding` 只读，没有 `setEncoding()`；需要切换编码就再创建一个流。

| `flags` | 行为 |
|---|---|
| `'w'`（默认） | 覆盖（不存在则创建） |
| `'a'` | 追加（不存在则创建） |
| `'wx'` | 排他创建（已存在报错） |

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
for await (const chunk of r) { /* 逐块处理 */ }
await r.close();

// 文本模式：for await 得到 string 块
const t = await createReadStream('data.txt', { encoding: 'utf8' });
for await (const s of t) { /* string 块 */ }
```

### WriteStream

```ts
interface WriteStream {
  readonly encoding?: 'utf8' | 'base64';           // 创建时固定
  write(chunk: Uint8Array | string): Promise<void>; // 写入完成即 resolve（背压点）
  end(): Promise<void>;                             // 冲刷 + 关闭（此后不可 write）
  close(): Promise<void>;
}
```

### 权限

流操作按访问段继承 fs 权限节点：`fs:plugin.openRead/read/openWrite/write/end/close`（plugin 段免声明）；`fs:server.*` / `fs:outer.*` 需声明（`fs:server.*` 等通配已覆盖流操作）。

> 流句柄需显式 `close()` / `end()`；卸载、热重载时运行时自动关闭全部残留句柄。

## path 工具

路径拼接与解析（POSIX 风格，与平台无关）：

```js
import { path } from 'yeow-api';

path.join('a', 'b', 'c')       // "a/b/c"
path.basename('/a/b/c.txt')    // "c.txt"
path.dirname('/a/b/c.txt')     // "/a/b"
path.extname('data.json')      // ".json"
```

## 示例

```js
// 读写 JSON 配置（plugin 段节点，免权限）——文本需显式 utf8
const cfg = JSON.parse(await fs.readFile('config.json', 'utf8'));
cfg.version = 2;
await fs.writeFile('config.json', JSON.stringify(cfg, null, 2)); // string 默认 utf8

// 服务器级文件（需 fs:server.*）
const props = fs.server.readFileSync('server.properties', 'utf8');

// 二进制图片：默认 Uint8Array，直接读写
const icon = await fs.readFile('icon.png');        // Uint8Array
await fs.writeFile('backup.png', icon);

// 显式 base64 字符串读写
const b64 = await fs.readFile('blob.bin', 'base64');
await fs.writeFile('blob-copy.bin', b64, 'base64');
```
