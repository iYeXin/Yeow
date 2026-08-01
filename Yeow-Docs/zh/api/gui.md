# GUI API

自定义 GUI（箱子界面）。

```js
import { createGUI, setGUIItem, openGUI, closeGUI, destroyGUI, fillGUI, clearGUI } from 'yeow-api';
```

## 创建

```js
const gui = await createGUI(27, '<gold>My Shop</gold>');
// size 必须为 9 的倍数（9 ~ 54）
// title 支持 MiniMessage 格式
```

`createGUI` 返回 `GUIHandle`，后续操作均通过它寻址。

## 方法

```js
// 设置槽位物品
await setGUIItem(gui, 0, {
    type: 'minecraft:diamond', amount: 64,
    meta: { displayName: '<rainbow>Diamond</rainbow>' }
});

// 填充全部槽位
await fillGUI(gui, { type: 'minecraft:gray_stained_glass_pane', amount: 1 });

// 清空
await clearGUI(gui);

// 打开 / 关闭
await openGUI(gui, player.uuid);
// 玩家点击物品时触发 inventoryClick 事件

// 销毁（释放资源）
await destroyGUI(gui);
```

## ItemStack

```ts
interface ItemStack {
  type: string;
  amount?: number;
  meta?: {
    displayName?: string;      // MiniMessage 格式
    lore?: string[];           // MiniMessage 格式
    customModelData?: number;
    unbreakable?: boolean;
    hideTooltip?: boolean;
    enchantments?: Record<string, number>;  // { "sharpness": 5 }
    itemFlags?: string[];            // ["HIDE_ENCHANTS", ...]
  };
}
```

## 生命周期

GUI 由插件创建和管理。`destroyGUI` 显式释放。若忘记销毁，在热重载或插件停用时自动清理。`inventoryClick` 事件可拦截 GUI 内的点击行为。

## 示例

```js
const shop = await createGUI(27, '<gold>Shop</gold>');

await setGUIItem(shop, 11, {
    type: 'minecraft:diamond_sword',
    meta: {
        displayName: '<red>Magic Sword</red>',
        lore: ['<gray>+10 Attack Damage</gray>'],
        enchantments: { sharpness: 10, unbreaking: 3 },
        itemFlags: ['HIDE_ENCHANTS'],
    }
});

await setGUIItem(shop, 15, {
    type: 'minecraft:diamond', amount: 3,
    meta: { displayName: '<aqua>3 Diamonds</aqua>' }
});

await openGUI(shop, player.uuid);
```
