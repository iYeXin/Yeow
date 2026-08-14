# Debug 通道

调试、错误上报和连接诊断。

## `reportError`

手动上报 JS 错误。

- **请求**：`{ "t": "reportError", "p": { "message": "<msg>", "stack": "<stack>", "fileName": "<f>", "lineNumber": <int>, "columnNumber": <int>, "context": "<text>" } }`
- **返回**：`null`

运行时在开发模式下将错误转发给开发服务器进行 source-map 解析。

## `pong`

调试 ping 响应。运行时通过消息队列向 JS 投递 `DEBUG ping` 心跳，JS 端响应此操作测量往返延迟。

- **请求**：`{ "t": "pong" }`
- **返回**：`null`

## `command`（运行时内部测试节点）

运行时内部测试入口，JS 侧可提交。生产环境下此节点不可用。具体指令属运行时内部实现，不在此文档列出。不对稳定性做出任何保证。任何插件不应依赖其内部行为。

- **请求**：`{ "t": "command", "p": { "cmd": "<指令名>", ...指令参数 } }`

## `DEBUG` 消息（运行时 → JS）

运行时通过消息队列向 JS 投递 `DEBUG` 类型消息。JS 端 `$hm` 在收到 `t === 'DEBUG'` 时处理。

当前支持：

- `{ "t": "DEBUG", "p": "ping" }` → JS 应立即响应 `$send('debug', { t: 'pong' })`

**心跳检测**：运行时周期性（默认每 1 秒一个窗口）向每个插件 JS 线程发送一次 `DEBUG ping`，并记录 pong 往返时间。单次往返超过 `latency-warn-threshold-ms`（默认 200ms）触发 `heartbeat.timeout` 警告；连续 `suspend-warn-seconds`（默认 30s）无任何响应升级为 `plugin.hung`（线程挂起）。阈值在运行时 `config.yml` 的 `profile` 段配置，详见 [运行时警告指南](../../runtime-warning.md)。
