# Command 任务

命令注册、执行和 Tab 补全。

---

## `command.register`

注册一个命令。

- **请求**：

```json
{
  "pluginName": "<name>",
  "commandName": "<name>",
  "callbackId": "<cbId>",
  "completerCbId": "<cbId>",
  "description": "<text>",
  "usage": "<text>",
  "permission": { "node": "<节点>", "default": "all | op | none" },
  "aliases": ["<alias1>", "<alias2>"]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `pluginName` | 是 | 所属插件名 |
| `commandName` | 是 | 命令名（不含 `/`） |
| `callbackId` | 是 | 命令执行回调 ID（`persistent: true`） |
| `completerCbId` | 否 | Tab 补全回调 ID（`persistent: true`） |
| `description` | 否 | 命令描述 |
| `usage` | 否 | 用法提示（如 `"/cmd <arg1> <arg2>"`） |
| `permission` | 否 | 权限节点：字符串（兼容，default 按 `"none"`）或对象 `{ "node", "default" }`（default：`"all"` 所有人默认拥有 / `"op"` / `"none"`）。节点注册进 Bukkit 权限系统（权限插件可管理）；**执行时检查**：`permissionCheck` 事件优先，无处理时回退 `hasPermission` |
| `aliases` | 否 | 别名列表 |

- **返回**：`boolean`

当玩家（或控制台）执行命令时，运行时通过 `cb` 通道向 `callbackId` 投递数据：

```json
{
  "sender": { "name": "<name>", "uuid": "<uuid>", "isPlayer": true },
  "args": ["<arg1>", "<arg2>"],
  "label": "<commandName>"
}
```

- `args`：玩家输入的命令参数数组
- `label`：实际使用的命令名（可能是别名）
- `isPlayer`：`false` 时表示控制台执行，此时 `uuid` 为空

执行器可以使用 async 函数，内部可用 `await` 调用异步 API。

## `command.dispatch`

以控制台身份执行命令。

- **请求**：`{ "command": "<cmd>" }`
- **返回**：`boolean`

行为等价于调用 `dispatchCommand` 或 `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)`。

## `command.unregisterAll`

注销指定插件的所有命令。

- **请求**：`{ "pluginName": "<name>" }`
- **返回**：`true`

通常在插件热重载前调用，确保旧代码的命令被正确移除。

---

## Tab 补全

### 补全触发

当 `command.register` 时传入了 `completerCbId`，且玩家按下 Tab 键时，运行时通过 `cb` 通道向该 ID 投递补全请求：

```json
{
  "sender": { "name": "<name>", "uuid": "<uuid>", "isPlayer": true },
  "args": ["<typed arg1>", "<typing arg2>"]
}
```

- `args` 的最后一个元素为正在输入中的参数（可能为空字符串）
- 补全器**必须**通过 `command.tabComplete` 任务返回结果

### `command.tabComplete`

补全响应任务。

- **请求**：
```json
{
  "type": "command.tabComplete",
  "params": {
    "callbackId": "<cbId>",
    "completions": ["<suggestion1>", "<suggestion2>"]
  }
}
```

`callbackId` 必须与 `command.register` 时传入的 `completerCbId` 完全一致。

- **返回**：不产生返回值（`true`）

### 异步补全

补全器可以是 async 函数。若返回 Promise，补全在第一个 `await` 时自动提交空补全列表（等效于 `complete([])`），异步逻辑在后台继续执行但对当前 Tab 补全无影响。

### 手动补全模式

若应用代码需要在异步获取数据后再进行补全，可以不立即返回补全列表，而是在异步操作完成后由代码自行调用 `command.tabComplete` 提交结果。
