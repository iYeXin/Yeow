# HTTP API

```js
import { request, bytesToString } from 'yeow-api';
```

> [!WARNING]
> **`request` / 全局 `fetch` 依赖 `http:requestAsync` 权限**：底层走 http 通道的 `requestAsync`，未在 `yeow.config.json` 的 `permissions` 中声明 `"http:*"`（或 `"http:requestAsync"`）时会被拒绝。详见 [权限与原生服务可信性](../permissions.md)。

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
| `body` | `string \| Uint8Array` | | 请求体（POST/PUT；与 `fs.writeFile` 同语义：`Uint8Array` 直接二进制，字符串按 `encoding` 解释） |
| `encoding` | `'utf8' \| 'base64'` | `'utf8'` | **请求体**（body 为字符串时）解释：`'utf8'` 文本 / `'base64'` base64 二进制 |
| `responseEncoding` | `'utf8' \| 'base64'` | 缺省 | **响应体**形态：缺省 `Uint8Array`（原始字节，可在收到后自行解码）；`'utf8'` → UTF-8 字符串；`'base64'` → base64 字符串 |
| `timeout` | `number` | 运行时默认 | 超时毫秒数（连接与读取；默认连接 5s / 读取 10s） |

### HttpResponse

```ts
{
    status: number               // HTTP 状态码
    headers: Record<string, string>  // 响应头（键小写）
    body: Uint8Array | string    // 响应体：缺省 Uint8Array；responseEncoding 指定时为字符串
}
```

### 示例

```js
// GET（默认 Uint8Array，可在收到后自行解码）
const res = await request('https://api.example.com/data');
console.log(res.status);
const text = await bytesToString(res.body);       // Uint8Array → UTF-8 文本（util 通道）
const text2 = new TextDecoder().decode(res.body); // 或引擎原生解码

// 直接以文本 / base64 接收
const t = await request('https://api.example.com/text', { responseEncoding: 'utf8' });
const b = await request('https://api.example.com/img.png', { responseEncoding: 'base64' });

// POST（字符串体）+ 超时
await request('https://api.example.com/submit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key: 'value' }),
    timeout: 3000,
});

// POST 二进制体（Uint8Array 直接发送）
const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
await request('https://api.example.com/upload', {
    method: 'POST',
    headers: { 'Content-Type': 'image/png' },
    body: png,
});

// base64 字符串体（与 fs.writeFile 的 encoding: 'base64' 同语义）
await request('https://api.example.com/upload', {
    method: 'POST',
    body: 'iVBORw0KGgo=',           // base64 编码的二进制
    encoding: 'base64',
});
```

## 全局 fetch

```js
const res = await fetch('https://api.example.com/data');
const text = await res.text();        // 经 TextDecoder 触发 UTF-8 解码
const json = await res.json();        // 解码后 JSON.parse
const b64 = await res.base64();       // 原始 base64（零解码）
const bytes = await res.bytes();      // Uint8Array
const ab = await res.arrayBuffer();   // ArrayBuffer
console.log(res.status, res.ok);
```

全局内置，无需 import。底层使用 Java HttpClient，**响应始终以 base64 缓存原始字节**，`text()` / `json()` 经 [TextDecoder](../specifications/runtime/index.md#textencoder--textdecoder) 解码，`base64()` / `bytes()` / `arrayBuffer()` 按需取用（`bytes` 为 `Uint8Array`，`arrayBuffer` 为标准 `ArrayBuffer`）。`fetch(url, { timeout: 3000 })` 指定超时毫秒数（连接与读取）。
