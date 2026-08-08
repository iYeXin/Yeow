# HTTP 服务器 API

`yeow-utils` 提供高层 `createServer`，`yeow-api` 提供底层 `listen`/`respond`/`close`。

```js
import { createServer } from 'yeow-utils';
import type { Server, RouteRequest } from 'yeow-utils';
```

> **权限**：HTTP 服务器属于 http 通道，默认拒绝——需在 `yeow.config.json` 的 `permissions` 中声明 `"http:*"`（或 `"http:listen"` 等节点），否则 `listen` 返回 `Permission denied`。

创建 HTTP 服务器，支持路由和自动响应。

## createServer(port?)

创建 HTTP 服务器实例（同步）。

```ts
createServer(port?: number): Server
```

`port` 默认 0（随机端口）。

### Server 实例

```ts
server.port: number             // 实际端口号
server.use(mw)                  // 注册通用中间件（洋葱模型）
server.get(path, handler)       // 注册 GET 路由
server.post(path, handler)      // 注册 POST 路由
server.put(path, handler)       // 注册 PUT 路由
server.del(path, handler)       // 注册 DELETE 路由
server.mount(dir, prefix?)      // 挂载静态文件目录（插件数据目录）
server.close()                  // 关闭服务器
```

**执行顺序**：`use` / 路由 / `mount` 按**注册顺序**组成中间件链（洋葱模型）——每个层可返回响应（短路）或调用 `next()` 进入下一层；路由 handler 不返回时同样继续后续层；全部层未产生响应 → 404。

### 中间件（洋葱模型）

```js
import { createServer } from 'yeow-utils';

const app = createServer(8080);

// 日志中间件：先记录请求，next() 进入下一层，返回后记录耗时
app.use(async (req, next) => {
    const t = Date.now();
    const result = await next();
    console.log(`${req.method} ${req.path} — ${Date.now() - t}ms`);
    return result;              // 透传下层响应
});

// 鉴权中间件：不满足条件直接返回响应（短路，不再进入后续层）
app.use((req, next) => {
    if (!req.headers['x-token']) {
        return { status: 401, body: 'Unauthorized' };
    }
    return next();
});

app.get('/api/data', (req) => ({ body: JSON.stringify({ ok: true }) }));
```

- `next()` 调用链中下一层，返回其响应（无下一层时 `undefined`）
- 中间件返回响应对象/字符串 → 短路返回；返回 `undefined`（或不返回）→ 等效 `next()`
- 类型：`Middleware = (req, next) => string | ResponseBody | undefined | Promise<...>`（`NextFn`）

### 静态文件挂载（mount）

`mount(dir, prefix?)` 把**插件数据目录**（`plugins/<插件名>/`）下的目录挂载为静态文件服务（base64 二进制传输 + Content-Type 按扩展名推断）：

```js
import { createServer } from 'yeow-utils';

const app = createServer(8080);

app.mount('web/');                  // /index.html → plugins/<插件名>/web/index.html
app.mount('assets/web/', '/static'); // /static/xxx → plugins/<插件名>/assets/web/xxx

app.get('/api/data', () => ({ body: 'ok' }));
```

- `dir`：插件数据目录下的目录（可带尾 `/`）；`prefix`：URL 前缀（默认 `/`）
- 支持常见类型（html/css/js/json/svg/png/jpg/gif/webp/woff2/zip/pdf/wasm 等），未知扩展名回退 `application/octet-stream`
- **路径穿越防护**：含 `..` 段的请求路径被拒绝（继续后续层 → 404）
- 文件不存在时继续后续层（可被后续路由/404 接管）
- 静态文件建议放入 `assets/` 打包（`assetsReadBase64` + 缓存）或挂载由服主放入数据目录的文件（`fs` 通道 plugin 级免权限）

路由 handler 接收 `RouteRequest` 对象：

```ts
{
    connId: string
    serverId: string
    method: string
    path: string
    query: string | undefined   // 查询串；无查询参数时为 undefined——解析前需容错（如 (req.query ?? '').split('&')）
    headers: object
    body: string
    params: Record<string, string>   // 路径变量
}
```

### 路径变量

路由路径支持 `:name` 语法声明变量：

```js
app.get('/users/:id', (req) => {
    // GET /users/42  →  req.params.id === "42"
    // GET /users/abc →  req.params.id === "abc"
    return `User ${req.params.id}`;
});

app.get('/posts/:postId/comments/:commentId', (req) => {
    // GET /posts/10/comments/5 →  req.postId === "10", req.commentId === "5"
});
```

### handler 返回值

handler 可以返回：

- 字符串 → 自动作为 body 响应，status 200
- `{status, body, headers}` → 自定义响应
- `undefined` → 不自动响应，可通过 `respond(...)` 手动响应

**handler 支持 async**（返回 Promise 时自动 `await`，完成后才响应——不会把 Promise 当作 body 回传）；handler 内抛错时自动响应 500：

## 示例

```js
const app = createServer(8080);

app.get('/api/hello', (req) => {
    return { message: 'Hello World' };
});

app.get('/api/users/:id', (req) => {
    return `User ID: ${req.params.id}`;
});

app.post('/api/submit', (req) => {
    console.log('Received:', req.body);
    return { status: 201, body: 'Created' };
});

console.log(`Server on port ${app.port}`);
// 关闭: app.close();
```

## 底层 API

如需手动控制响应流程，使用底层 `listen` / `respond` / `close`：

```js
import { listen, respond, close } from 'yeow-api';

const { serverId, port } = listen((req) => {
    respond(req.serverId, req.connId, {
        status: 200,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ hello: 'world' }),
    });
}, 8080);

close(serverId);
```

### 二进制响应（base64）

`respond` 支持 `bodyBase64`——base64 编码的**二进制**响应体（与 `body` 互斥，优先）。典型场景：从 `assets` 读取资源包等二进制文件并暴露下载 URL。

**推荐使用 `yeow-utils` 的 `createServer`**（路由 + 自动响应）：

```js
import { createServer } from 'yeow-utils';
import { assetsReadBase64 } from 'yeow-api';

let cachedPack = null;   // 缓存：每次请求都从 .zip 内解压读取较耗时

const app = createServer(17835);

app.get('/resourcepack', async (req) => {
    if (!cachedPack) {
        cachedPack = await assetsReadBase64('pack/resourcepack.zip');   // 首次读取并缓存
    }
    return {
        bodyBase64: cachedPack,
        headers: {
            'content-type': 'application/zip',
            'content-disposition': 'attachment; filename="resourcepack.zip"',
        },
    };
});

// 客户端/玩家下载: http://<服务器>:17835/resourcepack
```

> **缓存提示**：`assetsReadBase64` 每次调用都会从插件包（.zip）中解压读取目标文件——对资源包等大文件，请**缓存**读取结果（如上面的 `cachedPack`），避免每个请求都重复解压。

> **端口告知**：HTTP 监听端口需**插件作者自行保证可用**（未被占用、服务器防火墙放行）——请在插件文档/说明中**告知服主监听的端口号**，由服主配置防火墙与转发。

### 完整闭环：资源包下载并发送给玩家

```js
import { createServer } from 'yeow-utils';
import { assetsReadBase64, Player, fs, eventOn } from 'yeow-api';

// ① 暴露资源包下载 URL（见上例，缓存 base64）
const app = createServer(17835);
app.get('/resourcepack', async (req) => {
    if (!cachedPack) cachedPack = await assetsReadBase64('pack/resourcepack.zip');
    return { bodyBase64: cachedPack, headers: { 'content-type': 'application/zip' } };
});

// ② 公网地址由服主在插件数据目录配置（plugins/<插件名>/config.json）
//    { "publicUrl": "https://mc.example.com:17835" }   ← 服主填写公网可达的 IP/域名 + 端口
const cfg = JSON.parse(fs.readFileSync('config.json'));

// ③ 玩家加入时发送资源包（url 指向上面暴露的下载地址）
eventOn('playerJoin', async (e) => {
    await e.player.sendResourcePack(
        cfg.publicUrl + '/resourcepack',              // url —— 从配置读取的公网地址
        hash,                                          // hash —— SHA-1（可选，建议提供以校验完整性）
        { text: '<yellow>请下载服务器资源包</yellow>' },  // prompt —— Message 对象（也支持纯字符串）
        true,                                          // force —— 是否强制
    );
});
```

`sendResourcePack` 的 `hash` 为 **SHA-1 十六进制**（可选但建议提供，客户端据此校验完整性）；`hash`/`prompt`/`force` 均可省略。`hash` 需插件自行提供（如构建时对资源包文件计算后写入配置）。

> **公网地址配置**：`url` 必须由**玩家客户端**可访问——插件作者应让**服主在配置中填写公网可达的 IP 或域名**，以及可用端口，并在插件文档中说明：端口需放行防火墙、如在内网/家宽需做端口转发。

> **⚠ 生产环境建议**：资源包**理论上应当通过 CDN 分发**（如对象存储 + CDN、GitHub Releases 等）。**本示例（插件自带 HTTP 服务器直出）仅用于临时场景**，或用户自定义资源包的情形。
