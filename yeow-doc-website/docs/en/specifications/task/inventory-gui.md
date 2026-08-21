# Inventory Tasks

Unified container operations (2026-08-13 refactor: the former `gui.*` task family was merged into `inventory.*`).

**Three addressing modes** (task parameters carry the corresponding fields according to the holder):

| Holder | Addressing field | Description |
|------|----------|------|
| Player inventory | `uuid` | Player UUID |
| Container block | `world` + `x` + `y` + `z` | Container block entity (Chest / Furnace / Hopper / Barrel / Dispenser / Dropper / BrewingStand, etc.); throws an error if not a container |
| Custom Inventory (formerly GUI) | `id` | The handle id created by `inventory.create` |

`slot` is the slot index (player: 0-35 main inventory, 36-39 armor, 40 offhand; block: 0 ~ container slots).

---

## Content Operations (common to the three addressing modes)

| Task | Request | Return |
|------|------|------|
| `inventory.getItem` | `{ address, "slot": <int> }` | `ItemStack` \| `null` (including meta) |
| `inventory.setItem` | `{ address, "slot": <int>, "item": <ItemStack \| null> }` | `true` |
| `inventory.setItems` | `{ address, "slots": [<int>, ...], "item": <ItemStack \| null> }` | `true` |
| `inventory.fill` | `{ address, "item": <ItemStack> }` | `true` |
| `inventory.addItem` | `{ address, "item": <ItemStack> }` | `int` (amount not added; 0 = all added. Overflow from a player inventory drops on the ground, still returns 0) |
| `inventory.removeItem` | `{ address, "item": <ItemStack> }` | `int` (amount not removed; 0 = all removed. Matched by type + meta, amount defaults to 1) |
| `inventory.clear` | `{ address, "slot": <int>? }` (slot is optional; clears everything if not passed) | `true` |
| `inventory.getSize` | `{ address }` | `int` (container slot count) |
| `inventory.getType` | `{ address }` | `string` (`"PLAYER"` / `"CUSTOM"` / block-entity type name such as `"CHEST"`) |
| `inventory.getContents` | `{ address }` | `(ItemStack \| null)[]` (full slot snapshot, empty slots are null, length = container slot count) |
| `inventory.setContents` | `{ address, "items": [(ItemStack \| null), ...] }` | `true` (writes the whole container; a short array only writes the prefix, a long array ignores the excess) |

## Custom Inventory Lifecycle (`id` addressing)

| Task | Request | Return |
|------|------|------|
| `inventory.create` | `{ "id": "<handle>", "size": <int>, "title": "<text>" }` | `string` (id, matches the request) |
| `inventory.open` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `inventory.close` | `{ "id": "<handle>" }` | `true` (closes all viewers) |
| `inventory.closePlayer` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `inventory.getViewers` | `{ "id": "<handle>" }` | `string[]` (viewer uuid list) |
| `inventory.destroy` | `{ "id": "<handle>" }` | `true` (destroys and closes all viewers) |

`size` must be a multiple of 9 (maximum 54). The `_plugin` ownership of a custom Inventory is injected by the runtime and automatically cleaned up on plugin unload/hot-reload (when `inventory.destroy` is not called).

### Event Integration (inventoryId)

Interactions on a custom Inventory from the `inventoryClick` / `inventoryClose` events carry the `inventoryId` field (the handle id passed to `inventory.create`) — the event bridge identifies them through the Inventory reverse-lookup table. Non-custom inventories (backpack, chests, etc.) do not carry it.

---

## ItemStack Full Format

`ItemStack` is a **pure-data** payload (value-semantics snapshot, not bound to a real item) — see the [ItemStack API](../../api/item.md) for fields and semantics:

```json
{
  "type": "minecraft:diamond_sword",
  "amount": 1,
  "meta": {
    "displayName": "<text>",
    "lore": ["<line1>", "<line2>"],
    "customModelData": 123,
    "unbreakable": true,
    "hideTooltip": false,
    "enchantments": { "minecraft:sharpness": 5, "minecraft:unbreaking": 3 },
    "itemFlags": ["HIDE_ENCHANTS", "HIDE_ATTRIBUTES"],
    "damage": 3,
    "color": "#FF0000",
    "potionEffects": [{ "type": "minecraft:speed", "duration": 200, "amplifier": 1 }],
    "skullOwner": "Notch",
    "attributeModifiers": [{ "attribute": "minecraft:attack_damage", "amount": 5, "operation": "ADD_NUMBER", "slot": "mainhand" }]
  }
}
```

| Field | Type | Required | Description |
|------|------|------|------|
| `type` | string | Yes | Material namespace key |
| `amount` | int | No (default 1) | Quantity |
| `meta` | object | No | Item metadata |
| `meta.displayName` | string | No | Display name (MiniMessage format) |
| `meta.lore` | string[] | No | Item description |
| `meta.customModelData` | int | No | Custom model data |
| `meta.unbreakable` | bool | No | Unbreakable |
| `meta.hideTooltip` | bool | No | Hide tooltip |
| `meta.enchantments` | object | No | Enchantment map (key → level). key is the enchantment namespace key (e.g. `minecraft:sharpness`) |
| `meta.itemFlags` | string[] | No | Item flags (ItemFlag enum names, e.g. `HIDE_ENCHANTS`) |
| `meta.damage` | int | No | Durability damage value (lost durability) |
| `meta.color` | string \| object | No | Leather armor dye / custom potion color (`"#RRGGBB"` or `{r,g,b}`) |
| `meta.potionEffects` | object[] | No | Custom potion effects (only for potion-type items): `{type, duration?, amplifier?, ambient?, particles?}` (`type` is a minecraft registry key, e.g. `minecraft:speed`; compatible with legacy enum names) |
| `meta.skullOwner` | string | No | Player skull: player name / UUID / base64 texture value |
| `meta.attributeModifiers` | object[] | No | Attribute modifiers: `{attribute, amount, operation, slot?}` (`attribute` is a minecraft registry key such as `minecraft:attack_damage`, compatible with legacy enum names; operation: `ADD_NUMBER`/`ADD_SCALED_AMOUNT`/`MULTIPLY_SCALED_1`; slot: `mainhand`/`offhand`/`feet`/`legs`/`chest`/`head`/`body`/`any`) |

> Extended fields (damage/color/potionEffects/skullOwner/attributeModifiers, 2026-08-13): fields not supported by the runtime are **silently ignored** (cross-version compatibility).
