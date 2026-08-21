# Block API

`Block` is a **unified block concept**: Data descriptor (type + block state) + optional **world position (`location`)**. Whether it has position only affects whether world operations can be executed (e.g., `breakNaturally`).

```js
import { Block, Material } from 'yeow-api';
```

## Block

```ts
Block.of('minecraft:stone')                         // Pure data descriptor, no location
Block.of('minecraft:water', { level: 8 })            // With state (values retain type: number/boolean/string)
new Block(type, state?, location?)                  // location optional
block.type                                          // "minecraft:stone" (snapshot)
block.state                                         // { level: 8 } | undefined (snapshot)
block.location                                      // Location | undefined — World position
block.withState({ waterlogged: false })             // Derive new descriptor (original object unchanged)
block.matches('minecraft:water', { level: 8 })      // Type/state match (ignores empty state differences)

// Material-level static judgment (based on type, delegated to Material; independent of position/state):
block.isSolid()          // Promise<boolean>
block.isSolidSync()      // boolean
block.isAir()            // Promise<boolean> — Whether it's air
block.isAirSync()        // boolean

// World operations (requires location, otherwise errors):
block.breakNaturally(itemStack)       // Promise<boolean> — Simulate using tool to naturally break and drop items, still produces drops without parameters, to simulate empty-hand mining use `minecraft:paper` etc. placeholder
block.breakNaturallySync(itemStack)   // boolean
```

State corresponds to **Minecraft vanilla block states** (key-value enumeration, **values retain vanilla types** — number/boolean/string), e.g., `minecraft:water[level=8]` → `{ type: "minecraft:water", state: { level: 8 } }`, `minecraft:stone[waterlogged=false]` → `{ waterlogged: false }`.

## Get from World

`world.getBlock(x, y, z)` returns `Block` with `location` (`location.yaw` / `location.pitch` ignored, always `0`):

```ts
const b = await world.getBlock(0, 65, 0);   // Block | null (includes location)
b.type;             // "minecraft:stone"
b.state;            // { waterlogged: "true" } | undefined
b.location;         // Location { x: 0, y: 65, z: 0, yaw: 0, pitch: 0, world: "world" }
b.isSolid();        // Delegates to Material.isSolid(this.type), static judgment
await b.breakNaturally();
```

### Static Data Semantics

- `type` / `state` / `location` are all **snapshots at acquisition time** — subsequent world changes won't auto-update
- **Need latest state? Re-call `world.getBlock(x, y, z)`** (no refresh method)
- `isSolid` / `isAir` are **material-level static judgments** (based on type, state doesn't affect) — independent of position, doesn't query world

## Place Block

`world.setBlock(x, y, z, block)` accepts **`Block` object or string** (compatible); blocks with state please construct `Block` object. **When passing `Block` ignores its `location` property** — only takes `type` / `state`:

```ts
import { World, Block } from 'yeow-api';

const world = World.getSync('world');

await world.setBlock(0, 65, 0, 'minecraft:stone');                                  // String (compatible, no state)
await world.setBlock(0, 65, 1, Block.of('minecraft:water', { level: '8' }));        // Descriptor (with state)
await world.setBlock(0, 65, 2, Block.of('minecraft:chest', { facing: 'north' }));   // Descriptor (with state)
```

## Material (Material-level Judgment)

`Block`'s `isSolid` / `isAir` delegates to same-name methods on `Material`; can also be used directly (no block instance needed):

```ts
Material.isSolid('minecraft:stone');   // Promise<boolean>
Material.isSolidSync('minecraft:stone'); // boolean
Material.isAir('minecraft:air');       // Promise<boolean>
```

> **`isLiquid` removed** (approximate semantics, see [Material API](material.md)).

## Example

```ts
import { World, Block } from 'yeow-api';

const world = World.getSync('world');
const b = world.getBlockSync(0, 65, 0);

if (b?.matches('minecraft:stone')) {
    await b.breakNaturally();
}

// Read state then write back as-is (stateful bucket placement)
const water = world.getBlockSync(1, 65, 0);
await world.setBlock(1, 65, 0, water);
```