# PDC API

Persistent Data Container — persists custom key-value data. Supports four holder types: Player, Entity, World, and Block.

```js
import { pdcGet, pdcSet, pdcHas, pdcRemove, pdcKeys, pdcGetAll,
    pdcGetBlock, pdcSetBlock, pdcHasBlock, pdcRemoveBlock, pdcKeysBlock, pdcGetAllBlock } from 'yeow-api';
```

## Automatic JSON serialization (recommended)

`pdcSet` / `pdcGet` **automatically JSON serialize/deserialize** — any JSON-serializable value can be stored and read directly, no manual `JSON.stringify` / `JSON.parse` needed:

```js
// Write any value: objects, numbers, booleans, etc.
await pdcSet(uuid, 'lastLogin', { ts: Date.now(), ip: '1.2.3.4' });
await pdcSet(uuid, 'kills', 42);

// Read auto-deserialized (generic)
const lastLogin = await pdcGet(uuid, 'lastLogin');   // { ts, ip } | null
const kills = await pdcGet(uuid, 'kills');           // 42
```

- When there is no value, `get` returns `null`
- Old data (non-JSON strings written by older versions) is returned **as-is as a string** by `get`, without erroring
- For low-level string reads/writes use `pdcGetRaw` / `pdcSetRaw` (for cross-language/version data exchange)

## Player / Entity / World

```js
// Write (any serializable value)
await pdcSet(uuid, 'myplugin.key', { score: 10 });

// Read (auto deserialized)
const val = await pdcGet(uuid, 'myplugin.key');  // unknown | null

// Check existence
const exists = await pdcHas(uuid, 'myplugin.key');  // boolean

// Get all keys (full key format, including namespace)
const keys = await pdcKeys(uuid);  // string[]

// Read all key-values of this plugin's namespace (each auto deserialized)
const all = await pdcGetAll(uuid);  // Record<string, unknown>

// Delete
await pdcRemove(uuid, 'myplugin.key');
```

`uuid` is the UUID of a Player or Entity. World PDC uses the same set of functions; at runtime the holder is automatically resolved in the order UUID → Player → Entity → World.

## Player / Block instance methods

`Player` and `Block` (which must include a location, i.e. instances returned by `world.getBlock`) provide PDC convenience methods:

```js
// Player
await player.setPdc('kills', 42);
const kills = await player.getPdc('kills');   // 42
await player.hasPdc('kills');                 // true
await player.removePdc('kills');
await player.keysPdc();                       // string[]
await player.getAllPdc();                     // Record<string, unknown>

// Block (addressed internally by world coordinates; Blocks without a location throw)
const block = await world.getBlock(0, 65, 0);
await block.setPdc('owner', 'Notch');
const owner = await block.getPdc('owner');
```

## Block

Block PDC is addressed by coordinates (same JSON semantics as entities):

```js
const val = await pdcGetBlock('world', 0, 64, 0, 'myplugin.key');
await pdcSetBlock('world', 0, 64, 0, 'myplugin.key', { data: 1 });
await pdcHasBlock('world', 0, 64, 0, 'myplugin.key');
await pdcRemoveBlock('world', 0, 64, 0, 'myplugin.key');
await pdcKeysBlock('world', 0, 64, 0);
await pdcGetAllBlock('world', 0, 64, 0);
```

> **Persistence**: writing block PDC immediately updates the block state and persists it (`TileState.update()`), surviving restarts. **Offline players**: reading/writing an offline player's PDC automatically falls back to offline storage (only effective for players who have played on that server; older server builds may fail to write offline data and return `false`).

## Key format

Supports the `namespace:key` format (e.g. `myplugin:config`); **a plain string (no colon) defaults to the plugin namespace** — bare keys (e.g. `score`) from different plugins don't collide:

```js
await pdcSet(uuid, 'score', 1);         // stored as <plugin>:score (e.g. myplugin:score)
await pdcSet(uuid, 'minecraft:level', 2);  // explicit namespace
```

> **Note:** uppercase letters in a key are automatically lowercased at runtime (e.g. `MyPlugin.deathLoc` → `myplugin.deathloc`). Allowed characters: `a-z` `0-9` `/` `.` `_` `-`. **The namespace changed from `yeow` to the plugin name**: if historical data is stored under the `yeow:` namespace, access it explicitly via `yeow:key` to migrate.

## Example

```js
// Save player custom state (any object)
await player.setPdc('lastLogin', { ts: Date.now(), ip: '1.2.3.4' });

const lastLogin = await player.getPdc('lastLogin');
if (lastLogin) {
    player.sendMessage(`Last login: ${new Date(lastLogin.ts)}`);
}
```
