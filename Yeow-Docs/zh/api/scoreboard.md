# Scoreboard API

计分板 Objective、Score、Team 操作。

```js
import {
    createScoreboard, deleteScoreboard,
    createObjective, deleteObjective, getObjectives,
    setObjectiveDisplay, getScore, setScore, resetScore,
    createTeam, deleteTeam, getTeam, getTeams,
    setTeamDisplayName, setTeamPrefix, setTeamSuffix, setTeamColor,
    setTeamFriendlyFire, setTeamSeeInvisible, setTeamOption,
    teamAddEntry, teamRemoveEntry, teamGetEntries,
    setPlayerBoard,
} from 'yeow-api';
```

## 个人计分板

默认所有操作作用于主计分板。传入 `board` 参数可操作自定义计分板：

```js
const myBoard = await createScoreboard('myBoard');

// 在自定义计分板上创建计分项
await createObjective('kills', 'dummy', '<red>Kills</red>', myBoard);
await setScore('kills', 'Notch', 42, myBoard);

// 为玩家设置个人计分板
await setPlayerBoard(player.uuid, myBoard);

// 销毁
await deleteScoreboard(myBoard);
```

所有 Objective、Score、Team 方法均支持可选的最后一个参数 `board`。

## Objective

```js
// 创建计分项
await createObjective('kills', 'dummy', '<red>Kills</red>');
// criteria 常用值: dummy(手动), deathCount, playerKillCount, totalKillCount, health

// 设置显示位置
await setObjectiveDisplay('kills', 'SIDEBAR');
// slot: "BELOW_NAME" | "PLAYER_LIST" | "SIDEBAR" | null(取消)

// 查询所有计分项
const objs = await getObjectives();
// → [{ name:"kills", criteria:"dummy", displaySlot:"SIDEBAR" }, ...]

// 删除
await deleteObjective('kills');
```

## Score

```js
// 设置分数
await setScore('kills', 'Notch', 42);

// 查询分数
const score = await getScore('kills', 'Notch');  // number | null

// 重置分数
await resetScore('kills', 'Notch');
```

## Team

```js
// 创建队伍
await createTeam('red_team');
await setTeamPrefix('red_team', '<red>[Red]</red>');
await setTeamSuffix('red_team', '<gray> [PvP]</gray>');
await setTeamColor('red_team', 'RED');
await setTeamFriendlyFire('red_team', false);
await setTeamOption('red_team', 'COLLISION_RULE', 'PUSH_OWN_TEAM');

// 成员管理
await teamAddEntry('red_team', 'Notch');
await teamRemoveEntry('red_team', 'Notch');
const members = await teamGetEntries('red_team');  // string[]

// 查询
const team = await getTeam('red_team');  // TeamInfo | null
const teams = await getTeams();          // TeamInfo[]

await deleteTeam('red_team');
```

## Player 绑定

```js
await setPlayerBoard(player.uuid);
```

## 示例

```js
await createObjective('money', 'dummy', '<gold>Money</gold>');
await setObjectiveDisplay('money', 'SIDEBAR');

await createTeam('staff');
await setTeamPrefix('staff', '<dark_red>[Staff]</dark_red>');
await setTeamColor('staff', 'DARK_RED');
await teamAddEntry('staff', player.name);
await setPlayerBoard(player.uuid);
```
