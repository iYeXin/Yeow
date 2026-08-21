# World API

```js
import { World } from 'yeow-api';
```

## Static Methods

Default is async (`Promise`), synchronous version adds `Sync` suffix.

| Method | Return | Description |
| ------ | ------ | ----------- |
| `World.get(name)` | `Promise<World \| null>` | Get world by name |
| `World.getSync(name)` | `World \| null` | Synchronous version |
| `World.getAll()` | `Promise<World[]>` | All loaded worlds |
| `World.getAllSync()` | `World[]` | Synchronous version |

## Properties

| Property | Type | Read/Write | Description |
| -------- | ---- | :--------: | ----------- |
| `name` | `string` | Read-only | World name |
| `time` | `number` | Read/Write | Game time 0-24000 tick |
| `storm` | `boolean` | Read/Write | Raining |
| `thundering` | `boolean` | Read/Write | Thundering |
| `difficulty` | `string` | Read/Write | PEACEFUL / EASY / NORMAL / HARD |
| `spawnLocation` | `Location \| null` | Read/Write | World spawn point |

> Difficulty values (`difficulty`) see [Value Domain Appendix · Directly Maintained Enumeration List](../specifications/values.md#2-directly-maintained-enumeration-list); Game rule keys see [Value Domain Appendix · Version Change Domain](../specifications/values.md#4-version-change-domain-rules--references).

## Methods

Default is async (`Promise`), synchronous version adds `Sync` suffix.

### Game Rules

```js
world.getGameRule(rule)             // Promise<string | null>
world.getGameRuleSync(rule)         // string | null
world.setGameRule(rule, value)      // Promise
world.setGameRuleSync(rule, value)
```

Rule names are **R3 camelCase format** (value domain appendix, e.g., `keepInventory`, `doDaylightCycle`), case-insensitive (input lenient; output strict camelCase).

### World Info & WorldBorder

```js
// World info
world.seed                        // number — World seed
world.environment                 // string — NORMAL / NETHER / THE_END
world.worldType                   // string | null
world.gameRules                   // string[] — All game rule names
await world.getSeed(); await world.getEnvironment(); ...

// WorldBorder (server world boundary)
const border = await world.getBorder();  // { centerX, centerZ, size, damageAmount, damageBuffer, warningDistance, warningTime }
await world.setBorderCenter(x, z);
await world.setBorderSize(size);
await world.setBorderDamage(amount?, buffer?);
await world.setBorderWarning(distance?, time?);
await world.setBorderMoving(from, to, seconds);   // Smooth movement
```

Player-side client boundary `player.setBorder` has been removed — only server world boundary retained (`world.setBorder*`, affects all players).

### Blocks

```js
world.getBlock(x, y, z)             // Promise<Block | null> (includes type, state and location)
world.getBlockSync(x, y, z)         // Block | null
world.setBlock(x, y, z, blockType)  // Promise (blockType is string, compatible, no state)
world.setBlock(x, y, z, block)      // Promise (block is Block descriptor, can have state; ignores its location)
world.setBlockSync(x, y, z, block)
```

Methods on `Block` object:

```js
block.isSolid(): Promise<boolean>        // Material-level static judgment (delegated to Material)
block.isSolidSync(): boolean
block.isAir(): Promise<boolean>          // Whether it's air
block.isAirSync(): boolean
block.breakNaturally(tool?): Promise<boolean>   // Natural break (includes drops; requires location)
block.breakNaturallySync(tool?): boolean
```

`Block` unifiedly represents blocks: `type`/`state`/`location` are all **snapshots at acquisition time** (need latest state, re-call `world.getBlock`); `getBlock` returned Block has `location` (yaw/pitch always 0), `Block.of()` constructed ones don't. `isSolid` etc. are material-level static judgments, don't query world. See [Block API](block.md) for details.

`tool` is optional `ItemStack` (**data snapshot**, simulates tool properties, doesn't consume real item durability), used to simulate specific tool mining effects (e.g., `{ type: 'minecraft:diamond_pickaxe', meta: { enchantments: { fortune: 3 } } }`). See [ItemStack](item.md) for details.

### Biome & Lighting

```js
world.getBiome(x, y, z)             // Promise<string> e.g., "minecraft:plains"
world.getBiomeSync(x, y, z)         // string
world.getHighestBlockY(x, z)        // Promise<number>
world.getHighestBlockYSync(x, z)    // number
world.getBlockLightLevel(x, y, z)   // Promise<number> (0-15)
world.getBlockLightLevelSync(x, y, z)
world.getSkyLightLevel(x, y, z)     // Promise<number> (0-15)
world.getSkyLightLevelSync(x, y, z)
```

### Chunks

```js
world.getChunkAt(x, z)              // Promise<Chunk> (gets chunk, may trigger loading; includes x/z/world)
world.getChunkAtSync(x, z)          // Chunk
world.isChunkLoaded(x, z)           // Promise<boolean>
world.isChunkLoadedSync(x, z)       // boolean
world.isChunkGenerated(x, z)        // Promise<boolean> (chunk generated, not loaded/not generated returns false)
world.isChunkGeneratedSync(x, z)    // boolean
world.loadChunk(x, z)               // Promise<boolean> (force load)
world.loadChunkSync(x, z)           // boolean
world.unloadChunk(x, z)             // Promise<boolean>
world.unloadChunkSync(x, z)         // boolean
```

Chunk object block data snapshot (batch read, advanced scenarios) see [Chunk API](chunk.md).

### Entity Queries

```js
world.getEntities()                       // Promise<string[]>
world.getEntitiesSync()                   // string[]
world.getPlayers()                        // Promise<string[]>
world.getPlayersSync()                    // string[]
world.getNearbyEntities(x, y, z, radius)  // Promise<string[]>
world.getNearbyEntitiesSync(x, y, z, radius)
```

### World Operations

```js
world.dropItem(x, y, z, item, amount?)               // Promise — item is ItemStack or material name string (string + amount compatible with legacy)
world.dropItemSync(x, y, z, item, amount?)
world.strikeLightning(x, y, z)                       // Promise
world.strikeLightningSync(x, y, z)
world.strikeLightningEffect(x, y, z)                 // Promise (effect only)
world.strikeLightningEffectSync(x, y, z)
world.createExplosion(x, y, z, power?, fire?, breaks?)   // Promise
world.createExplosionSync(x, y, z, power?, fire?, breaks?)
```

### Entity Spawning

```js
world.spawnEntity(type, x, y, z)       // Promise<string | null> — Returns entity UUID
world.spawnEntitySync(type, x, y, z)   // string | null
```

`type` is **minecraft registration key** (e.g., `minecraft:zombie`, `minecraft:creeper` — consistent with `entity.type`); compatible with legacy Bukkit enumeration names (e.g., `ZOMBIE`).

### Sound & Particle

```js
world.playSound(sound, x, y, z, volume?, pitch?)         // Promise
world.playSoundSync(sound, x, y, z, volume?, pitch?)
```

World-level sound. `sound` is Paper series Sound enumeration name (e.g., `block.note_block.pling`).

Particle effect API see [Particle documentation](particle.md).

## Example

```js
const w = await World.get('world');
if (w) {
    w.time = 6000;                            // Noon (property sync)
    w.storm = true;
    await w.setBlock(0, 65, 0, 'minecraft:diamond_block');
    w.strikeLightningSync(100, 64, 100);
    await w.createExplosion(10, 64, 10, 5);
}
```