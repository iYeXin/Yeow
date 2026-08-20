# Material API

查询 Material、Block、Item 注册表 + 材料级静态判断。

```js
import { Material, getMaterials, getBlocks, getItems } from 'yeow-api';
```

## 方法

```js
// 获取所有 Material（含 isBlock/isItem 标记）
const mats = await getMaterials();
// → [{ key:"minecraft:stone", isBlock:true, isItem:true }, ...]

// 仅方块
const blocks = await getBlocks();
// → ["minecraft:stone", "minecraft:dirt", ...]

// 仅物品
const items = await getItems();
// → ["minecraft:diamond", "minecraft:apple", ...]
```

## Material（材料级静态判断）

`Material` 提供**基于类型（material）的固有属性判断**——不依赖坐标/状态，不查询世界：

```ts
Material.isSolid('minecraft:stone');      // Promise<boolean> — 是否为固体
Material.isSolidSync('minecraft:stone');  // boolean
Material.isAir('minecraft:air');          // Promise<boolean> — 是否为空气
Material.isAirSync('minecraft:air');      // boolean
Material.getMaxDurability('minecraft:diamond_pickaxe');  // Promise<number> — 最大耐久（非耐用品为 0）
Material.getMaxDurabilitySync('minecraft:diamond_pickaxe'); // number
```

说明：

- `getMaxDurability` 返回工具/盔甲等的耐久上限；非耐用品返回 `0`；未知类型抛错
- 判断与方块状态无关（如 `minecraft:chest[facing=...]` 任何状态都是固体）
- `Block` 实例的 `isSolid()` 等即委托此处

> 材料 / 方块 / 物品键（`minecraft:xxx`）的取值格式见 [值域附录 · 版本变迁域](../specifications/values.md#四版本变迁域规则--引用)。

## MaterialInfo

```ts
interface MaterialInfo {
  key: string;        // 命名空间 key，如 "minecraft:stone"
  isBlock: boolean;   // 是否为方块
  isItem: boolean;    // 是否为物品
}
```

## 示例

```js
// 获取所有方块列表
const blocks = await getBlocks();
console.log(`Server has ${blocks.length} blocks registered`);

// 查找所有可种植的方块
const mats = await getMaterials();
const plantable = mats.filter(m => m.isBlock && m.key.includes('sapling'));
```

## 缓存

三个方法在首次调用后将结果缓存在 JS 侧，之后调用**不触发任何任务**，直接返回引用。

- `getMaterials()` — 返回的数组及内部对象均被 `Object.freeze` 冻结
- `getBlocks()` / `getItems()` — 返回的数组被 `Object.freeze` 冻结（元素为不可变字符串）

缓存仅在插件生命周期内有效。热重载或停用后失效，下次调用重新获取。
