# Assets 通道

读取插件 JAR/包内 `assets/` 目录中的内置资源文件。

> **权限**：assets 通道**不设权限拦截**（仅读取打包资源，或解压到**本插件数据目录**内）。解压目标被强制限定在 `plugins/<插件名>/` 内（越界返回错误）。

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

- **p**：`{ "path": "<path>", "dest": "<dest>" }` —— **`dest` 必填**，基于插件数据目录（`plugins/<插件名>/`）计算并限定其内
- **返回**：`{ "path": "<相对服务器根目录的路径>" }`

将资源文件提取到文件系统。`dest` 必填（缺省返回错误）。

### `extractDir`

- **p**：`{ "path": "<path>", "dest": "<extractPath>"? }`
- **返回**：`{ "path": "<相对服务器根目录的路径>" }`

将资源**目录树**提取到文件系统（递归，保持内部相对结构）。`path` 指向 `assets/` 下的目录（如 `native/`），`dest` 可选（默认 `plugins/<插件名>/assets/<path>`），同样限定在插件数据目录内。

---

## 开发模式

开发模式下，实现应从本地文件系统路径（而非 JAR 包内）读取资源文件，以支持热重载。
