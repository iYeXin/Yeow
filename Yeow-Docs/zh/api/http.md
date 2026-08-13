# HTTP API

```js
import { request } from 'yeow-api';
```

> [!WARNING]
> **全局 `fetch` 依赖 `http:requestAsync` 权限**：`fetch` 底层走 http 通道的 `requestAsync`，未在 `yeow.config.json` 的 `permissions` 中声明 `"http:*"`（或 `"http:requestAsync"`）时，`fetch` 会以 `Permission denied: http:requestAsync` 拒绝。`request` 同理。详见 [权限与原生服务可信性](../permissions.md)。

## request(url, options?)

发出 HTTP 请求（**异步**，不阻塞 JS 线程——底层走 `http:requestAsync` 通道）。推荐用于事件处理器与高频场景。

```ts
request(url: string, options?: RequestOptions): Promise<HttpResponse>
```

### RequestOptions

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `method` | `string` | `"GET"` | `GET` / `POST` / `PUT` / `DELETE` |
| `headers` | `Record<string, string>` | `{}` | 请求头 |
| `body` | `string` | | 请求体（POST/PUT） |
| `responseType` | `string` | `"text"` | 响应体格式。`"text"`（UTF-8 文本）或 `"base64"`（二进制，Base64 编码） |

### HttpResponse

```ts
{
    status: number          // HTTP 状态码
    headers: object         // 响应头
    body: string            // 响应体
}
```

## requestSync(url, options?)

发出 HTTP 请求（**同步，阻塞 JS 线程**直到响应返回——底层走 `http:request` 通道）。

```ts
requestSync(url: string, options?: RequestOptions): HttpResponse
```

> ⚠ **阻塞语义**：`requestSync` 阻塞期间 JS 线程无法处理事件、命令与回调——**可能触发 `event.timeout` 运行时告警**。仅适合低频、非事件上下文（如启动初始化）；事件处理器或高频场景请使用 `request`（异步）或全局 `fetch`。

### 示例

```js
// GET 请求（异步，推荐）
const res = await request('https://api.example.com/data');
console.log(res.status, res.body);

// 同步请求（阻塞 JS 线程，低频场景）
const res2 = requestSync('https://api.example.com/data');
console.log(res2.status, res2.body);

// POST 请求
await request('https://api.example.com/submit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key: 'value' }),
});
```

## 全局 fetch

```js
const res = await fetch('https://api.example.com/data');
const json = await res.json();
const text = await res.text();
console.log(res.status, res.ok);
```

全局内置，无需 import。底层使用 Java HttpClient。返回标准 `Response` 对象。
