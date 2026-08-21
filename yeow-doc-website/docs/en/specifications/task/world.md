# World Tasks

World and global game operations.

---

## Lookup

### `world.get`

Find a world by name.

- **Request**: `{ "name": "<name>" }`
- **Returns**: `{ "name": "<name>" }` | `null`

### `world.getAll`

Get all worlds.

- **Request**: `{}`
- **Returns**: `[{ "name": "<name>" }, ...]`

---

## World Properties

In all property task requests, `world` is the world name string. `value` is the value to set.

### Time and Weather

| Task | Request | Returns |
|------|---------|---------|
| `world.getTime` | `{ "world": "<name>" }` | `number` (tick) |
| `world.setTime` | `{ "world": "<name>", "value": <long> }` | `true` |
| `world.getStorm` | `{ "world": "<name>" }` | `boolean` |
| `world.setStorm` | `{ "world": "<name>", "value": <boolean> }` | `true` |
| `world.getThundering` | `{ "world": "<name>" }` | `boolean` |
| `world.setThundering` | `{ "world": "<name>", "value": <boolean> }` | `true` |

### Difficulty

| Task | Request | Returns |
|------|---------|---------|
| `world.getDifficulty` | `{ "world": "<name>" }` | `"peaceful" \| "easy" \| "normal" \| "hard"` |
| `world.setDifficulty` | `{ "world": "<name>", "value": "<difficulty>" }` | `true` |

### Spawn Location

| Task | Request | Returns |
|------|---------|---------|
| `world.getSpawnLocation` | `{ "world": "<name>" }` | `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double> }` |
| `world.setSpawnLocation` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `true` |

### Game Rules

| Task | Request | Returns |
|------|---------|---------|
| `world.getGameRule` | `{ "world": "<name>", "rule": "<rule>" }` | `string` | `null` |
| `world.setGameRule` | `{ "world": "<name>", "rule": "<rule>", "value": "<value>" }` | `true` |

---

## Terrain and Blocks

### Terrain

| Task | Request | Returns |
|------|---------|---------|
| `world.getBiome` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `string` (namespace key, e.g. `minecraft:plains`) |
| `world.getHighestBlockY` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `number` |
| `world.getBlockLightLevel` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `number` (0-15, block light level) |
| `world.getSkyLightLevel` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `number` (0-15, sky light level) |

### Chunks

| Task | Request | Returns |
|------|---------|---------|
| `world.getChunkAt` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `{ "x": <int>, "z": <int>, "world": "<name>" }` (gets the chunk, may trigger loading) |
| `world.isChunkLoaded` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean` |
| `world.isChunkGenerated` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean` (chunk has been generated; returns false if not loaded/not generated) |
| `world.loadChunk` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean` (force load) |
| `world.unloadChunk` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `boolean` |

### Chunk Snapshots

| Task | Request | Returns |
|------|---------|---------|
| `chunk.getSnapshot` | `{ "world": "<name>", "x": <int>, "z": <int> }` | `{ "data": "<base64>", "minY": <int>, "height": <int> }` |
| `chunk.getTopSnapshot` | `{ "world": "<name>", "x": <int>, "z": <int> [, "withHeight": true] }` | `{ "data": "<base64>" [, "height": "<base64>"] }` |

- `data` is the base64-encoded **block type index array**: `short[]` **little-endian** (2 bytes per element, zero-copy decoded with JS-side `Uint16Array` view)
- **Index base**: block type index = the index in the array returned by `server.getBlocks` (see [server specification](server.md)); indices are only valid within the current runtime and cannot be persisted
- `chunk.getSnapshot`: full chunk, length `16×16×height` (`height = maxHeight - minY`), iteration order **y outer → z middle → x inner**; offset `((y - minY) * 16 + z) * 16 + x`, `y` is the world absolute height
- `chunk.getTopSnapshot`: highest non-air block per column, length 256, iteration order **z outer → x inner**; offset `z * 16 + x`; void columns fall back to air index; bottom layer uses `World.getHighestBlockYAt` (world coordinates) query. When the request includes `withHeight: true`, also returns `height` (**heightMap**: world height of the highest block per column, short[] little-endian base64, same layout as `data`)

### Blocks

| Task | Request | Returns |
|------|---------|---------|
| `world.getBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int> }` | `{ "type": "<key>", "x": <int>, "y": <int>, "z": <int>, "state": { "<state key>": <string\|number\|boolean> }, "world": "<name>" }` (state is an empty object when no state exists; values preserve type — boolean `waterlogged: false`, number `level: 8`, enum string `facing: "north"`; world is the world name it belongs to) |
| `world.setBlock` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "blockType": "<material>", "state": { "<key>": <string\|number\|boolean> }? }` | `true` (when state is present, constructs BlockData using `type[key=value,...]` and places it; values are written literally — `waterlogged=false`, `level=8`; when a Block is passed, its location is ignored) |

`type` and `blockType` use Material namespace keys (e.g. `minecraft:stone`), and also accept shorthand forms like `stone`.

### Block Breaking (World Operation)

| Task | Request | Returns |
|------|---------|---------|
| `block.breakNaturally` | `{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "item": <ItemStack> }` | `boolean` |

`block.breakNaturally` simulates a player breaking a block and dropping items. `item` is an optional tool (**data snapshot** that simulates tool attributes without consuming real item durability), affecting drop probability and type, such as enchanted tools.

### Material-Level Checks (Material)

Static check tasks that determine block intrinsic properties based on type (material), **independent of coordinates/state**, without querying the world:

| Task | Request | Returns |
|------|---------|---------|
| `material.isSolid` | `{ "type": "<key>" }` | `boolean` (Paper's `Material.isSolid()`) |
| `material.isAir` | `{ "type": "<key>" }` | `boolean` (Paper's `Material.isAir()`) |
| `material.getMaxDurability` | `{ "type": "<key>" }` | `int` (max durability; returns `0` for non-durable items; returns error for unknown types) |

---

## Entities and Players

| Task | Request | Returns |
|------|---------|---------|
| `world.getEntities` | `{ "world": "<name>" }` | `string[]` (array of entity UUIDs) |
| `world.getPlayers` | `{ "world": "<name>" }` | `string[]` (array of player UUIDs) |
| `world.getNearbyEntities` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "radius": <double> }` | `string[]` (array of entity UUIDs) |

---

## Entity Spawning

### `world.spawnEntity`

Spawn an entity.

- **Request**: `{ "world": "<name>", "type": "<entityType>", "x": <double>, "y": <double>, "z": <double> }`
- **Returns**: `string` (entity UUID)

`type` is a **Minecraft registry key** (e.g. `minecraft:zombie`, `minecraft:creeper` — consistent with `entity.getType` output); also accepts legacy Bukkit enum names (e.g. `ZOMBIE`, `CREEPER`).

### `world.spawnItem`

Throw an item.

- **Request**: `{ "world": "<name>", "item": { "type": "<material>", "amount": <int> }, "x": <double>, "y": <double>, "z": <double> }`
- **Returns**: `string` (item entity UUID)

---

## Effects

### World Sound

| Task | Request | Returns |
|------|---------|---------|
| `world.playSound` | `{ "world": "<name>", "sound": "<sound>", "x": <double>, "y": <double>, "z": <double>, "volume": <float>, "pitch": <float> }` | `true` |

`sound` is a Paper Sound enum name (e.g. `entity.creeper.primed`).

### Particles

| Task | Request | Returns |
|------|---------|---------|
| `world.spawnParticle` | `{ "world": "<name>", "particle": "<key>", "x": <double>, "y": <double>, "z": <double>, "count": <int>, "offsetX": <double>, "offsetY": <double>, "offsetZ": <double>, "speed": <double>, "force": <bool>, ... }` | `true` |

Additional fields carried based on particle type:

| Particle Type | Additional Fields |
|---------------|-------------------|
| `dust` / `dust_color_transition` | `{ "color": { "r": <int>, "g": <int>, "b": <int>, "size": <float> } }` |
| `block_marker` / `falling_dust` | `{ "blockType": "<material>" }` |
| `item` | `{ "item": { "type": "<material>", "amount": <int> } }` |

### Other Effects

| Task | Request | Returns |
|------|---------|---------|
| `world.dropItem` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "item": <ItemStack> }` | `true` |
| `world.strikeLightning` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double> }` | `true` |
| `world.strikeLightningEffect` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double> }` | `true` |
| `world.createExplosion` | `{ "world": "<name>", "x": <double>, "y": <double>, "z": <double>, "power": <float>, "setFire": <bool>, "breakBlocks": <bool> }` | `true` |

---

## World Info and WorldBorder (2026-08-13)

### World Info

| Task | Request | Returns |
|------|---------|---------|
| `world.getSeed` | `{ "world": "<name>" }` | `long` (world seed) |
| `world.getEnvironment` | `{ "world": "<name>" }` | `string` (`NORMAL` / `NETHER` / `THE_END`) |
| `world.getWorldType` | `{ "world": "<name>" }` | `string` | `null` (null when platform does not support it) |
| `world.getGameRules` | `{ "world": "<name>" }` | `string[]` (all game rule names) |

### WorldBorder

| Task | Request | Returns |
|------|---------|---------|
| `world.getBorder` | `{ "world": "<name>" }` | `{ "centerX", "centerZ", "size", "damageAmount", "damageBuffer", "warningDistance", "warningTime" }` |
| `world.setBorderCenter` | `{ "world": "<name>", "x": <double>, "z": <double> }` | `true` |
| `world.setBorderSize` | `{ "world": "<name>", "size": <double> }` | `true` (border radius) |
| `world.setBorderDamage` | `{ "world": "<name>", "amount": <double>?, "buffer": <double>? }` | `true` (damage per second / no-damage buffer distance) |
| `world.setBorderWarning` | `{ "world": "<name>", "distance": <int>?, "time": <int>? }` | `true` (warning distance / time) |
| `world.setBorderMoving` | `{ "world": "<name>", "from": <double>, "to": <double>, "seconds": <long> }` | `true` (smooth movement) |

> The client-side player border `player.setBorder` has been removed — only the server-side world border (`world.setBorder*`) is retained, affecting all players.

> For format rules and lists covering value domains (blocks/items/materials, entity types, biomes, sounds, particles, game rules, difficulty, environment, world types, etc.), see the [Values Appendix](../values.md).
