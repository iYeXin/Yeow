# Material API

查询 Material、Block、Item 注册表。

```js
import { getMaterials, getBlocks, getItems } from 'yeow-api';
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
