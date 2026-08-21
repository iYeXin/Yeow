# Material API

Query the Material, Block, and Item registries, plus material-level static checks.

```js
import { Material, getMaterials, getBlocks, getItems } from 'yeow-api';
```

## Methods

```js
// Get all Materials (including the isBlock/isItem flags)
const mats = await getMaterials();
// → [{ key:"minecraft:stone", isBlock:true, isItem:true }, ...]

// Blocks only
const blocks = await getBlocks();
// → ["minecraft:stone", "minecraft:dirt", ...]

// Items only
const items = await getItems();
// → ["minecraft:diamond", "minecraft:apple", ...]
```

## Material (material-level static checks)

`Material` provides **inherent property checks based on the type (material)** — independent of coordinates/state, and it does not query the world:

```ts
Material.isSolid('minecraft:stone');      // Promise<boolean> — whether it is solid
Material.isSolidSync('minecraft:stone');  // boolean
Material.isAir('minecraft:air');          // Promise<boolean> — whether it is air
Material.isAirSync('minecraft:air');      // boolean
Material.getMaxDurability('minecraft:diamond_pickaxe');  // Promise<number> — max durability (0 for items without durability)
Material.getMaxDurabilitySync('minecraft:diamond_pickaxe'); // number
```

Notes:

- `getMaxDurability` returns the durability cap of tools/armor etc.; non-durable items return `0`; unknown types throw an error
- The checks are independent of block state (e.g. `minecraft:chest[facing=...]` is solid in any state)
- A `Block` instance's `isSolid()` etc. delegate to this

> For the value format of material / block / item keys (`minecraft:xxx`), see [Values appendix · Version transition domain](../specifications/values.md#四版本变迁域规则--引用).

## MaterialInfo

```ts
interface MaterialInfo {
  key: string;        // namespaced key, e.g. "minecraft:stone"
  isBlock: boolean;   // whether it is a block
  isItem: boolean;    // whether it is an item
}
```

## Example

```js
// Get the list of all blocks
const blocks = await getBlocks();
console.log(`Server has ${blocks.length} blocks registered`);

// Find all plantable blocks
const mats = await getMaterials();
const plantable = mats.filter(m => m.isBlock && m.key.includes('sapling'));
```

## Caching

After the first call, the three methods cache their results on the JS side; later calls **trigger no task at all** and return the reference directly.

- `getMaterials()` — the returned array and its inner objects are all frozen with `Object.freeze`
- `getBlocks()` / `getItems()` — the returned arrays are frozen with `Object.freeze` (the elements are immutable strings)

The cache is only valid for the plugin's lifetime. It is invalidated after a hot reload or shutdown, and fetched again on the next call.
