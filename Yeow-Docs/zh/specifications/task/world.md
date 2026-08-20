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
| `world.isChunkGenerated` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean`（区块已生成，未加载/未生成返回 false） |
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
| `world.getBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `{ "type": "<key>", "x": <int>, "y": <int>, "z": <int>, "state": { "<状态键>": <string\|number\|boolean> }, "world": "<name>" }`（state 为空对象时表示无状态；值保留类型——布尔 `waterlogged: false`、数字 `level: 8`、枚举串 `facing: "north"`；world 为所属世界名） |
| `world.setBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "blockType": "<material>", "state": { "<键>": <string\|number\|boolean> }? }` | `true`（state 存在时按 `type[键=值,...]` 构造 BlockData 放置；值按字面量原样写入——`waterlogged=false`、`level=8`；传入 Block 时忽略其 location） |

`type` 和 `blockType` 使用 Material 命名空间 key（如 `minecraft:stone`），也兼容简写 `stone`。

### 方块破坏（世界操作）

| 任务 | 请求 | 返回 |
|------|------|------|
| `block.breakNaturally` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "item": <ItemStack> }` | `boolean` |

`block.breakNaturally` 模拟玩家破坏方块并掉落物品。`item` 为可选工具（**数据快照**，模拟工具属性，不消耗真实物品耐久），影响掉落概率和类型，如附魔工具。

### 材料级判断（Material）

静态判断任务，基于类型（material）判断方块固有属性，**不依赖坐标/状态**，不查询世界：

| 任务 | 请求 | 返回 |
|------|------|------|
| `material.isSolid` | `{ "type": "<key>" }` | `boolean`（Paper 系 `Material.isSolid()`） |
| `material.isAir` | `{ "type": "<key>" }` | `boolean`（Paper 系 `Material.isAir()`） |
| `material.getMaxDurability` | `{ "type": "<key>" }` | `int`（最大耐久；非耐用品返回 `0`；未知类型返回错误） |

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

`type` 为 **minecraft 注册键**（如 `minecraft:zombie`、`minecraft:creeper`——与 `entity.getType` 输出一致）；兼容旧式 Bukkit 枚举名（如 `ZOMBIE`、`CREEPER`）。

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

`sound` 为 Paper 系 Sound 枚举名（如 `entity.creeper.primed`）。

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
| `world.dropItem` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "item": <ItemStack> }` | `true` |
| `world.strikeLightning` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double> }` | `true` |
| `world.strikeLightningEffect` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double> }` | `true` |
| `world.createExplosion` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "power": <float>, "setFire": <bool>, "breakBlocks": <bool> }` | `true` |

---

## 世界信息与 WorldBorder（2026-08-13）

### 世界信息

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getSeed` | `{ "world": "<name>" }` | `long`（世界种子） |
| `world.getEnvironment` | `{ "world": "<name>" }` | `string`（`NORMAL` / `NETHER` / `THE_END`） |
| `world.getWorldType` | `{ "world": "<name>" }` | `string` \| `null`（平台不支持时 null） |
| `world.getGameRules` | `{ "world": "<name>" }` | `string[]`（全部游戏规则名） |

### WorldBorder

| 任务 | 请求 | 返回 |
|------|------|------|
| `world.getBorder` | `{ "world": "<name>" }` | `{ "centerX", "centerZ", "size", "damageAmount", "damageBuffer", "warningDistance", "warningTime" }` |
| `world.setBorderCenter` | `{ "world": "<name>", "x": <double>, "z": <double> }` | `true` |
| `world.setBorderSize` | `{ "world": "<name>", "size": <double> }` | `true`（边界半径） |
| `world.setBorderDamage` | `{ "world": "<name>", "amount": <double>?, "buffer": <double>? }` | `true`（每秒伤害 / 无伤缓冲距离） |
| `world.setBorderWarning` | `{ "world": "<name>", "distance": <int>?, "time": <int>? }` | `true`（警告距离 / 时间） |
| `world.setBorderMoving` | `{ "world": "<name>", "from": <double>, "to": <double>, "seconds": <long> }` | `true`（平滑移动） |

> 玩家侧客户端边界 `player.setBorder` 已移除——仅保留服务端世界边界（`world.setBorder*`，对全体玩家生效）。

> 涉及值域（方块/物品/材料、实体类型、生物群系、音效、粒子、游戏规则、难度、环境、世界类型等）的格式规则与清单见 [值域附录](../values.md)。
