# Chunk API

**Advanced performance tool** — for use when you need to batch-read chunk block data. For day-to-day development, the [World API](world.md) is sufficient.

```js
import { World, Chunk, ChunkSnapshot, ChunkTopSnapshot } from 'yeow-api';
```

## Getting a Chunk

```ts
const chunk = await world.getChunkAt(0, 0);   // Chunk { x, z, world }
const chunk2 = world.getChunkAtSync(0, 0);
```

The `Chunk` itself does not carry block data — block data is obtained through snapshots.

### Chunk data (position)

`Chunk` carries the chunk's position information, which can be used for locating and area calculations:

| Field        | Type     | Description                                                         |
| ------------ | -------- | ------------------------------------------------------------------- |
| `chunk.x`    | `number` | Chunk X coordinate (world coordinate `>> 4`, i.e. `Math.floor(wx / 16)`) |
| `chunk.z`    | `number` | Chunk Z coordinate                                                  |
| `chunk.world`| `string` | The world name it belongs to (can be passed to `World.get()` to retrieve the world object) |

```ts
// Chunk ↔ world coordinate conversion
const wx = chunk.x * 16 + 0, wz = chunk.z * 16 + 0;  // chunk's northwest corner
const cx = Math.floor(wx / 16), cz = Math.floor(wz / 16);  // world coordinate → chunk
```

### Side effect: chunk loading

**`getChunkAt` / `getSnapshot` / `getTopSnapshot` all load unloaded chunks** — if the target chunk is not in memory, it will be force-loaded (possibly even generating it). Side effects:

- Calling on unexplored regions will **trigger chunk generation** (disk I/O / main-thread load)
- Loaded chunks remain resident until they are normally unloaded
- **Use `isChunkLoaded` for read-only checks**: decide yourself whether to `loadChunk` / skip when it is not loaded

```ts
if (!world.isChunkLoadedSync(cx, cz)) {
  // decide for yourself: skip, loadChunk, or getChunkAt (force load)
}
```

## Block type index

Each block in a snapshot is represented by a **type index** (`number`, 0-65535):

- The index is the **subscript** of the array returned by [`getBlocks()`](server.md) (e.g. `blocks[0]` = the first block key in the list)
- The index is built by the runtime (`Registry.MATERIAL` order) and is **only valid within the current runtime** — indexes may change after a server restart, so **snapshot data is not persistent** and must be used within the current session. To persist it, you also need to save the block indexes from `getBlocks()`.
- Reverse mapping: `const blocks = await getBlocks(); blocks[snap.getBlockIndex(x, y, z)]`

## ChunkSnapshot (full snapshot, 3D)

`chunk.getSnapshot()` returns the block indexes of the entire chunk (16×16×world height, e.g. 384 layers):

```ts
const snap = await chunk.getSnapshot();   // ChunkSnapshot
snap.getBlock(x, y, z)                    // string —— block key at absolute height y (e.g. "minecraft:stone")
snap.getBlockIndex(x, y, z)               // number —— raw index
snap.data                                 // Uint16Array —— all indexes
snap.minY / snap.height                   // world minimum height / number of layers
```

- **Traversal order**: `y` outermost → `z` middle → `x` innermost; offset = `((y - minY) * 16 + z) * 16 + x`
- `y` is the **world absolute height**
- Out-of-bounds coordinates and unknown indexes fall back to `'minecraft:air'`
- **Relatively heavy operation**: a single snapshot has ~100k indexes (transported as a base64-packed package at the task layer, ~255 KB), but it is far more efficient than iterating with `world.getBlock()`.

## ChunkTopSnapshot (top snapshot, 2D)

`chunk.getTopSnapshot()` returns the index of the **highest non-air block** in each column (256 elements, 16×16). Pass `withHeight=true` to also get the **heightMap** (the world height of the highest block in each column):

```ts
const top = await chunk.getTopSnapshot();           // ChunkTopSnapshot
const top2 = await chunk.getTopSnapshot(true);      // includes heightMap
top.getTop(x, z)                           // string —— column's highest block key
top.getTopIndex(x, z)                      // number —— raw index
top.getTopHeight(x, z)                     // number | null —— world height of the column's highest block (requires withHeight)
top.data                                   // Uint16Array (256 elements, block indexes)
top.height                                 // Uint16Array | undefined (256 elements, heights)
```

- **Traversal order**: `z` outer → `x` inner; offset = `z * 16 + x` (`data` and `height` share the same layout)
- For void columns (no blocks), `getTop` falls back to `'minecraft:air'`, and `getTopHeight` returns the actual minimum height
- Internally uses `World.getHighestBlockYAt` (world coordinates) for a direct query — lightweight and efficient

## Example: drawing a map

```js
import { World, getBlocks } from 'yeow-api';
import { colors } form './colors.js'

const world = await World.get('world');
const top = await world.getChunkAt(0, 0).getTopSnapshot();

// iterate the chunk (16×16)
for (let z = 0; z < 16; z++) {
  for (let x = 0; x < 16; x++) {
    const key = top.getTop(x, z);            // block key
    const color = colors[key];
    // ...
  }
}
```
