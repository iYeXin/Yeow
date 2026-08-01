# Assets 通道

读取插件 JAR/包内 `assets/` 目录中的内置资源文件。

> **权限**：`assets:extract`（解压资源到磁盘）**默认拒绝**，插件必须在 `yeow.json` 的 `permissions` 中声明；`assets:read` / `assets:readBase64` 默认允许。未声明调用返回 `Permission denied: assets:extract`。

## 调用格式

```json
{ "t": "<operation>", "p": { "path": "<path>", "dest": "<dest>" }, "cb": "<callbackId>" }
```

`path` 为相对于 `assets/` 的路径（如 `config.json` 表示 `assets/config.json`）。

### 异步模式

若 payload 中包含 `cb` 字段，操作将**异步执行**：`$send` 立即返回 `null`，操作在独立 IO 线程中执行，完成后通过 `cb` 通道投递结果。若不含 `cb`，操作同步执行。

---

## 操作列表

### `read`

- **p**：`{ "path": "<path>" }`
- **返回**：`{ "data": "<content>" }`

### `readBase64`

- **p**：`{ "path": "<path>" }`
- **返回**：`{ "data": "<base64>" }`

### `extract`

- **p**：`{ "path": "<path>", "dest": "<extractPath>" }`
- **返回**：`{ "path": "<absolutePath>" }`

将资源文件提取到文件系统。`dest` 为可选的目标路径，默认提取到 `plugins/<插件名>/assets/<path>`。

---

## 开发模式

开发模式下，实现应从本地文件系统路径（而非 JAR 包内）读取资源文件，以支持热重载。
