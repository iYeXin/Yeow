# Block API

方块对象。由 `world.getBlock()` 获取，默认为异步（`Promise`），同步版本加 `Sync` 后缀。

```js
import { Block } from 'yeow-api';
```

## 属性

```ts
new Block(world, x, y, z, type)

block.world          // 世界名
block.x / y / z      // 坐标
block.type           // 方块类型（如 "minecraft:stone"）
block.location       // Location 对象（同步属性）
```

## 方法

```ts
block.isSolid()              // Promise<boolean> — 是否为固体
block.isSolidSync()          // boolean
block.isLiquid()             // Promise<boolean> — 是否为液体
block.isLiquidSync()         // boolean
block.isEmpty()              // Promise<boolean> — 是否为空（空气）
block.isEmptySync()          // boolean
block.breakNaturally()       // Promise<boolean> — 自然破坏并掉落物品
block.breakNaturallySync()   // boolean
block.breakNaturally(tool?)  // 可选 ItemStack 参数指定工具
```

## 示例

```ts
import { World } from 'yeow-api';

const world = World.getSync('world');
const block = world.getBlockSync(0, 65, 0);

if (block.type === 'minecraft:stone') {
    await block.breakNaturally();
}
```
