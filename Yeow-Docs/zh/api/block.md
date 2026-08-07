# Block API

Yeow 把"方块"分为两个概念：

| 类 | 语义 | 用途 |
| --- | ---- | ---- |
| **`Block`** | **数据层面**的方块描述符（类型 + 方块状态键值对，Minecraft 原版概念），**不绑定坐标** | 构造/描述方块，传给 `world.setBlock` |
| **`WorldBlock`** | 世界中的方块（位置 + 数据描述符） | 由 `world.getBlock(x, y, z)` 返回，查询/操作世界中的方块 |

```js
import { Block, WorldBlock } from 'yeow-api';
```

## Block（数据描述符）

```ts
Block.of('minecraft:stone')                         // 无状态
Block.of('minecraft:water', { level: '8' })         // 带状态（状态值统一为字符串）
new Block(type, state?)
block.type          // "minecraft:stone"
block.state         // { level: "8" } | undefined
block.withState({ waterlogged: 'true' })            // 派生新描述符（原对象不变）
block.matches('minecraft:water', { level: '8' })    // 类型/状态匹配
```

状态对应 **Minecraft 原版的方块状态**（键值对枚举，值统一为字符串），如 `minecraft:water[level=8]` → `{ type: "minecraft:water", state: { level: "8" } }`。

## WorldBlock（世界中的方块）

由 `world.getBlock(x, y, z)` 返回：

```ts
const b = await world.getBlock(0, 65, 0);   // WorldBlock | null

b.world / b.x / b.y / b.z   // 位置
b.type                      // "minecraft:stone"（获取时刻的快照）
b.state                     // { waterlogged: "true" } | undefined（获取时刻的快照）
b.location                  // Location（同步属性）
b.toBlock()                 // Block 描述符视图（可传给 world.setBlock）
b.isSolid()                 // Promise<boolean> — 是否为固体（实时查询世界）
b.isSolidSync()             // boolean
b.isLiquid()                // Promise<boolean> — 是否为液体（实时）
b.isLiquidSync()            // boolean
b.isEmpty()                 // Promise<boolean> — 是否为空（实时）
b.isEmptySync()             // boolean
b.breakNaturally()          // Promise<boolean> — 自然破坏并掉落物品（实时操作世界）
b.breakNaturallySync()      // boolean
```

### 实时性语义

- **属性是快照**：`type` / `state` 固定为 `getBlock` 获取时刻的值——之后世界变化不会自动更新
- **方法是实时的**：`isSolid` / `isEmpty` / `breakNaturally` 等按坐标实时查询/操作世界，不受快照影响
- **获取最新状态**：调用 `refresh()` / `refreshSync()` 返回该位置的最新 `WorldBlock`（原对象不变）；若方块被移除，返回 `null`

## 放置方块

`world.setBlock(x, y, z, block)` 接受 **`Block` 描述符或字符串**（兼容）；带状态的方块请构造 `Block` 对象：

```ts
import { World, Block } from 'yeow-api';

const world = World.getSync('world');

await world.setBlock(0, 65, 0, 'minecraft:stone');                                  // 字符串（兼容，无状态）
await world.setBlock(0, 65, 1, Block.of('minecraft:water', { level: '8' }));        // 描述符（带状态）
await world.setBlock(0, 65, 2, Block.of('minecraft:chest', { facing: 'north' }));   // 描述符（带状态）
```

## 示例

```ts
import { World, Block } from 'yeow-api';

const world = World.getSync('world');
const b = world.getBlockSync(0, 65, 0);

if (b?.matches('minecraft:stone')) {
    await b.breakNaturally();
}

// 读取状态后原样写回（带状态的水桶放置）
const water = world.getBlockSync(1, 65, 0);
await world.setBlock(1, 65, 0, water.toBlock());
```
