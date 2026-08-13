# PDC API

Persistent Data Container — 持久化自定义键值数据。支持 Player、Entity、World、Block 四种持有者。

```js
import { pdcGet, pdcSet, pdcHas, pdcRemove, pdcKeys, pdcGetAll,
    pdcGetBlock, pdcSetBlock, pdcHasBlock, pdcRemoveBlock, pdcKeysBlock, pdcGetAllBlock } from 'yeow-api';
```

## 自动 JSON 序列化（推荐）

`pdcSet` / `pdcGet` **自动 JSON 序列化/反序列化**——任意可 JSON 序列化的值直接存取，无需手写 `JSON.stringify` / `JSON.parse`：

```js
// 写入对象/数字/布尔等任意值
await pdcSet(uuid, 'lastLogin', { ts: Date.now(), ip: '1.2.3.4' });
await pdcSet(uuid, 'kills', 42);

// 读取自动反序列化（泛型）
const lastLogin = await pdcGet(uuid, 'lastLogin');   // { ts, ip } | null
const kills = await pdcGet(uuid, 'kills');           // 42
```

- 无值时 `get` 返回 `null`
- 旧数据（历史版本写入的非 JSON 字符串）`get` 时**原样返回字符串**，不会报错
- 底层字符串读写用 `pdcGetRaw` / `pdcSetRaw`（跨语言/版本数据交换场景）

## Player / Entity / World

```js
// 写入（任意可序列化值）
await pdcSet(uuid, 'myplugin.key', { score: 10 });

// 读取（自动反序列化）
const val = await pdcGet(uuid, 'myplugin.key');  // unknown | null

// 检查是否存在
const exists = await pdcHas(uuid, 'myplugin.key');  // boolean

// 获取所有 key（完整 key 格式，含命名空间）
const keys = await pdcKeys(uuid);  // string[]

// 全量读取本插件命名空间的键值（每个值自动反序列化）
const all = await pdcGetAll(uuid);  // Record<string, unknown>

// 删除
await pdcRemove(uuid, 'myplugin.key');
```

`uuid` 为 Player 或 Entity 的 UUID。World 的 PDC 通过同一套函数，运行时自动按 UUID → Player → Entity → World 顺序查找持有者。

## Player / Block 实例方法

`Player` 与 `Block`（需含 location，即 `world.getBlock` 返回的实例）提供 PDC 语法糖：

```js
// Player
await player.setPdc('kills', 42);
const kills = await player.getPdc('kills');   // 42
await player.hasPdc('kills');                 // true
await player.removePdc('kills');
await player.keysPdc();                       // string[]
await player.getAllPdc();                     // Record<string, unknown>

// Block（内部以世界坐标寻址；无 location 的 Block 抛错）
const block = await world.getBlock(0, 65, 0);
await block.setPdc('owner', 'Notch');
const owner = await block.getPdc('owner');
```

## Block

方块 PDC 通过坐标寻址（与实体同套 JSON 语义）：

```js
const val = await pdcGetBlock('world', 0, 64, 0, 'myplugin.key');
await pdcSetBlock('world', 0, 64, 0, 'myplugin.key', { data: 1 });
await pdcHasBlock('world', 0, 64, 0, 'myplugin.key');
await pdcRemoveBlock('world', 0, 64, 0, 'myplugin.key');
await pdcKeysBlock('world', 0, 64, 0);
await pdcGetAllBlock('world', 0, 64, 0);
```

## Key 格式

支持 `namespace:key` 格式（如 `myplugin:config`）；**纯字符串（无冒号）默认使用插件命名空间**——不同插件的裸 key（如 `score`）互不冲突：

```js
await pdcSet(uuid, 'score', 1);         // 存为 <插件名>:score（如 myplugin:score）
await pdcSet(uuid, 'minecraft:level', 2);  // 显式命名空间
```

> **注意：** key 中的大写字母运行时自动转为小写（如 `MyPlugin.deathLoc` → `myplugin.deathloc`）。允许字符：`a-z` `0-9` `/` `.` `_` `-`。**命名空间由 `yeow` 改为插件名**（2026-08-13）：历史数据若存于 `yeow:` 命名空间下，需用 `yeow:key` 显式访问迁移。

## 示例

```js
// 保存玩家自定义状态（任意对象）
await player.setPdc('lastLogin', { ts: Date.now(), ip: '1.2.3.4' });

const lastLogin = await player.getPdc('lastLogin');
if (lastLogin) {
    player.sendMessage(`Last login: ${new Date(lastLogin.ts)}`);
}
```
