# Inventory API

统一容器抽象——三种持有者使用**同一套方法**：

| 持有者 | 获取方式 | 说明 |
|---|---|---|
| 玩家物品栏 | `player.inventory` | uuid 寻址 |
| 容器方块 | `block.getInventory()` | 世界坐标寻址（Chest / Furnace / Hopper / Barrel / Dispenser / Dropper / BrewingStand 等 Container；需方块有 location） |
| 自定义 Inventory（自定义箱子界面） | `await Inventory.create(size, title)` | 句柄 id 寻址（原 GUI 改名统一） |

```js
import { Inventory, ItemStack } from 'yeow-api';

// ① 玩家物品栏
await player.inventory.setItem(0, ItemStack.create('minecraft:diamond', 5));
const slot0 = await player.inventory.getItem(0);   // ItemStack | null（含 meta）

// ② 容器方块（需 location，即 world.getBlock 返回的 Block）
const chest = await world.getBlock(10, 64, 10);
await chest.getInventory().setItem(0, ItemStack.create('minecraft:stone', 64));
const chestItem = await chest.getInventory().getItem(0);
const type = await chest.getInventory().getType();  // "CHEST"（方块实体类型）

// ③ 自定义 Inventory
const inv = await Inventory.create(27, '<gold>Shop</gold>');
await inv.setItem(11, ItemStack.create('minecraft:diamond_sword', 1, { displayName: '<red>Magic Sword</red>' }));
await inv.open(player.uuid);
await inv.destroy();
```

## 通用方法（三种持有者）

```ts
inv.getItem(slot)                 // Promise<ItemStack | null> — 读回快照（含 meta）
inv.getItemSync(slot)
inv.setItem(slot, item | null)    // 设置槽位（完整 ItemStack 含 meta；null 清空）
inv.setItemSync(slot, item | null)
inv.setItems(slots[], item | null) // 批量设置（分页/布局用）
inv.fill(item)                    // 用同一物品填充全部槽位
inv.addItem(item)                 // 添加物品到空位 → Promise<number>（未放入数量；0=全部放入。
                                  //   玩家物品栏溢出部分掉落在地上，同样返回 0）
inv.addItemSync(item)
inv.removeItem(item)              // 移除指定物品 → Promise<number>（未移除数量；0 = 全部移除成功）
inv.removeItemSync(item)          //   按类型 + meta 匹配，amount 默认 1
inv.clear(slot?)                  // 清空（slot 可选，不传清空全部）
inv.clearSync(slot?)
inv.getSize()                     // Promise<number> — 容器槽位数
inv.getType()                     // Promise<string> — "PLAYER" | "CUSTOM" | 方块实体类型名（如 "CHEST"）
inv.getContents()                 // Promise<(ItemStack | null)[]> — 全槽位快照（空槽 null，长度 = 槽位数）
inv.setContents(items)            // 整容器写入（短数组只写前段；null 清空对应槽位）
```

> 容器类型取值（`getType()` 的 `"CHEST"` 等 InventoryType）见 [值域附录 · 直接维护的枚举清单](../specifications/values.md#二直接维护的枚举清单)。

| 参数 | 类型 | 说明 |
|------|------|------|
| `slot` | `number` | 槽位索引（玩家：0-35 主物品栏，36-39 装备栏等；方块：0 ~ 容器槽位） |
| `item` | `ItemStack \| null` | 完整物品（含 meta，见 [ItemStack](item.md)）；`null` 清空槽位 |

## 自定义 Inventory 专属

```ts
const inv = await Inventory.create(27, '<gold>Shop</gold>');
// size 必须为 9 的倍数（9 ~ 54）；title 支持 MiniMessage

inv.toString()                    // 句柄 id（与 inventoryClick/Close 事件的 inventoryId 字段一致）
await inv.open(player);           // 为玩家打开（接受 Player 对象或 uuid）
await inv.close();                // 关闭所有查看者
await inv.closePlayer(player);    // 仅关闭指定玩家（接受 Player 对象或 uuid）
await inv.getViewers();           // string[] — 查看者 uuid 列表
await inv.destroy();              // 销毁（关闭所有查看者并释放）
```

### 与事件联动（inventoryId）

点击/关闭事件携带 `inventoryId`——多自定义 Inventory 场景识别交互归属：

```js
const shop = await Inventory.create(27, '<gold>Shop</gold>');

eventOn('inventoryClick', (e) => {
    if (e.inventoryId !== shop.toString()) return;   // 只处理本 Inventory 的点击
    if (e.slot === 11) {
        e.cancelled = true;   // 处理购买等逻辑
    }
});

eventOn('inventoryClose', (e) => {
    if (e.inventoryId === shop.toString()) {
        // Inventory 被玩家关闭
    }
});
```

## 生命周期

自定义 Inventory 由插件创建和管理，`destroy()` 显式释放；忘记销毁时在热重载或插件停用时自动清理。`inventoryClick` 事件可拦截其中点击行为。

## 示例

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
