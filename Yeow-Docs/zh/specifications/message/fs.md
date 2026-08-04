# FS 通道

文件系统操作。

> **权限**：fs 通道的节点按**访问范围命名段**区分（`plugin` / `server` / `outer`）。`plugin` 节点（插件数据目录）默认允许；`server` / `outer` 节点默认拒绝，插件必须在 `yeow.json` 的 `computedPermissions` 中声明 `fs:server.*` / `fs:outer.*`（整组）或具体节点（如 `fs:server.readFile`）。`fs:*` 通配整个 fs 通道。未声明调用返回 `Permission denied: fs:server.readFile`。

## 概念：消息节点

`t` 字段是**消息节点**，格式为 `段.操作`：

- `plugin.readFile`、`server.readFile`、`outer.readFile` 是三个**独立的节点**
- 命名段（`plugin` / `server` / `outer`）是**业务/访问范围命名**，与 `task:player.get` 中 `player` 的性质相同；权限只按完整节点（`fs:plugin.readFile` 等）考虑
- 运行时不理解命名段含义，仅做节点级通配匹配（见下）

## 调用格式

```json
{ "t": "<段>.<操作>", "p": { "path": "<path>", "data": "<data>" }, "cb": "<callbackId>" }
```

| 节点前缀             | 路径基准                          | 权限                                 |
| -------------------- | --------------------------------- | ------------------------------------ |
| `plugin.readFile` 等 | `plugins/<插件名>/`               | 免声明（默认允许）                   |
| `server.readFile` 等 | 服务器根目录（Java 进程工作目录） | 需 `fs:server.*` 或 `fs:server.<op>` |
| `outer.readFile` 等  | 任意路径（相对路径基于服务器根）  | 需 `fs:outer.*` 或 `fs:outer.<op>`   |

`path` 为相对于对应基准目录的路径；`server` 节点阻止逃逸出服务器根，`outer` 节点无范围限制。

### 异步模式

若 payload 中包含 `cb` 字段（回调 ID），操作将**异步执行**：`$send` 立即返回 `null`，操作在独立 IO 线程中执行，完成后通过 `cb` 通道投递结果。

```json
// 异步请求
{ "t": "plugin.readFile", "p": { "path": "config.json" }, "cb": "cb_42" }
// 立即返回 null
// ... 操作在 IO 线程中执行 ...
// 结果通过 cb 投递：{ "t": "cb", "p": "cb_42", "r": {"data": "..."} }
```

若 **不含** `cb` 字段，操作**同步执行**，`$send` 阻塞直到操作完成并直接返回结果。

---

## 操作列表

以下操作名在 `plugin` / `server` / `outer` 三个节点前缀下均可使用（如 `plugin.readFile`、`server.readFile`、`outer.readFile`）。

### `readFile`

- **p**：`{ "path": "<path>" }`
- **返回**：`{ "data": "<content>" }`

### `writeFile`

- **p**：`{ "path": "<path>", "data": "<content>" }`
- **返回**：`"true"` (string)

覆盖写入。

### `appendFile`

- **p**：`{ "path": "<path>", "data": "<content>" }`
- **返回**：`"true"` (string)

追加写入，文件不存在时自动创建。

### `exists`

- **p**：`{ "path": "<path>" }`
- **返回**：`"true"` | `"false"` (string)

### `isDirectory`

- **p**：`{ "path": "<path>" }`
- **返回**：`"true"` | `"false"` (string)

### `delete`

- **p**：`{ "path": "<path>" }`
- **返回**：`"true"` | `"false"` (string)

删除文件或目录（递归删除）。

### `mkdir`

- **p**：`{ "path": "<path>" }`
- **返回**：`"true"` (string)

递归创建目录。

### `list`

- **p**：`{ "path": "<path>" }`
- **返回**：`["<path1>", "<path2>", ...]`

列出目录内容（完整路径）。

### `readBase64` / `writeBase64`

- **p**：`{ "path": "<path>", "data": "<base64>" }` (write 需要 data)
- **返回**：`{ "data": "<base64>" }` (read) / `"true"` (write)

Base64 编码的二进制读写。

### `systemPaths`（仅 `outer` 前缀）

- **p**：无需参数
- **返回**：`{ "home": "<用户主目录>", "desktop": "<桌面路径>", "temp": "<系统临时目录>" }`

获取常用系统路径（JVM 属性，无 IO）。`desktop` = `<home>/Desktop`（可能不存在），`temp` = `java.io.tmpdir`。此节点仅 `outer` 前缀可用（`outer.systemPaths`），其他前缀调用返回错误。

---

## 路径安全

实现**必须**拦截任何尝试逃逸基准目录的请求（包含 `../` 或以 `/` 开头的绝对路径）——`plugin` 级基准为 `plugins/<插件名>/`，`server` 级基准为服务器根目录。`outer` 级无此限制。

**运行时配置目录保护**：实现**必须**拒绝所有 fs **写操作**（`writeFile` / `appendFile` / `writeBase64` / `delete` / `mkdir`）对运行时配置目录（如 `plugins/Yeow/runtime/`）的修改（全部级别一致拦截）——该目录存放运行时的 `config.yml` 与 `approve.json`，插件不可通过 fs API 篡改。读取不受限。
