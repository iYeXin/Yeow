# Server 任务

服务器全局操作。

---

| 任务 | 请求 | 返回 |
|------|------|------|
| `server.broadcast` | `{ "message": "<text>" }` | `true` |
| `server.getMotd` | `{}` | `string` |
| `server.setMotd` | `{ "motd": "<text>" }` | `true` |
| `server.getVersion` | `{}` | `string` |
| `server.setIcon` | `{ "icon": "<base64>" }` | `true` |

## Material / Block / Item 查询

获取所有已注册的 Material 数据。

### `server.getMaterials`

- **请求**：`{}`
- **返回**：

```json
[
  { "key": "minecraft:air", "isBlock": true, "isItem": true },
  { "key": "minecraft:stone", "isBlock": true, "isItem": true },
  ...
]
```

| 返回字段 | 类型 | 说明 |
|---------|------|------|
| `key` | string | Material 命名空间 key（如 `minecraft:stone`） |
| `isBlock` | boolean | 是否为方块类型 |
| `isItem` | boolean | 是否为物品类型 |

### `server.getBlocks`

- **请求**：`{}`
- **返回**：`["minecraft:stone", "minecraft:dirt", ...]`

仅包含 `isBlock()` 为 `true` 的 Material 的命名空间 key。

### `server.getItems`

- **请求**：`{}`
- **返回**：`["minecraft:diamond", "minecraft:apple", ...]`

仅包含 `isItem()` 为 `true` 的 Material 的命名空间 key。
