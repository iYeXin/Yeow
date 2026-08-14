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
| `inventoryId` | string \| null | （2026-08-13）关闭的为 Yeow 自定义 Inventory（`inventory.create` 创建）时：其句柄 id；否则缺省 |

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
| `clickedItem` | object \| null | `{ "type": "<key>", "amount": <int> }`（**可回写**：`mods.clickedItem` 替换点击物品，常用于锁定/替换） |
| `cursorItem` | object \| null | 光标上的物品 `{ "type": "<key>", "amount": <int> }`（**可回写**：`mods.cursorItem` 替换光标物品，`amount: 0` 表示清空） |
| `inventoryId` | string \| null | （2026-08-13）点击发生在 Yeow 自定义 Inventory（`inventory.create` 创建）上时：其句柄 id；否则缺省 |

> **inventoryId 语义**：事件桥通过 Inventory 反查表识别自定义 Inventory。多自定义 Inventory 场景用 `inventoryId` 精确区分点击/关闭归属，不再依赖 `inventoryType=CHEST` + 槽位推断。
