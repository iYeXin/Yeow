# ItemStack（物品数据）

`ItemStack` 是 Yeow 的物品**纯数据**描述符——值语义，可序列化传输，**不绑定任何真实物品实例**。

```ts
import { ItemStack } from 'yeow-api';
import type { ItemStack as ItemStackType } from 'yeow-api';

interface ItemStack {
  type: string;                                  // Material key，如 "minecraft:diamond_pickaxe"
  amount?: number;                               // 数量（默认 1）
  meta?: ItemMeta;
}

interface ItemMeta {
  displayName?: string;                          // 显示名（MiniMessage）
  lore?: string[];                               // 描述行（MiniMessage，每行一条）
  customModelData?: number;                      // 自定义模型数据
  unbreakable?: boolean;                         // 是否不可破坏
  hideTooltip?: boolean;                         // 是否隐藏提示
  enchantments?: Record<string, number>;         // 附魔：key → 等级（如 { "minecraft:fortune": 3 }）
  itemFlags?: string[];                          // ItemFlag 枚举名（HIDE_ATTRIBUTES 等）
  damage?: number;                               // 耐久损伤值（被损耗的耐久）
  color?: string | { r: number; g: number; b: number };  // 皮革盔甲染色 / 自定义药水颜色（"#RRGGBB" 或 rgb 对象）
  potionEffects?: PotionEffectData[];            // 自定义药水效果（仅药水类物品生效）
  skullOwner?: string;                           // 玩家头颅：玩家名 / UUID / base64 纹理值
  attributeModifiers?: AttributeModifierData[];  // 属性修饰符
}

interface PotionEffectData {
  type: string;          // 药水效果：minecraft 注册键（如 "minecraft:speed"；兼容旧式枚举名 "speed"/"SPEED"）
  duration?: number;     // 刻（默认 200）
  amplifier?: number;    // 等级（默认 0）
  ambient?: boolean;     // 环境粒子（信标样式，默认 false）
  particles?: boolean;   // 显示粒子（默认 true）
}

interface AttributeModifierData {
  attribute: string;                        // minecraft 注册键（如 "minecraft:attack_damage"；兼容旧式 Bukkit 枚举名 "ATTACK_DAMAGE"）
  amount: number;
  operation: 'ADD_NUMBER' | 'ADD_SCALED_AMOUNT' | 'MULTIPLY_SCALED_1';
  slot?: string;                            // mainhand / offhand / feet / legs / chest / head / body / any（默认 any）
}
```

> 附魔与属性修饰符键（`minecraft:sharpness`、`minecraft:attack_damage`）、`itemFlags`（ItemFlag）的取值域见 [值域附录](../specifications/values.md)（附魔/属性见「版本变迁域」，ItemFlag 见「直接维护的枚举清单」）。
>
> 工具/盔甲等的耐久上限通过 `Material.getMaxDurability(type)` 获取，详见 [Material API](./material.md)。

## 工具函数

`ItemStack` 命名空间提供构造与操作工具：

```ts
// 构造
const sword = ItemStack.create('minecraft:diamond_sword', 1, {
    displayName: '<red>Magic Sword</red>',
    enchantments: { 'minecraft:sharpness': 10 },
});

// 深拷贝（快照语义：修改副本不影响原对象）
const copy = ItemStack.clone(sword);

// 深度相等
ItemStack.equals(sword, copy);   // true
```

## 语义

- **纯数据**：描述一个物品的"长相"，不指向任何真实物品——读取/构造/传递都不会产生副作用
- **值语义**：作为参数传入时是快照；作为返回值（如 `getItemInMainHand`、`inventory.getItem`）时是当时的序列化数据，之后真实物品变化不会反映在已返回的对象上
- **可序列化**：跨插件、跨平台一致（协议层 JSON 载荷）
- **兼容性**：meta 字段全部可选；运行时不支持的字段静默忽略（跨版本安全）。文本字段（displayName/lore）支持 MiniMessage；color 支持 `"#RRGGBB"` 或 `{r,g,b}`

## 使用场景

```js
import { World } from 'yeow-api';

// ① 构造（自定义 Inventory 物品、工具、物品栏写入等）
const pickaxe = ItemStack.create('minecraft:diamond_pickaxe', 1, {
    displayName: '<gradient:aqua:blue>钻石镐</gradient>',
    enchantments: { 'minecraft:fortune': 3 },
    unbreakable: true,
});

// ② 读取玩家手上物品（快照，含 meta）
const held = await player.getItemInMainHand();   // ItemStack | null（主手为空时 null）
console.log(held?.type, held?.meta?.enchantments);

// ③ 写入物品栏 / 自定义 Inventory（完整 meta 生效）
await player.inventory.setItem(0, pickaxe);
await player.inventory.getItem(0);               // ItemStack | null（读回含 meta）

// ④ 作为工具传给世界操作（模拟工具属性）
const world = World.getSync('world');
const b = await world.getBlock(0, 65, 0);
await b.breakNaturally(pickaxe);                 // 模拟用该工具破坏（影响掉落物/速度）
```

## 真实物品交互

`ItemStack` 是数据，**不操作真实物品**。
