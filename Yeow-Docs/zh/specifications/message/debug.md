# Debug 通道

调试、错误上报和连接诊断。

## `reportError`

手动上报 JS 错误。

- **请求**：`{ "t": "reportError", "p": { "message": "<msg>", "stack": "<stack>", "fileName": "<f>", "lineNumber": <int>, "columnNumber": <int>, "context": "<text>" } }`
- **返回**：`null`

运行时在开发模式下将错误转发给开发服务器进行 source-map 解析。

## `pong`

调试 ping 响应。运行时可通过发送 `DEBUG ping` 消息测量往返延迟（预留，当前未自动触发）。

- **请求**：`{ "t": "pong" }`
- **返回**：`null`

## `DEBUG` 消息（运行时 → JS）

运行时通过消息队列向 JS 投递 `DEBUG` 类型消息。JS 端 `$hm` 在收到 `t === 'DEBUG'` 时处理。

当前支持：
- `{ "t": "DEBUG", "p": "ping" }` → JS 应立即响应 `$send('debug', { t: 'pong' })`（预留，用于未来的性能检测与评估）
