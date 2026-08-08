# ItemStack（物品数据）

`ItemStack` 是 Yeow 的物品**纯数据**描述符——值语义，可序列化传输，**不绑定任何真实物品实例**。

```ts
import type { ItemStack } from 'yeow-api';

interface ItemStack {
  type: string;                                  // Material key，如 "minecraft:diamond_pickaxe"
  amount?: number;                               // 数量（默认 1）
  meta?: {
    displayName?: string;                        // 显示名（MiniMessage）
    lore?: string[];                             // 描述行（MiniMessage，每行一条）
    customModelData?: number;                    // 自定义模型数据
    unbreakable?: boolean;                       // 是否不可破坏
    hideTooltip?: boolean;                       // 是否隐藏提示
    enchantments?: Record<string, number>;       // 附魔：key → 等级（如 { "minecraft:fortune": 3 }）
    itemFlags?: string[];                        // ItemFlag 枚举名（HIDE_ATTRIBUTES 等）
  };
}
```

## 语义

- **纯数据**：描述一个物品的"长相"，不指向任何真实物品——读取/构造/传递都不会产生副作用
- **值语义**：作为参数传入时是快照；作为返回值（如 `getItemInMainHand`）时是当时的序列化数据，之后真实物品变化不会反映在已返回的对象上
- **可序列化**：跨插件、跨平台一致（协议层 JSON 载荷）

## 使用场景

```js
import { World } from 'yeow-api';

// ① 构造（GUI 物品、工具等）
const pickaxe = {
    type: 'minecraft:diamond_pickaxe',
    meta: {
        displayName: '<gradient:aqua:blue>钻石镐</gradient>',
        enchantments: { 'minecraft:fortune': 3 },
        unbreakable: true,
    },
};

// ② 读取玩家手上物品（快照）
const held = await player.getItemInMainHand();   // ItemStack | null（主手为空时 null）
console.log(held?.type, held?.meta?.enchantments);

// ③ 作为工具传给世界操作（模拟工具属性）
const world = World.getSync('world');
const b = await world.getBlock(0, 65, 0);
await b.breakNaturally(pickaxe);                 // 模拟用该工具破坏（影响掉落物/速度）
```

## 真实物品交互

`ItemStack` 是数据，**不操作真实物品**：

- `breakNaturally(tool)` 的 `tool` 是数据快照——模拟工具属性（挖掘速度/掉落物/附魔），**不消耗任何真实物品的耐久**
- 需要消耗耐久、扣除数量等真实交互：通过玩家背包 API（`getItemInMainHand` 读取 → 配合物品操作），Yeow 的背包写操作目前覆盖基础槽位读写——复杂物品操作（耐久、附魔写回）属后续规划

> 设计原则：物品的**数据描述**（ItemStack）与**游戏操作**（破坏、消耗）分离——前者纯数据，后者经显式 API。
