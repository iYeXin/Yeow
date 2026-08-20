# Scoreboard 任务

计分板 Objective、Score、Team 操作。支持主计分板和自定义计分板。

---

## Board 管理

### `scoreboard.createBoard`

创建新的计分板实例。

- **请求**：`{ "id": "<handle>" }`
- **返回**：`string` (id)

### `scoreboard.deleteBoard`

销毁计分板实例（不影响正在查看该计分板的玩家，他们将继续看到之前的内容）。

- **请求**：`{ "id": "<handle>" }`
- **返回**：`true`

### Board 寻址

以下所有 Objective/Score/Team 操作均接受可选 `board` 字段。若 `board` 存在且为有效的自定义计分板 ID，操作在该计分板上执行；否则在主计分板上执行。

```json
{ "name": "kills", "criteria": "dummy", "displayName": "Kills", "board": "myBoard" }
```

---

## Objective（计分项）

### 创建/删除

| 任务 | 请求 | 返回 |
|------|------|------|
| `scoreboard.createObjective` | `{ "name": "<name>", "criteria": "<criteria>", "displayName": "<text>" }` | `{ "name": "<name>", "criteria": "<criteria>", "displayName": "<text>" }` |
| `scoreboard.deleteObjective` | `{ "name": "<name>" }` | `true` |

`criteria` 常用值：`dummy`（手动计分）、`deathCount`、`playerKillCount`、`totalKillCount`、`health`。

### 查询与显示

| 任务 | 请求 | 返回 |
|------|------|------|
| `scoreboard.getObjectives` | `{}` | `[{ "name": "<name>", "criteria": "<criteria>", "displaySlot": "<slot>" \| null }, ...]` |
| `scoreboard.setObjectiveDisplay` | `{ "name": "<name>", "slot": "<slot>" \| null }` | `true` |

`slot`：`"BELOW_NAME"`、`"PLAYER_LIST"`、`"SIDEBAR"`。`null` 表示清除显示。

### Score 操作

| 任务 | 请求 | 返回 |
|------|------|------|
| `scoreboard.getScore` | `{ "objective": "<name>", "entry": "<entry>" }` | `number` \| `null` |
| `scoreboard.setScore` | `{ "objective": "<name>", "entry": "<entry>", "value": <int> }` | `true` |
| `scoreboard.resetScore` | `{ "objective": "<name>", "entry": "<entry>" }` | `true` |

`entry` 为任意字符串（通常是玩家名或自定义标识）。

---

## Team

### 创建/删除/查询

| 任务 | 请求 | 返回 |
|------|------|------|
| `scoreboard.createTeam` | `{ "name": "<name>" }` | `{ "name": "<name>" }` |
| `scoreboard.deleteTeam` | `{ "name": "<name>" }` | `true` |
| `scoreboard.getTeam` | `{ "name": "<name>" }` | `TeamInfo` \| `null` |
| `scoreboard.getTeams` | `{}` | `[TeamInfo, ...]` |

`TeamInfo` 返回格式：

```json
{
  "name": "<name>",
  "displayName": "<text>",
  "prefix": "<text>",
  "suffix": "<text>",
  "color": "<ChatColor>",
  "allowFriendlyFire": true,
  "canSeeFriendlyInvisibles": false,
  "entries": ["<player1>", "<player2>"],
  "options": {
    "nameTagVisibility": "ALWAYS",
    "deathMessageVisibility": "ALWAYS",
    "collisionRule": "ALWAYS"
  }
}
```

### 属性修改

| 任务 | 请求 | 返回 |
|------|------|------|
| `scoreboard.setTeamDisplayName` | `{ "name": "<name>", "displayName": "<text>" }` | `true` |
| `scoreboard.setTeamPrefix` | `{ "name": "<name>", "prefix": "<text>" }` | `true` |
| `scoreboard.setTeamSuffix` | `{ "name": "<name>", "suffix": "<text>" }` | `true` |
| `scoreboard.setTeamColor` | `{ "name": "<name>", "color": "<ChatColor>" }` | `true` |
| `scoreboard.setTeamFriendlyFire` | `{ "name": "<name>", "allow": <bool> }` | `true` |
| `scoreboard.setTeamSeeInvisible` | `{ "name": "<name>", "canSee": <bool> }` | `true` |
| `scoreboard.setTeamOption` | `{ "name": "<name>", "option": "<option>", "value": "<value>" }` | `true` |

`option` 值：`"NAME_TAG_VISIBILITY"`、`"DEATH_MESSAGE_VISIBILITY"`、`"COLLISION_RULE"`。

`value` 值：`"ALWAYS"`、`"NEVER"`、`"FOR_OTHER_TEAMS"`、`"FOR_OWN_TEAM"`（三个 option 共用同一枚举；collisionRule 的推挤语义对应 `FOR_OTHER_TEAMS` / `FOR_OWN_TEAM`）。完整说明见 [值域附录](../values.md)。

### 成员管理

| 任务 | 请求 | 返回 |
|------|------|------|
| `scoreboard.teamAddEntry` | `{ "name": "<name>", "entry": "<entry>" }` | `true` |
| `scoreboard.teamRemoveEntry` | `{ "name": "<name>", "entry": "<entry>" }` | `true` |
| `scoreboard.teamGetEntries` | `{ "name": "<name>" }` | `string[]` |

---

## Player 绑定

### `scoreboard.setPlayerBoard`

- **请求**：`{ "uuid": "<uuid>", "board": "<handle>" }`
- **返回**：`true` \| `false`

将玩家的计分板设为指定的自定义计分板。若不传 `board`，设为主计分板。

> 涉及值域（displaySlot、队伍颜色、teamOption/optionStatus）的完整清单见 [值域附录](../values.md)。
