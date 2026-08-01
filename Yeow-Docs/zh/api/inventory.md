# Inventory API

玩家物品栏。通过 `player.inventory` 获取，默认为异步（`Promise`），同步版本加 `Sync` 后缀。

```js
import { Player } from 'yeow-api';

const p = await Player.get('uuid');
p.inventory;   // Inventory 对象
```

## 方法

```ts
player.inventory.getItem(slot)              // Promise<{ type, amount } | null>
player.inventory.getItemSync(slot)          // { type, amount } | null

player.inventory.setItem(slot, type, amount?)    // Promise — 设置槽位物品
player.inventory.setItemSync(slot, type, amount?)

player.inventory.addItem(type, amount?)          // Promise — 添加物品到空位
player.inventory.addItemSync(type, amount?)

player.inventory.removeItem(type, amount?)       // Promise — 移除指定物品
player.inventory.removeItemSync(type, amount?)

player.inventory.clear(slot?)                    // Promise — 清空（slot 可选，不传清空全部）
player.inventory.clearSync(slot?)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `slot` | `number` | 槽位索引（0-35 主物品栏，36-39 装备栏等） |
| `type` | `string` | 物品类型（如 `"minecraft:diamond"`） |
| `amount` | `number` | 数量（默认 1） |

## 示例

```ts
import { Player } from 'yeow-api';

const p = await Player.get('uuid');
await p.inventory.addItem('minecraft:diamond', 64);
const slot0 = await p.inventory.getItem(0);   // { type, amount } | null
```
