# Chunk API

**进阶性能工具**——用于需要批量读取区块方块数据的场景（如地图画插件渲染）。日常开发使用 [World API](world.md) 即可。

```js
import { World, Chunk, ChunkSnapshot, ChunkTopSnapshot } from 'yeow-api';
```

## 获取 Chunk

```ts
const chunk = await world.getChunkAt(0, 0);   // Chunk { x, z, world }
const chunk2 = world.getChunkAtSync(0, 0);
```

`Chunk` 本身不承载方块数据——方块数据通过快照获取。

### Chunk 数据（位置）

`Chunk` 携带区块的位置信息，可用于定位与区域计算：

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `chunk.x` | `number` | 区块 X 坐标（世界坐标 `>> 4`，即 `Math.floor(wx / 16)`） |
| `chunk.z` | `number` | 区块 Z 坐标 |
| `chunk.world` | `string` | 所属世界名（可用于 `World.get()` 取回世界对象） |

```ts
// 区块 ↔ 世界坐标换算
const wx = chunk.x * 16 + 0, wz = chunk.z * 16 + 0;  // 区块西北角
const cx = Math.floor(wx / 16), cz = Math.floor(wz / 16);  // 世界坐标 → 区块
```

### 副作用：区块加载

**`getChunkAt` / `getSnapshot` / `getTopSnapshot` 都会加载未加载的区块**——底层是 Bukkit 的 `World.getChunkAt(x, z)`，若目标区块不在内存中会强制加载（甚至触发生成）。副作用：

- 在未探索区域调用会**触发区块生成**（磁盘 IO / 主线程负载）
- 加载的区块保持驻留，直到被正常卸载
- **只读检查请用 `isChunkLoaded`**：未加载时自行决定是否 `loadChunk` / 跳过

```ts
if (!world.isChunkLoadedSync(cx, cz)) {
  // 自行决定：跳过、loadChunk 或 getChunkAt（强制加载）
}
```

## 方块类型索引

快照中每个方块表示为**类型索引**（`number`，0-65535）：

- 索引 = [`getBlocks()`](server.md) 返回数组的**下标**（如 `blocks[0]` = 列表第一个方块 key）
- 索引由运行时构建（`Registry.MATERIAL` 顺序），**仅当前运行时内有效**——重启服务器后索引可能变化，**快照数据不可持久化**，需在本次会话内使用。如需持久化，需要同时保存 `getBlocks()` 的方块索引。
- 反向映射：`const blocks = await getBlocks(); blocks[snap.getBlockIndex(x, y, z)]`

## ChunkSnapshot（完整快照，3D）

`chunk.getSnapshot()` 返回整个区块（16×16×世界高度，如 384 层）的方块索引：

```ts
const snap = await chunk.getSnapshot();   // ChunkSnapshot
snap.getBlock(x, y, z)                    // string —— 绝对高度 y 处的方块 key（如 "minecraft:stone"）
snap.getBlockIndex(x, y, z)               // number —— 原始索引
snap.data                                 // Uint16Array —— 全部索引
snap.minY / snap.height                   // 世界最低高度 / 层数
```

- **遍历顺序**：`y` 外层 → `z` 中层 → `x` 内层；偏移量 = `((y - minY) * 16 + z) * 16 + x`
- `y` 为**世界绝对高度**
- 越界坐标与未知索引回退 `'minecraft:air'`
- **操作较重**：单次快照约 10 万次索引（任务层通过 base64 打包传输，约 255 KB），但比使用 `world.getBlock()` 遍历高效得多。

## ChunkTopSnapshot（顶部快照，2D）

`chunk.getTopSnapshot()` 返回每列**最高非空气方块**的索引（256 元素，16×16）：

```ts
const top = await chunk.getTopSnapshot();  // ChunkTopSnapshot
top.getTop(x, z)                           // string —— 列最高方块 key
top.getTopIndex(x, z)                      // number —— 原始索引
top.data                                   // Uint16Array（256 元素）
```

- **遍历顺序**：`z` 外层 → `x` 内层；偏移量 = `z * 16 + x`
- 虚空列（无方块）回退 `'minecraft:air'`
- 底层用 `World.getHighestBlockYAt`（世界坐标）直接查询，轻量高效

## 示例：地图画

```js
import { World, getBlocks } from 'yeow-api';
import { colors } form './colors.js'

const world = await World.get('world');
const top = await world.getChunkAt(0, 0).getTopSnapshot();

// 遍历区块（16×16）
for (let z = 0; z < 16; z++) {
  for (let x = 0; x < 16; x++) {
    const key = top.getTop(x, z);            // 方块 key，可直接用于 world.setBlock
    const color = colors[key];
    // ...
  }
}
```
