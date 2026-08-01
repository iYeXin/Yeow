# Inventory 事件

## `inventoryOpen`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 打开库存的玩家 |
| `inventoryType` | string | 库存类型（`CHEST`、`WORKBENCH`、`FURNACE` 等） |
| `title` | string | 库存标题 |

## `inventoryClose`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 关闭库存的玩家 |
| `inventoryType` | string | 库存类型 |

## `inventoryClick`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 点击的玩家 |
| `slot` | number | 槽位索引（-999 表示窗口外点击） |
| `hotbarKey` | number | 快捷键按键索引（0-8），-1 表示未使用快捷键 |
| `action` | string | 点击类型（`LEFT`、`RIGHT`、`SHIFT_LEFT`、`DOUBLE_CLICK`、`DROP` 等） |
| `inventoryType` | string | 库存类型 |
| `isLeftClick` | boolean | 是否左键 |
| `isRightClick` | boolean | 是否右键 |
| `isShiftClick` | boolean | 是否按住 Shift |
| `clickedItem` | object \| null | `{ "type": "<key>", "amount": <int> }` |
| `cursorItem` | object \| null | 光标上的物品 `{ "type": "<key>", "amount": <int> }` |
