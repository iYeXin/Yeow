# Advancement API

进度（成就）授予、撤销和查询。

```js
import { grantAdvancement, revokeAdvancement, getAdvancementProgress,
    awardCriteria, revokeCriteria } from 'yeow-api';
```

## 方法

```js
// 授予进度所有条件
await grantAdvancement(uuid, 'minecraft:story/mine_stone');

// 撤销进度
await revokeAdvancement(uuid, 'minecraft:story/mine_stone');

// 授予/撤销特定条件
await awardCriteria(uuid, 'minecraft:story/root', 'crafting_table');
await revokeCriteria(uuid, 'minecraft:story/root', 'crafting_table');

// 查询进度
const prog = await getAdvancementProgress(uuid, 'minecraft:story/root');
// → { awardedCriteria: ["crafting_table"], remainingCriteria: [] }
```

## Key 格式

进度使用命名空间 key：`minecraft:story/root`、`minecraft:nether/root` 等。

## 示例

```js
const player = await Player.get('Notch');

// 授予"石器时代"进度
await grantAdvancement(player.uuid, 'minecraft:story/mine_stone');

// 检查完成情况
const p = await getAdvancementProgress(player.uuid, 'minecraft:story/root');
if (p && p.remainingCriteria.length === 0) {
    console.log('Root advancement complete!');
}
```
