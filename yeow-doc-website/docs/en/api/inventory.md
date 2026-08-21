# Inventory API

A unified container abstraction — all three holder types use **the same set of methods**:

| Holder | How to get it | Description |
|---|---|---|
| Player inventory | `player.inventory` | Addressed by uuid |
| Container block | `block.getInventory()` | Addressed by world coordinates (Containers such as Chest / Furnace / Hopper / Barrel / Dispenser / Dropper / BrewingStand; requires the block to have a location) |
| Custom Inventory (custom chest UI) | `await Inventory.create(size, title)` | Addressed by handle id (unified from the former GUI) |

```js
import { Inventory, ItemStack } from 'yeow-api';

// ① Player inventory
await player.inventory.setItem(0, ItemStack.create('minecraft:diamond', 5));
const slot0 = await player.inventory.getItem(0);   // ItemStack | null (including meta)

// ② Container block (requires a location, i.e. the Block returned by world.getBlock)
const chest = await world.getBlock(10, 64, 10);
await chest.getInventory().setItem(0, ItemStack.create('minecraft:stone', 64));
const chestItem = await chest.getInventory().getItem(0);
const type = await chest.getInventory().getType();  // "CHEST" (block entity type)

// ③ Custom Inventory
const inv = await Inventory.create(27, '<gold>Shop</gold>');
await inv.setItem(11, ItemStack.create('minecraft:diamond_sword', 1, { displayName: '<red>Magic Sword</red>' }));
await inv.open(player.uuid);
await inv.destroy();
```

## Common methods (all three holders)

```ts
inv.getItem(slot)                 // Promise<ItemStack | null> — read-back snapshot (including meta)
inv.getItemSync(slot)
inv.setItem(slot, item | null)    // set a slot (full ItemStack including meta; null clears it)
inv.setItemSync(slot, item | null)
inv.setItems(slots[], item | null) // batch set (for paging/layout)
inv.fill(item)                    // fill every slot with the same item
inv.addItem(item)                 // add an item to empty slots → Promise<number> (amount not placed; 0 = all placed.
                                  //   Overflow from a player inventory drops on the ground, and also returns 0)
inv.addItemSync(item)
inv.removeItem(item)              // remove a specific item → Promise<number> (amount not removed; 0 = all removed)
inv.removeItemSync(item)          //   matches by type + meta; amount defaults to 1
inv.clear(slot?)                  // clear (slot optional; if omitted clears everything)
inv.clearSync(slot?)
inv.getSize()                     // Promise<number> — number of container slots
inv.getType()                     // Promise<string> — "PLAYER" | "CUSTOM" | block entity type name (e.g. "CHEST")
inv.getContents()                 // Promise<(ItemStack | null)[]> — snapshot of all slots (empty slots are null, length = slot count)
inv.setContents(items)            // write the whole container (a short array only writes the leading part; null clears the corresponding slot)
```

> For the container type values (the InventoryType such as `getType()`'s `"CHEST"`), see [Values appendix · Directly maintained enum list](../specifications/values.md#二直接维护的枚举清单).

| Parameter | Type | Description |
|------|------|------|
| `slot` | `number` | Slot index (player: 0-35 main inventory, 36-39 armor slots, etc.; block: 0 ~ container slots) |
| `item` | `ItemStack \| null` | Full item (including meta, see [ItemStack](item.md)); `null` clears the slot |

## Custom Inventory only

```ts
const inv = await Inventory.create(27, '<gold>Shop</gold>');
// size must be a multiple of 9 (9 ~ 54); title supports MiniMessage

inv.toString()                    // handle id (matches the inventoryId field of the inventoryClick/Close events)
await inv.open(player);           // open for a player (accepts a Player object or uuid)
await inv.close();                // close all viewers
await inv.closePlayer(player);    // close only the given player (accepts a Player object or uuid)
await inv.getViewers();           // string[] — list of viewer uuids
await inv.destroy();              // destroy (closes all viewers and releases)
```

### Interacting with events (inventoryId)

The click/close events carry an `inventoryId` — used to identify which interaction belongs to which inventory in multi-custom-Inventory scenarios:

```js
const shop = await Inventory.create(27, '<gold>Shop</gold>');

eventOn('inventoryClick', (e) => {
    if (e.inventoryId !== shop.toString()) return;   // only handle clicks on this Inventory
    if (e.slot === 11) {
        e.cancelled = true;   // handle purchase logic etc.
    }
});

eventOn('inventoryClose', (e) => {
    if (e.inventoryId === shop.toString()) {
        // Inventory was closed by a player
    }
});
```

## Lifecycle

A custom Inventory is created and managed by the plugin, and is explicitly released with `destroy()`; if forgotten, it is cleaned up automatically on hot reload or plugin shutdown. The `inventoryClick` event can intercept clicks inside it.

## Example

```js
const shop = await Inventory.create(27, '<gold>Shop</gold>');

await shop.setItem(11, ItemStack.create('minecraft:diamond_sword', 1, {
    displayName: '<red>Magic Sword</red>',
    lore: ['<gray>+10 Attack Damage</gray>'],
    enchantments: { 'minecraft:sharpness': 10, 'minecraft:unbreaking': 3 },
    itemFlags: ['HIDE_ENCHANTS'],
}));

await shop.setItem(15, ItemStack.create('minecraft:diamond', 3, {
    displayName: '<aqua>3 Diamonds</aqua>',
}));

await shop.open(player.uuid);
```
