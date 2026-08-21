# PDC Tasks

Custom persistent data (Persistent Data Container). A generic key-value store for pdc, supporting four holders: Player, Entity, World, and Block.

All value types are `string` (JSON serialization is handled automatically by the yeow-api layer).

---

## Common Operations

The following tasks apply to Player and Entity (addressed via `uuid`).

| Task | Request | Return |
|------|------|------|
| `pdc.get` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `string` \| `null` |
| `pdc.set` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>", "value": "<value>" }` | `true` |
| `pdc.has` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `boolean` |
| `pdc.remove` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `true` |
| `pdc.keys` | `{ "uuid": "<uuid>" \| "world": "<name>" }` | `string[]` (full key format, including namespace) |
| `pdc.getAll` | `{ "uuid": "<uuid>" \| "world": "<name>" }` | `{ "<key>": "<value>" }` (key-value pairs of the **current plugin's namespace**, key without namespace) |

> `pdc.getAll` (2026-08-13): only returns the keys of the current plugin's namespace — combined with `_plugin` ownership injection, to avoid cross-plugin data interference.

---

## Holder Addressing

The runtime resolves the `uuid` field with the following priority:

1. **Player** — looks up an online player by UUID
2. **Entity** — looks up a loaded entity by UUID
3. **World** — if a `world` field exists, looks up by world name

## Block Addressing

Addresses a block via the combined `world` + `x` + `y` + `z` fields:

```json
{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "key": "<key>" }
```

---

## Key Format

Keys support the `namespace:key` format (e.g. `myplugin:mykey`). **A bare string (no colon) uses the plugin namespace by default** (the `_plugin` task parameter, injected by the runtime) — bare keys of different plugins do not conflict with each other; it falls back to `yeow` when `_plugin` is missing. Keys are automatically converted to lowercase before storage, and allowed characters are limited to `[a-z0-9/._-]`.

```json
// Equivalence relationships in the plugin folia-test scenario:
{ "key": "myplugin:mykey" }    // explicit namespace
{ "key": "mykey" }             // → <pluginName>:mykey (e.g. folia-test:mykey)
{ "key": "MyPlugin.DeathLoc" } // → <pluginName>:myplugin.deathloc (auto-converted to lowercase)

// Legacy data compatibility: historical versions default to the yeow namespace, migration read:
{ "key": "yeow:mykey" }
```
