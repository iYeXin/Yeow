# HTTP 通道

HTTP 客户端和简易 HTTP 服务端。

> **权限**：http 通道**默认拒绝**，插件必须在 `yeow.json` 的 `permissions` 中声明 `http:*`（全部）或具体节点（如 `http:requestAsync`、`http:listen`）。未声明调用返回 `Permission denied: http:<op>`。注意：`fetch` 底层使用 `requestAsync`，同样受约束。

## 客户端请求

### 同步请求 (`request`)

- **请求**：
```json
{
  "url": "https://api.example.com/data",
  "method": "GET",
  "headers": { "Authorization": "Bearer xxx" },
  "body": "<body>"
}
```
- **返回**：
```json
{
  "status": 200,
  "headers": { "content-type": "application/json", ... },
  "body": "<response body>"
}
```

| 字段 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `url` | 是 | — | 请求 URL |
| `method` | 否 | `GET` | HTTP 方法 (`GET`, `POST`, `PUT`, `DELETE`) |
| `headers` | 否 | `{}` | 请求头 |
| `body` | 否 | `null` | 请求体字符串 |

### 异步请求 (`requestAsync`)

`fetch()` 函数的底层实现。与同步请求格式相同，增加 `cb` 字段：

```json
{
  "url": "...",
  "method": "GET",
  "headers": {},
  "body": null,
  "responseType": "text",
  "cb": "<callbackId>"
}
```

- **可选的 `responseType`**：`"text"`（默认）或 `"base64"`。
- 结果通过 `cb` 通道异步投递，格式为 `{ "status": <int>, "headers": {...}, "body": "<body>" }`。

---

## HTTP 服务端

### `listen`

启动 HTTP 服务器。

- **请求**：
```json
{
  "pluginName": "<name>",
  "callbackId": "<cbId>",
  "port": <int>
}
```
- **返回**：`{ "serverId": "<serverId>", "port": <int> }` (port=0 时返回实际分配端口)

有请求到达时，通过 `cb` 向 JS 投递回调数据：

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

响应 HTTP 请求。

- **请求**：
```json
{
  "serverId": "<serverId>",
  "connId": "<connId>",
  "status": 200,
  "headers": { "Content-Type": "application/json" },
  "body": "<response body>"
}
```
- **返回**：`"true"`

**二进制响应**：`bodyBase64` 字段（base64 编码的原始字节）与 `body` 互斥、优先——存在时按字节原样写出（Content-Length = 解码后字节数），用于资源包等二进制文件：

```json
{
  "serverId": "<serverId>",
  "connId": "<connId>",
  "status": 200,
  "headers": { "Content-Type": "application/zip", "Content-Disposition": "attachment; filename=\"rp.zip\"" },
  "bodyBase64": "<base64 编码的二进制内容>"
}
```

### `close`

关闭 HTTP 服务器。

- **请求**：`{ "serverId": "<serverId>" }`
- **返回**：`"true"`
