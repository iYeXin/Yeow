# Worker 通道

Worker（虚拟插件）通道——主插件 JS 侧经 `$_send('worker', ...)` 控制其 Worker 的创建/卸载/消息/重载。

> **内部实现**：Worker 通道是运行时内部能力（配合 yeow-api 的 `createWorker`），不属插件公开权限模型（不受权限约束）。

## 消息格式

```json
// 注册 Worker（仅注册到注册表并返回句柄——不启动；worker.load() 才执行启动）
{ "t": "create", "p": { "name": "<worker名>", "code": "<代码>" | "entry": "<资源路径>", "msgCb": "<主插件侧回调id>", "cb": "<回调id>" } }
// 启动（注册实体 → init.js → worker-inject.js → Worker 代码 → INIT → LOAD → 就绪）
{ "t": "load", "p": { "name": "<worker名>", "cb": "<回调id>" } }
// 卸载（物理销毁 JS 上下文并清理事件/命令/服务/任务；句柄保留，可重新 load）
{ "t": "unload", "p": { "name": "<worker名>", "cb": "<回调id>" } }
// 发送消息给 Worker（未 load 时报错）
{ "t": "post", "p": { "name": "<worker名>", "msg": <任意 JSON>, "cb": "<回调id>" } }
// 重载（需已 load）
{ "t": "reload", "p": { "name": "<worker名>", "code" | "entry", "cb": "<回调id>" } }
```

**回调**：含 `cb` 时异步执行，完成/失败经 `{"t":"cb","p":"<cbId>","r":"true"|{"err":...}}` 投递（`r` 为 JSON 字符串或对象）。

**Worker → 主插件**（Worker 线程内部处理，不经过主插件通道路由）：

```json
{ "t": "postToMain", "p": { "msg": <任意 JSON> } }
```

运行时投递到主插件 JS 侧该 Worker 的 `onMessage` 回调（`{"t":"cb","p":"<msgCb>","r":<msg>}`）。

## 生命周期

| 事件 | 行为 |
|------|------|
| `create` | 仅注册（构造句柄，不启动）；重复/非法名报错 |
| `load` | 注册实体（plugins map + profiler）→ 构造执行单元（独立 QuickJS 上下文 + 线程）→ `INIT` → `LOAD` → 就绪后回调；已加载为 no-op |
| `unload` | 发送 `DISABLE` → 等待退出（5s 强制）→ 清理事件/命令/服务/任务 → 注销 profiler——**句柄保留，可重新 `load`** |
| `reload` | 发送 `RELOAD` → 旧上下文销毁 → 新代码重新加载（`INIT` + `LOAD`）；需已 load |
| 主插件卸载/热重载 | **连带卸载**全部依附 Worker（彻底清理，句柄随之销毁） |

## 错误

Worker 的 JS 错误经 `debug` 通道回传（与插件一致），消息携带 `origin` 字段：

```json
{ "t": "reportError", "p": { "origin": "<worker名>", "message": "...", "stack": "...", "fileName": "...", "lineNumber": 1, "columnNumber": 1 } }
```

dev-server 按 `origin` 反解对应 Worker 的 source-map（`JS Error in Worker <name>`）。主插件错误 `origin` 为 `"main"`。

## 约束

- **Worker 不能创建新的 Worker**：Worker 内 `$_send('worker', ...)` 仅接受 `postToMain`，其余返回 `{"err":"workers cannot create workers"}`
- Worker 与主插件**共享数据目录 / 权限 / 资源**（fs 的 plugin 级 base、assets 命名空间一致）
- Worker 以 `<主插件>.<worker名>` 注册为插件实体（事件/命令/服务/调度器独立）；`/yeow` 管理命令不覆盖虚拟插件
