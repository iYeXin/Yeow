# 文件系统 API

```js
import { fs } from 'yeow-api';
```

所有路径相对于 `plugins/<插件名>/`，自动阻止 `../` 穿越。

> **权限**：fs 通道默认拒绝，必须在 `yeow.config.json` 的 `permissions` 中声明（构建后写入 `yeow.json`）：`"fs:*"`（全部）或按节点声明（如 `"fs:readFile"`、`"fs:writeFile"`）。未声明调用返回错误 `Permission denied: fs:<op>`（异步 API 以 Promise reject 呈现）。详见 [快速开始 - 权限声明](../getting-started.md#权限声明)。

## fs.readFile(path) / fs.readFileSync(path)

读取文件为 UTF-8 字符串。

```ts
fs.readFile('config.json'): Promise<string>
fs.readFileSync('config.json'): string
```

## fs.readFileBase64(path) / fs.readFileBase64Sync(path)

读取文件为 Base64 编码字符串。

```ts
fs.readFileBase64('image.png'): Promise<string>
fs.readFileBase64Sync('image.png'): string
```

## fs.writeFile(path, data) / fs.writeFileSync(path, data)

写入 UTF-8 文本到文件。

```ts
fs.writeFile('log.txt', 'hello'): Promise<void>
fs.writeFileSync('log.txt', 'hello'): void
```

## fs.writeFileBase64(path, data) / fs.writeFileBase64Sync(path, data)

写入 Base64 解码后的二进制数据到文件。

```ts
fs.writeFileBase64('out.png', base64str): Promise<void>
fs.writeFileBase64Sync('out.png', base64str): void
```

## fs.appendFile(path, data) / fs.appendFileSync(path, data)

追加写入，文件不存在时自动创建。

```ts
fs.appendFile('log.txt', 'new line\n'): Promise<void>
fs.appendFileSync('log.txt', 'new line\n'): void
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
// 读写 JSON 配置
const cfg = await fs.readFile('config.json');
const data = JSON.parse(cfg);
data.version = 2;
await fs.writeFile('config.json', JSON.stringify(data, null, 2));

// Base64 图片
const b64 = await fs.readFileBase64('icon.png');
await fs.writeFileBase64('backup.png', b64);
```
