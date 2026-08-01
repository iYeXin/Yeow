# FS 通道

文件系统操作。所有文件操作限定在 `plugins/<插件名>/` 目录下，路径穿越（`../`）必须被拒绝。

> **权限**：fs 通道**默认拒绝**，插件必须在 `yeow.json` 的 `permissions` 中声明 `fs:*`（全部）或具体节点（如 `fs:readFile`）。未声明调用返回 `Permission denied: fs:<op>`。

## 调用格式

```json
{ "t": "<operation>", "p": { "path": "<path>", "data": "<data>" }, "cb": "<callbackId>" }
```

`path` 为相对于插件数据目录的路径。

### 异步模式

若 payload 中包含 `cb` 字段（回调 ID），操作将**异步执行**：`$send` 立即返回 `null`，操作在独立 IO 线程中执行，完成后通过 `cb` 通道投递结果。

```json
// 异步请求
{ "t": "readFile", "p": { "path": "config.json" }, "cb": "cb_42" }
// 立即返回 null
// ... 操作在 IO 线程中执行 ...
// 结果通过 cb 投递：{ "t": "cb", "p": "cb_42", "r": {"data": "..."} }
```

若 **不含** `cb` 字段，操作**同步执行**，`$send` 阻塞直到操作完成并直接返回结果。

---

## 操作列表

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

---

## 路径安全

实现**必须**拦截任何尝试穿越插件数据目录的请求（包含 `../` 或以 `/` 开头的绝对路径），返回错误。
