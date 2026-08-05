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
| `server.getTps` | `{}` | `{ "tps1m": <double>, "tps5m": <double>, "tps15m": <double> }` |

> **`server.getTps` 跨平台不保证可用**：TPS 是宿主平台的运行指标（Paper 平台基于 `Bukkit.getTPS`）——其他平台运行时不保证支持，且未来 TPS 这一概念可能发生变化；调用方需自行降级处理。

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

仅包含 `isBlock()` 为 `true` 的 Material 的命名空间 key（`Registry.MATERIAL` 迭代顺序，运行时内稳定）。

> **方块类型索引基准**：本数组的**下标**即[区块快照](world.md#区块快照)使用的方块类型索引（`chunk.getSnapshot` / `chunk.getTopSnapshot` 的 `data` 值）——`blocks[index]` 即可还原方块 key。索引仅当前运行时内有效，不可持久化。

### `server.getItems`

- **请求**：`{}`
- **返回**：`["minecraft:diamond", "minecraft:apple", ...]`

仅包含 `isItem()` 为 `true` 的 Material 的命名空间 key。
