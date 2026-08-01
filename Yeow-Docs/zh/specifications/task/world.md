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

### 方块

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `{ "type": "<key>", "x": <int>, "y": <int>, "z": <int> }` |
| `world.setBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "blockType": "<material>" }` | `true` |

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
