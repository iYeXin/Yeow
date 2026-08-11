# PDC API

Persistent Data Container — 持久化自定义键值数据。支持 Player、Entity、World、Block 四种持有者。

```js
import { pdcGet, pdcSet, pdcHas, pdcRemove, pdcKeys,
    pdcGetBlock, pdcSetBlock, pdcHasBlock, pdcRemoveBlock } from 'yeow-api';
```

## Player / Entity / World

```js
// 写入（值均为 string）
await pdcSet(uuid, 'myplugin.key', 'hello');

// 读取
const val = await pdcGet(uuid, 'myplugin.key');  // string | null

// 检查是否存在
const exists = await pdcHas(uuid, 'myplugin.key');  // boolean

// 获取所有 key
const keys = await pdcKeys(uuid);  // string[]

// 删除
await pdcRemove(uuid, 'myplugin.key');
```

`uuid` 为 Player 或 Entity 的 UUID。World 的 PDC 通过同一套函数，运行时自动按 UUID → Player → Entity → World 顺序查找持有者。

## Block

方块 PDC 通过坐标寻址：

```js
const val = await pdcGetBlock('world', 0, 64, 0, 'myplugin.key');
await pdcSetBlock('world', 0, 64, 0, 'myplugin.key', 'data');
await pdcHasBlock('world', 0, 64, 0, 'myplugin.key');
await pdcRemoveBlock('world', 0, 64, 0, 'myplugin.key');
```

## Key 格式

支持 `namespace:key` 格式（如 `myplugin:config`），也支持纯字符串（默认命名空间 `yeow`）。

> **注意：** 由于 Paper 系平台上，key 中的大写字母将在运行时自动转为小写。例如 `myPlugin.deathLoc` 自动转为 `myplugin.deathloc`。允许字符：`a-z` `0-9` `/` `.` `_` `-`。

## 示例

```js
// 保存玩家自定义状态
await pdcSet(player.uuid, 'myplugin.lastLogin', Date.now().toString());

const lastLogin = await pdcGet(player.uuid, 'myplugin.lastLogin');
if (lastLogin) {
    player.sendMessage(`Last login: ${new Date(parseInt(lastLogin))}`);
}
```
