# World 任务

世界和全局游戏操作。

---

## 查找

### `world.get`

按名称查找世界。

- **请求**：`{ "name": "<name>" }`
- **返回**：`{ "name": "<name>" }` | `null`

### `world.getAll`

获取所有世界。

- **请求**：`{}`
- **返回**：`[{ "name": "<name>" }, ...]`

---

## 世界属性

所有属性任务请求字段中 `world` 为世界名称字符串。`value` 为设置值。

### 时间与天气

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getTime` | `{ "world": "<name>" }` | `number` (tick) |
| `world.setTime` | `{ "world": "<name>", "value": <long> }` | `true` |
| `world.getStorm` | `{ "world": "<name>" }` | `boolean` |
| `world.setStorm` | `{ "world": "<name>", "value": <boolean> }` | `true` |
| `world.getThundering` | `{ "world": "<name>" }` | `boolean` |
| `world.setThundering` | `{ "world": "<name>", "value": <boolean> }` | `true` |

### 难度

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getDifficulty` | `{ "world": "<name>" }` | `"peaceful" \| "easy" \| "normal" \| "hard"` |
| `world.setDifficulty` | `{ "world": "<name>", "value": "<difficulty>" }` | `true` |

### 出生点

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getSpawnLocation` | `{ "world": "<name>" }` | `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double> }` |
| `world.setSpawnLocation` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `true` |

### 游戏规则

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getGameRule` | `{ "world": "<name>", "rule": "<rule>" }` | `string` | `null` |
| `world.setGameRule` | `{ "world": "<name>", "rule": "<rule>", "value": "<value>" }` | `true` |

---

## 地形与方块

### 地形

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getBiome` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `string` (命名空间 key，如 `minecraft:plains`) |
| `world.getHighestBlockY` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `number` |
| `world.getBlockLightLevel` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `number` (0-15，方块光源等级) |
| `world.getSkyLightLevel` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `number` (0-15，天空光照等级) |

### 区块

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getChunkAt` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `{ "x": <int>, "z": <int>, "world": "<name>" }`（取区块，可能触发加载） |
| `world.isChunkLoaded` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean` |
| `world.loadChunk` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean`（强制加载） |
| `world.unloadChunk` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean` |

### 区块快照

| 任务 | 请求 | 返回 |
|------|------|------|
| `chunk.getSnapshot` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `{ "data": "<base64>", "minY": <int>, "height": <int> }` |
| `chunk.getTopSnapshot` | `{ "world": "<name>", "x": <int>, "z": <int> [, "withHeight": true] }` | `{ "data": "<base64>" [, "height": "<base64>"] }` |

- `data` 为**方块类型索引数组**的 base64 编码：`short[]` **小端序**（每元素 2 字节，与 JS 侧 `Uint16Array` 视图零拷贝解码匹配）
- **索引基准**：方块类型索引 = `server.getBlocks` 返回数组的下标（见 [server 规范](server.md)）；索引仅当前运行时内有效，不可持久化
- `chunk.getSnapshot`：完整区块，长度 `16×16×height`（`height = maxHeight - minY`），遍历顺序 **y 外层 → z 中层 → x 内层**；偏移量 `((y - minY) * 16 + z) * 16 + x`，`y` 为世界绝对高度
- `chunk.getTopSnapshot`：每列最高非空气方块，长度 256，遍历顺序 **z 外层 → x 内层**；偏移量 `z * 16 + x`；虚空列回退 air 索引；底层用 `World.getHighestBlockYAt`（世界坐标）查询。请求带 `withHeight: true` 时同时返回 `height`（**heightMap**：每列最高方块的世界高度，short[] 小端序 base64，与 `data` 同布局）

### 方块

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `{ "type": "<key>", "x": <int>, "y": <int>, "z": <int>, "state": { "<状态键>": "<值>" } }`（state 为空对象时表示无状态） |
| `world.setBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "blockType": "<material>", "state": { "<键>": "<值>" }? }` | `true`（state 存在时按 `type[键=值,...]` 构造 BlockData 放置） |

`type` 和 `blockType` 使用 Material 命名空间 key（如 `minecraft:stone`），也兼容简写 `stone`。

### 方块状态查询

| 任务 | 请求 | 返回 |
|------|------|------|
| `block.isSolid` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `boolean` |
| `block.isLiquid` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `boolean` |
| `block.isEmpty` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `boolean` |
| `block.breakNaturally` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "item": <ItemStack> }` | `boolean` |

`block.breakNaturally` 模拟玩家破坏方块并掉落物品。`item` 为可选工具（影响掉落概率和类型，如附魔工具）。

---

## 实体与玩家

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getEntities` | `{ "world": "<name>" }` | `string[]` (实体 UUID 数组) |
| `world.getPlayers` | `{ "world": "<name>" }` | `string[]` (玩家 UUID 数组) |
| `world.getNearbyEntities` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "radius": <double> }` | `string[]` (实体 UUID 数组) |

---

## 实体生成

### `world.spawnEntity`

生成实体。

- **请求**：`{ "world": "<name>", "type": "<entityType>", "x": <double>, "y": <double>, "z": <double> }`
- **返回**：`string` (实体 UUID)

`type` 为 Bukkit EntityType 枚举名（如 `ZOMBIE`、`CREEPER`）。

### `world.spawnItem`

投掷物品。

- **请求**：`{ "world": "<name>", "item": { "type": "<material>", "amount": <int> }, "x": <double>, "y": <double>, "z": <double> }`
- **返回**：`string` (物品实体 UUID)

---

## 特效

### 世界音效

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.playSound` | `{ "world": "<name>", "sound": "<sound>", "x": <double>, "y": <double>, "z": <double>, "volume": <float>, "pitch": <float> }` | `true` |

`sound` 为 Bukkit Sound 枚举名（如 `entity.creeper.primed`）。

### 粒子

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.spawnParticle` | `{ "world": "<name>", "particle": "<key>", "x": <double>, "y": <double>, "z": <double>, "count": <int>, "offsetX": <double>, "offsetY": <double>, "offsetZ": <double>, "speed": <double>, "force": <bool>, ... }` | `true` |

根据粒子类型携带额外字段：

| 粒子类型 | 额外字段 |
|---------|---------|
| `dust` / `dust_color_transition` | `{ "color": { "r": <int>, "g": <int>, "b": <int>, "size": <float> } }` |
| `block_marker` / `falling_dust` | `{ "blockType": "<material>" }` |
| `item` | `{ "item": { "type": "<material>", "amount": <int> } }` |

### 其他特效

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.dropItem` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "itemType": "<material>", "amount": <int> }` | `true` |
| `world.strikeLightning` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double> }` | `true` |
| `world.strikeLightningEffect` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double> }` | `true` |
| `world.createExplosion` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "power": <float>, "setFire": <bool>, "breakBlocks": <bool> }` | `true` |
