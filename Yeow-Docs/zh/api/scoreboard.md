# Scoreboard API

计分板（OOP 对象式 API）：`Scoreboard` / `Objective` / `Team` 对象，操作都在对象上调用。

```js
import { Scoreboard } from 'yeow-api';
```

> [!WARNING]
> **Folia 平台限制**：Folia 的 Scoreboard 实现**不支持创建**计分板对象——
> `board.createObjective(...)` / `board.createTeam(...)` 在 Folia 上 reject
> （`Folia does not support creating new objectives/teams`）。
> 原因：Folia（1.21）的 `registerNewObjective` / `registerNewTeam` 全部重载直接抛
> `UnsupportedOperationException`，仅实现了**读取与修改已存在对象**的能力
> （`setScore`、`team.setPrefix`、`obj.setDisplay` 等修改操作可用）。
> **Paper 上无此限制。**

## 获取计分板

```js
const board = await Scoreboard.create('myBoard');   // 自定义计分板
const main = Scoreboard.main();                     // 主计分板（服务器默认）
```

## Objective（计分项）

```js
// 创建计分项（criteria: dummy(手动), deathCount, playerKillCount, totalKillCount, health）
const obj = await board.createObjective('kills', 'dummy', '<red>Kills</red>');

// 设置显示位置（slot: "BELOW_NAME" | "PLAYER_LIST" | "SIDEBAR" | null(取消)）
await obj.setDisplay('SIDEBAR');

// 分数（target 接受 Player 对象或 entry 字符串）
await obj.setScore(player, 42);
await obj.setScore('Notch', 42);
const score = await obj.getScore(player);    // number | null
await obj.resetScore(player);

// 查询全部计分项 / 删除
const objs = await board.getObjectives();    // Objective[]
await obj.delete();
```

## Team（队伍）

```js
const team = await board.createTeam('red_team');
await team.setPrefix('<red>[Red]</red>');
await team.setSuffix('<gray> [PvP]</gray>');
await team.setColor('RED');
await team.setFriendlyFire(false);
await team.setSeeInvisible(false);
await team.setOption('COLLISION_RULE', 'PUSH_OWN_TEAM');

// 成员管理（接受 Player 对象或其名/entry 字符串）
await team.add(player);
await team.add('Notch');
await team.remove(player);
const members = await team.getEntries();    // string[]（快照）

// 查询 / 删除
const t = await board.getTeam('red_team');  // Team | null
const teams = await board.getTeams();       // Team[]
await team.delete();
```

> 计分板取值域（展示槽 DisplaySlot、队伍选项 TeamOption、队伍颜色等）见 [值域附录 · 直接维护的枚举清单](../specifications/values.md#二直接维护的枚举清单)。

## 玩家个人计分板

```js
await board.attach(player);        // 为玩家设置个人计分板为本计分板（接受 Player 对象或 uuid）
await Scoreboard.main().attach(player);   // 重置为主计分板
```

## 示例

```js
const board = Scoreboard.main();
const obj = await board.createObjective('money', 'dummy', '<gold>Money</gold>');
await obj.setDisplay('SIDEBAR');
await obj.setScore(player, 100);

const team = await board.createTeam('staff');
await team.setPrefix('<dark_red>[Staff]</dark_red>');
await team.setColor('DARK_RED');
await team.add(player);
await board.attach(player);
```
