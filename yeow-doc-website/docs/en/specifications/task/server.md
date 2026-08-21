# Server Tasks

Server global operations.

---

| Task | Request | Returns |
|------|---------|---------|
| `server.broadcast` | `{ "message": <Message> }` | `true` | `message` is a [Message object](../task/player.md#message-translatable-component) or plain text |
| `server.getMotd` | `{}` | `string` |
| `server.setMotd` | `{ "motd": "<text>" }` | `true` |
| `server.getVersion` | `{}` | `string` |
| `server.getTps` | `{}` | `{ "tps1m": <double>, "tps5m": <double>, "tps15m": <double> }` |
| `server.getMaxPlayers` | `{}` | `number` |

> **`server.getTps` is not guaranteed across platforms**: TPS is a host platform runtime metric (Paper platform uses `Bukkit.getTPS`) — other platform runtimes do not guarantee support, and the TPS concept may change in the future; callers must handle degradation themselves.

## Material / Block / Item Queries

Get all registered Material data.

### `server.getMaterials`

- **Request**: `{}`
- **Returns**:

```json
[
  { "key": "minecraft:air", "isBlock": true, "isItem": true },
  { "key": "minecraft:stone", "isBlock": true, "isItem": true },
  ...
]
```

| Return field | Type | Description |
|-------------|------|-------------|
| `key` | string | Material namespace key (e.g. `minecraft:stone`) |
| `isBlock` | boolean | Whether it is a block type |
| `isItem` | boolean | Whether it is an item type |

### `server.getBlocks`

- **Request**: `{}`
- **Returns**: `["minecraft:stone", "minecraft:dirt", ...]`

Contains only namespace keys of Materials where `isBlock()` is `true` (`Registry.MATERIAL` iteration order, stable within a runtime).

> **Block type index basis**: The **index** (subscript) of this array is the block type index used by [chunk snapshots](world.md#chunk-snapshots) (`chunk.getSnapshot` / `chunk.getTopSnapshot` `data` value) — `blocks[index]` restores the block key. Indices are only valid within the current runtime and cannot be persisted.

### `server.getItems`

- **Request**: `{}`
- **Returns**: `["minecraft:diamond", "minecraft:apple", ...]`

Contains only namespace keys of Materials where `isItem()` is `true`.
