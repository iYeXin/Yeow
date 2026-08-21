# HTTP Channel

HTTP client and a simple HTTP server.

> **Permissions**: The http channel is **denied by default**; the plugin must declare `http:*` (all) or specific nodes (e.g. `http:requestAsync`, `http:listen`) in the `permissions` of `yeow.json`. An undeclared call returns `Permission denied: http:<op>`. Note: `fetch` is implemented on top of `requestAsync` and is therefore also subject to this restriction.

## Client Requests

### Synchronous Request (`request`)

- **Request**:
```json
{
  "url": "https://api.example.com/data",
  "method": "GET",
  "headers": { "Authorization": "Bearer xxx" },
  "body": "<body>"
}
```
- **Return**:
```json
{
  "status": 200,
  "headers": { "content-type": "application/json", ... },
  "body": "<response body>"
}
```

| Field      | Required | Default                                   | Description |
|------------|----------|-------------------------------------------|-------------|
| `url`      | Yes      | —                                         | Request URL |
| `method`   | No       | `GET`                                     | HTTP method (`GET`, `POST`, `PUT`, `DELETE`) |
| `headers`  | No       | `{}`                                      | Request headers |
| `body`     | No       | `null`                                    | Request body string; with `encoding: 'base64'` it is treated as base64-encoded binary (written out as-is after decoding, same semantics as `respond`) |
| `encoding` | No       | `'utf8'`                                  | Interpretation of the request body: `'utf8'` (default, text) / `'base64'` (base64 binary) |
| `timeout`  | No       | `0` (runtime default: connect 5s / read 10s) | Timeout in milliseconds (connect and read) |

On failure it returns `{ "err": "<message>" }` (common error format).

### Asynchronous Request (`requestAsync`)

The underlying implementation of the `fetch()` function. Same format as the synchronous request (including the optional `timeout`), plus a `cb` field:

```json
{
  "url": "...",
  "method": "GET",
  "headers": {},
  "body": null,
  "encoding": "utf8",
  "responseType": "text",
  "timeout": 3000,
  "cb": "<callbackId>"
}
```

- **Optional `responseType`**: `"text"` (default, UTF-8 decoded) or `"base64"` (raw bytes carried as base64).
- The result is delivered asynchronously through the `cb` channel in the format `{ "status": <int>, "headers": {...}, "body": "<body>" }`; on failure it is `{ "err": "<message>" }`.

---

## HTTP Server

### `listen`

Starts an HTTP server.

- **Request**:
```json
{
  "pluginName": "<name>",
  "callbackId": "<cbId>",
  "port": <int>
}
```
- **Return**: `{ "serverId": "<serverId>", "port": <int> }` (when port=0, returns the actually allocated port)

When a request arrives, callback data is delivered to JS through `cb`:

```json
{
  "connId": "<connId>",
  "serverId": "<serverId>",
  "method": "GET",
  "path": "/api/hello",
  "query": "name=test",
  "headers": { "host": "localhost:8080" },
  "body": "<request body>"
}
```

### `respond`

Responds to an HTTP request.

- **Request**:
```json
{
  "serverId": "<serverId>",
  "connId": "<connId>",
  "status": 200,
  "headers": { "Content-Type": "application/json" },
  "body": "<response body>",
  "encoding": "utf8"
}
```
- **Return**: `"true"`

- `body`: response body string. `encoding` is `"utf8"` (default, text) or `"base64"` (the body is treated as base64-encoded **binary** data, decoded and written out byte-for-byte — Content-Length = decoded byte count, used for binary files such as resource packs):

```json
{
  "serverId": "<serverId>",
  "connId": "<connId>",
  "status": 200,
  "headers": { "Content-Type": "application/zip", "Content-Disposition": "attachment; filename=\"rp.zip\"" },
  "body": "<base64-encoded binary content>",
  "encoding": "base64"
}
```

> **Unresponded timeout (2026-08-13)**: If the JS side does not call `respond` within **30 seconds** of receiving the request callback (handler crash/missed call), the runtime automatically closes the connection with `503` and removes it (preventing connection and memory leaks); a periodic sweep (10s interval) starts on the first `listen` call.

### `close`

Closes an HTTP server.

- **Request**: `{ "serverId": "<serverId>" }`
- **Return**: `"true"`
