# Inventory Events

## `inventoryOpen`

| Field           | Type          | Description |
|-----------------|---------------|-------------|
| `player`        | string (UUID) | The player who opened the inventory |
| `inventoryType` | string        | The inventory type (`CHEST`, `WORKBENCH`, `FURNACE`, etc.) |
| `title`         | string        | The inventory title |

## `inventoryClose`

| Field           | Type          | Description |
|-----------------|---------------|-------------|
| `player`        | string (UUID) | The player who closed the inventory |
| `inventoryType` | string        | The inventory type |
| `inventoryId`   | string \| null | (2026-08-13) When the closed inventory is a Yeow custom Inventory (created via `inventory.create`): its handle id; otherwise omitted |

## `inventoryClick`

| Field           | Type          | Description |
|-----------------|---------------|-------------|
| `player`        | string (UUID) | The player who clicked |
| `slot`          | number        | Slot index (-999 means a click outside the window) |
| `hotbarKey`     | number        | Hotbar key index (0-8); -1 means no hotbar key was used |
| `action`        | string        | Click type (`LEFT`, `RIGHT`, `SHIFT_LEFT`, `DOUBLE_CLICK`, `DROP`, etc.) |
| `inventoryType` | string        | The inventory type |
| `isLeftClick`   | boolean       | Whether it was a left click |
| `isRightClick`  | boolean       | Whether it was a right click |
| `isShiftClick`  | boolean       | Whether Shift was held |
| `clickedItem`   | object \| null | `{ "type": "<key>", "amount": <int> }` (**writable**: `mods.clickedItem` replaces the clicked item, commonly used for locking/replacing) |
| `cursorItem`    | object \| null | The item on the cursor `{ "type": "<key>", "amount": <int> }` (**writable**: `mods.cursorItem` replaces the cursor item, `amount: 0` clears it) |
| `inventoryId`   | string \| null | (2026-08-13) When the click happens on a Yeow custom Inventory (created via `inventory.create`): its handle id; otherwise omitted |

> **`inventoryId` semantics**: the event bridge identifies custom Inventories through an Inventory reverse-lookup table. In scenarios with multiple custom Inventories, use `inventoryId` to accurately determine which inventory a click/close belongs to, instead of relying on `inventoryType=CHEST` + slot inference.
