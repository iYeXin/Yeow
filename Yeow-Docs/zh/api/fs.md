# 文件系统 API

```js
import { fs } from 'yeow-api';
```

## 访问级别

fs API 分**三级访问级别**，相对路径基于对应基准目录计算：

| 级别 | 基准目录 | 权限 |
| ---- | -------- | ---- |
| `fs.*`（plugin，默认） | `plugins/<插件名>/` | **无需声明** |
| `fs.server.*` | 服务器根目录（Java 进程工作目录） | 需 `fs:server.*` 或节点级 `fs:server.<op>` |
| `fs.outer.*` | 任意路径（相对路径仍基于服务器根） | 需 `fs:outer.*` 或节点级 `fs:outer.<op>` |

plugin 级自动阻止 `../` 穿越；server 级同样阻止逃逸出服务器根；outer 级无范围限制（绝对/相对路径均可）。

```js
import { fs } from 'yeow-api';

fs.readFileSync('config.json');          // plugin 级：plugins/<插件名>/config.json
fs.server.readFileSync('eula.txt');      // server 级：服务器根/eula.txt
fs.outer.readFileSync('/etc/hosts');     // outer 级：任意路径
```

> **权限**：plugin 级（`fs.*`）默认允许。`fs.server.*` / `fs.outer.*` 默认拒绝，须在 `yeow.config.json` 的 `permissions` 中声明（构建后自动计算进 `computedPermissions`）：`"fs:server.*"`（整级）或节点级（如 `"fs:server.readFile"`）。`"fs:*"` 通配整个 fs 通道（含 server/outer）。未声明调用返回错误 `Permission denied: fs:server.<op>`（异步 API 以 Promise reject 呈现）。详见 [快速开始 - 权限声明](../getting-started.md#权限声明)。

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

## 其他级别

`fs.server` 与 `fs.outer` 暴露与 `fs` 完全相同的操作集合，仅访问范围与权限不同：

```ts
fs.server.readFileSync('server.properties')   // server 级
fs.outer.writeFileSync('/tmp/data.txt', 'x')  // outer 级
```

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
// 读写 JSON 配置（plugin 级，免权限）
const cfg = await fs.readFile('config.json');
const data = JSON.parse(cfg);
data.version = 2;
await fs.writeFile('config.json', JSON.stringify(data, null, 2));

// 服务器级文件（需 fs:server.*）
const props = fs.server.readFileSync('server.properties');

// Base64 图片
const b64 = await fs.readFileBase64('icon.png');
await fs.writeFileBase64('backup.png', b64);
```
