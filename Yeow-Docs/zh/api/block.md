# Block API

`Block` 是**统一的方块概念**：数据描述符（类型 + 方块状态）+ 可选的**世界位置（`location`）**。是否带位置只影响能否执行世界操作（如 `breakNaturally`）。

```js
import { Block, Material } from 'yeow-api';
```

## Block（方块）

```ts
Block.of('minecraft:stone')                         // 纯数据描述符，无 location
Block.of('minecraft:water', { level: '8' })         // 带状态（状态值统一为字符串）
new Block(type, state?, location?)                  // location 可选
block.type                                          // "minecraft:stone"（快照）
block.state                                         // { level: "8" } | undefined（快照）
block.location                                      // Location | undefined —— 世界位置
block.withState({ waterlogged: 'true' })            // 派生新描述符（原对象不变）
block.matches('minecraft:water', { level: '8' })    // 类型/状态匹配（忽略空状态差异）

// 材料级静态判断（基于 type，委托 Material；不依赖位置/状态）：
block.isSolid()          // Promise<boolean>
block.isSolidSync()      // boolean
block.isLiquid()         // Promise<boolean> — 是否为液体（水/熔岩）
block.isLiquidSync()     // boolean
block.isAir()            // Promise<boolean> — 是否为空气
block.isAirSync()        // boolean

// 世界操作（需要 location，否则报错）：
block.breakNaturally(itemStack)       // Promise<boolean> — 模拟使用工具自然破坏并掉落物品，不传参数仍产生掉落
block.breakNaturallySync(itemStack)   // boolean
```

状态对应 **Minecraft 原版的方块状态**（键值对枚举，值统一为字符串），如 `minecraft:water[level=8]` → `{ type: "minecraft:water", state: { level: "8" } }`。

## 从世界中获取

`world.getBlock(x, y, z)` 返回带 `location` 的 `Block`（`location.yaw` / `location.pitch` 忽略，恒为 `0`）：

```ts
const b = await world.getBlock(0, 65, 0);   // Block | null（含 location）
b.type;             // "minecraft:stone"
b.state;            // { waterlogged: "true" } | undefined
b.location;         // Location { x: 0, y: 65, z: 0, yaw: 0, pitch: 0, world: "world" }
b.isSolid();        // 委托 Material.isSolid(this.type)，静态判断
await b.breakNaturally();
```

### 静态数据语义

- `type` / `state` / `location` 均为**获取时刻的快照**——之后世界变化不会自动更新
- **需要最新状态时请重新调用 `world.getBlock(x, y, z)`**（没有 refresh 方法）
- `isSolid` / `isLiquid` / `isAir` 为**材料级静态判断**（基于类型，状态不影响）——与位置无关，不查询世界

## 放置方块

`world.setBlock(x, y, z, block)` 接受 **`Block` 对象或字符串**（兼容）；带状态的方块请构造 `Block` 对象。**传入 `Block` 时忽略其 `location` 属性**——只取 `type` / `state`：

```ts
import { World, Block } from 'yeow-api';

const world = World.getSync('world');

await world.setBlock(0, 65, 0, 'minecraft:stone');                                  // 字符串（兼容，无状态）
await world.setBlock(0, 65, 1, Block.of('minecraft:water', { level: '8' }));        // 描述符（带状态）
await world.setBlock(0, 65, 2, Block.of('minecraft:chest', { facing: 'north' }));   // 描述符（带状态）
```

## Material（材料级判断）

`Block` 的 `isSolid` / `isLiquid` / `isAir` 委托 `Material` 上的同名方法；也可直接使用（无需方块实例）：

```ts
Material.isSolid('minecraft:stone');   // Promise<boolean>
Material.isSolidSync('minecraft:stone'); // boolean
Material.isLiquid('minecraft:water');  // Promise<boolean> — 原版液体仅水/熔岩
Material.isAir('minecraft:air');       // Promise<boolean>
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
await world.setBlock(1, 65, 0, water);
```
