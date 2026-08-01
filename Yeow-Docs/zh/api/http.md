# HTTP API

```js
import { request } from 'yeow-api';
```

> [!WARNING]
> **全局 `fetch` 依赖 `http:requestAsync` 权限**：`fetch` 底层走 http 通道的 `requestAsync`，未在 `yeow.config.json` 的 `permissions` 中声明 `"http:*"`（或 `"http:requestAsync"`）时，`fetch` 会以 `Permission denied: http:requestAsync` 拒绝。`request` 同理。详见 [快速开始 - 权限声明](../getting-started.md#权限声明)。

## request(url, options?)

发出 HTTP 请求（异步）。

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

### 示例

```js
// GET 请求
const res = await request('https://api.example.com/data');
console.log(res.status, res.body);

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
