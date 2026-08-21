# HTTP API

```js
import { request, bytesToString } from 'yeow-api';
```

> [!WARNING]
> **`request` / global `fetch` depends on `http:requestAsync` permission**: Underlying uses http channel's `requestAsync`, will be refused when not declared `"http:*"` (or `"http:requestAsync"`) in `yeow.config.json`'s `permissions`. See [Permissions & Native Service Trust](../permissions.md) for details.

## request(url, options?)

Send HTTP request (**async**, doesn't block JS thread — underlying uses `http:requestAsync` channel). Recommended for event handlers and high-frequency scenarios.

```ts
request(url: string, options?: RequestOptions): Promise<HttpResponse>
```

### RequestOptions

| Field | Type | Default | Description |
| ----- | ---- | ------- | ----------- |
| `method` | `string` | `"GET"` | `GET` / `POST` / `PUT` / `DELETE` |
| `headers` | `Record<string, string>` | `{}` | Request headers |
| `body` | `string \| Uint8Array` | | Request body (POST/PUT; same semantics as `fs.writeFile`: `Uint8Array` direct binary, string interpreted by `encoding`) |
| `encoding` | `'utf8' \| 'base64'` | `'utf8'` | **Request body** (when body is string) interpretation: `'utf8'` text / `'base64'` base64 binary |
| `responseEncoding` | `'utf8' \| 'base64'` | Default | **Response body** form: Default `Uint8Array` (raw bytes, can self-decode after receipt); `'utf8'` → UTF-8 string; `'base64'` → base64 string |
| `timeout` | `number` | Runtime default | Timeout milliseconds (connection and read; default connection 5s / read 10s) |

### HttpResponse

```ts
{
    status: number               // HTTP status code
    headers: Record<string, string>  // Response headers (keys lowercase)
    body: Uint8Array | string    // Response body: Default Uint8Array; string when responseEncoding specified
}
```

### Example

```js
// GET (default Uint8Array, can self-decode after receipt)
const res = await request('https://api.example.com/data');
console.log(res.status);
const text = await bytesToString(res.body);       // Uint8Array → UTF-8 text (util channel)
const text2 = new TextDecoder().decode(res.body); // Or engine native decode

// Receive directly as text / base64
const t = await request('https://api.example.com/text', { responseEncoding: 'utf8' });
const b = await request('https://api.example.com/img.png', { responseEncoding: 'base64' });

// POST (string body) + timeout
await request('https://api.example.com/submit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key: 'value' }),
    timeout: 3000,
});

// POST binary body (Uint8Array direct send)
const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
await request('https://api.example.com/upload', {
    method: 'POST',
    headers: { 'Content-Type': 'image/png' },
    body: png,
});

// Base64 string body (same semantics as fs.writeFile's encoding: 'base64')
await request('https://api.example.com/upload', {
    method: 'POST',
    body: 'iVBORw0KGgo=',           // Base64 encoded binary
    encoding: 'base64',
});
```

## Global fetch

```js
const res = await fetch('https://api.example.com/data');
const text = await res.text();        // Via TextDecoder triggers UTF-8 decode
const json = await res.json();        // After decode JSON.parse
const b64 = await res.base64();       // Raw base64 (zero decode)
const bytes = await res.bytes();      // Uint8Array
const ab = await res.arrayBuffer();   // ArrayBuffer
console.log(res.status, res.ok);
```

Globally built-in, no import needed. Underlying uses Java HttpClient, **response always caches raw bytes as base64**, `text()` / `json()` decoded via [TextDecoder](../specifications/runtime/index.md#textencoder--textdecoder), `base64()` / `bytes()` / `arrayBuffer()` accessed on-demand (`bytes` is `Uint8Array`, `arrayBuffer` is standard `ArrayBuffer`). `fetch(url, { timeout: 3000 })` specifies timeout milliseconds (connection and read).