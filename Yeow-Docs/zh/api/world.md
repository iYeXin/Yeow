# World API

```js
import { World } from 'yeow-api';
```

## 静态方法

默认为异步（`Promise`），同步版本加 `Sync` 后缀。

| 方法 | 返回 | 说明 |
|------|------|------|
| `World.get(name)` | `Promise<World \| null>` | 按名字获取世界 |
| `World.getSync(name)` | `World \| null` | 同步版 |
| `World.getAll()` | `Promise<World[]>` | 所有已加载世界 |
| `World.getAllSync()` | `World[]` | 同步版 |

## 属性

| 属性 | 类型 | 读写 | 说明 |
|------|------|:----:|------|
| `name` | `string` | 只读 | 世界名 |
| `time` | `number` | 读写 | 游戏时间 0-24000 tick |
| `storm` | `boolean` | 读写 | 下雨 |
| `thundering` | `boolean` | 读写 | 打雷 |
| `difficulty` | `string` | 读写 | PEACEFUL / EASY / NORMAL / HARD |
| `spawnLocation` | `Location \| null` | 读写 | 世界出生点 |

## 方法

默认为异步（`Promise`），同步版本加 `Sync` 后缀。

### 游戏规则

```js
world.getGameRule(rule)             // Promise<string | null>
world.getGameRuleSync(rule)         // string | null
world.setGameRule(rule, value)      // Promise
world.setGameRuleSync(rule, value)
```
规则名使用大写下划线格式，如 `DO_DAYLIGHT_CYCLE`、`KEEP_INVENTORY`。

### 方块

```js
world.getBlock(x, y, z)             // Promise<Block | null>
world.getBlockSync(x, y, z)         // Block | null
world.setBlock(x, y, z, blockType)  // Promise
world.setBlockSync(x, y, z, blockType)
```

`Block` 对象上的方法：

```js
block.isSolid(): Promise<boolean>
block.isLiquid(): Promise<boolean>
block.isEmpty(): Promise<boolean>
block.breakNaturally(tool?): Promise<boolean>   // 自然破坏（含掉落物）
block.breakNaturallySync(tool?): boolean
```

`tool` 为可选的 `ItemStack`，用于模拟特定工具的挖掘效果（如 `{ type: 'minecraft:diamond_pickaxe', meta: { enchantments: { fortune: 3 } } }`）。

### 生物群系与光照

```js
world.getBiome(x, y, z)             // Promise<string> 如 "minecraft:plains"
world.getBiomeSync(x, y, z)         // string
world.getHighestBlockY(x, z)        // Promise<number>
world.getHighestBlockYSync(x, z)    // number
world.getBlockLightLevel(x, y, z)   // Promise<number> (0-15)
world.getBlockLightLevelSync(x, y, z)
world.getSkyLightLevel(x, y, z)     // Promise<number> (0-15)
world.getSkyLightLevelSync(x, y, z)
```

### 区块

```js
world.getChunkAt(x, z)              // Promise<{ x, z }>（取区块，可能触发加载）
world.getChunkAtSync(x, z)
world.isChunkLoaded(x, z)           // Promise<boolean>
world.isChunkLoadedSync(x, z)       // boolean
world.loadChunk(x, z)               // Promise<boolean>（强制加载）
world.loadChunkSync(x, z)           // boolean
world.unloadChunk(x, z)             // Promise<boolean>
world.unloadChunkSync(x, z)         // boolean
```

### 实体查询

```js
world.getEntities()                       // Promise<string[]>
world.getEntitiesSync()                   // string[]
world.getPlayers()                        // Promise<string[]>
world.getPlayersSync()                    // string[]
world.getNearbyEntities(x, y, z, radius)  // Promise<string[]>
world.getNearbyEntitiesSync(x, y, z, radius)
```

### 世界操作

```js
world.dropItem(x, y, z, itemType, amount?)               // Promise
world.dropItemSync(x, y, z, itemType, amount?)
world.strikeLightning(x, y, z)                           // Promise
world.strikeLightningSync(x, y, z)
world.strikeLightningEffect(x, y, z)                     // Promise（仅效果）
world.strikeLightningEffectSync(x, y, z)
world.createExplosion(x, y, z, power?, fire?, breaks?)   // Promise
world.createExplosionSync(x, y, z, power?, fire?, breaks?)
```

### 实体生成

```js
world.spawnEntity(type, x, y, z)       // Promise<string | null> — 返回实体 UUID
world.spawnEntitySync(type, x, y, z)   // string | null
```

`type` 为实体类型名（如 `ZOMBIE`、`CREEPER`、`COW`）。

### 音效与粒子

```js
world.playSound(sound, x, y, z, volume?, pitch?)         // Promise
world.playSoundSync(sound, x, y, z, volume?, pitch?)
```

世界级音效。`sound` 为 Bukkit Sound 枚举名（如 `block.note_block.pling`）。

粒子效果 API 参见 [Particle 文档](particle.md)。

## 示例

```js
const w = await World.get('world');
if (w) {
    w.time = 6000;                            // 正午（属性同步）
    w.storm = true;
    await w.setBlock(0, 65, 0, 'minecraft:diamond_block');
    w.strikeLightningSync(100, 64, 100);
    await w.createExplosion(10, 64, 10, 5);
}
```
