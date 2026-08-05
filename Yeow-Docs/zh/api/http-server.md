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
server.get(path, handler)       // 注册 GET 路由
server.post(path, handler)      // 注册 POST 路由
server.put(path, handler)       // 注册 PUT 路由
server.del(path, handler)       // 注册 DELETE 路由
server.close()                  // 关闭服务器
```

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
