# Scoreboard Tasks

Scoreboard Objective, Score, Team operations. Supports the main scoreboard and custom scoreboards.

---

## Board Management

### `scoreboard.createBoard`

Create a new scoreboard instance.

- **Request**: `{ "id": "<handle>" }`
- **Returns**: `string` (id)

### `scoreboard.deleteBoard`

Destroy a scoreboard instance (does not affect players currently viewing it — they will continue to see the previous content).

- **Request**: `{ "id": "<handle>" }`
- **Returns**: `true`

### Board Addressing

All Objective/Score/Team operations below accept an optional `board` field. If `board` exists and is a valid custom scoreboard ID, the operation executes on that scoreboard; otherwise it executes on the main scoreboard.

```json
{ "name": "kills", "criteria": "dummy", "displayName": "Kills", "board": "myBoard" }
```

---

## Objective

### Create/Delete

| Task | Request | Returns |
|------|---------|---------|
| `scoreboard.createObjective` | `{ "name": "<name>", "criteria": "<criteria>", "displayName": "<text>" }` | `{ "name": "<name>", "criteria": "<criteria>", "displayName": "<text>" }` |
| `scoreboard.deleteObjective` | `{ "name": "<name>" }` | `true` |

`criteria` common values: `dummy` (manual scoring), `deathCount`, `playerKillCount`, `totalKillCount`, `health`.

### Query and Display

| Task | Request | Returns |
|------|---------|---------|
| `scoreboard.getObjectives` | `{}` | `[{ "name": "<name>", "criteria": "<criteria>", "displaySlot": "<slot>" \| null }, ...]` |
| `scoreboard.setObjectiveDisplay` | `{ "name": "<name>", "slot": "<slot>" \| null }` | `true` |

`slot`: `"BELOW_NAME"`, `"PLAYER_LIST"`, `"SIDEBAR"`. `null` clears the display.

### Score Operations

| Task | Request | Returns |
|------|---------|---------|
| `scoreboard.getScore` | `{ "objective": "<name>", "entry": "<entry>" }` | `number` \| `null` |
| `scoreboard.setScore` | `{ "objective": "<name>", "entry": "<entry>", "value": <int> }` | `true` |
| `scoreboard.resetScore` | `{ "objective": "<name>", "entry": "<entry>" }` | `true` |

`entry` is any string (typically a player name or custom identifier).

---

## Team

### Create/Delete/Query

| Task | Request | Returns |
|------|---------|---------|
| `scoreboard.createTeam` | `{ "name": "<name>" }` | `{ "name": "<name>" }` |
| `scoreboard.deleteTeam` | `{ "name": "<name>" }` | `true` |
| `scoreboard.getTeam` | `{ "name": "<name>" }` | `TeamInfo` \| `null` |
| `scoreboard.getTeams` | `{}` | `[TeamInfo, ...]` |

`TeamInfo` return format:

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

### Property Modifications

| Task | Request | Returns |
|------|---------|---------|
| `scoreboard.setTeamDisplayName` | `{ "name": "<name>", "displayName": "<text>" }` | `true` |
| `scoreboard.setTeamPrefix` | `{ "name": "<name>", "prefix": "<text>" }` | `true` |
| `scoreboard.setTeamSuffix` | `{ "name": "<name>", "suffix": "<text>" }` | `true` |
| `scoreboard.setTeamColor` | `{ "name": "<name>", "color": "<ChatColor>" }` | `true` |
| `scoreboard.setTeamFriendlyFire` | `{ "name": "<name>", "allow": <bool> }` | `true` |
| `scoreboard.setTeamSeeInvisible` | `{ "name": "<name>", "canSee": <bool> }` | `true` |
| `scoreboard.setTeamOption` | `{ "name": "<name>", "option": "<option>", "value": "<value>" }` | `true` |

`option` values: `"NAME_TAG_VISIBILITY"`, `"DEATH_MESSAGE_VISIBILITY"`, `"COLLISION_RULE"`.

`value` values: `"ALWAYS"`, `"NEVER"`, `"FOR_OTHER_TEAMS"`, `"FOR_OWN_TEAM"` (the three options share the same enum; collisionRule push semantics correspond to `FOR_OTHER_TEAMS` / `FOR_OWN_TEAM`). See the [Value Domain Appendix](../values.md) for full details.

### Member Management

| Task | Request | Returns |
|------|---------|---------|
| `scoreboard.teamAddEntry` | `{ "name": "<name>", "entry": "<entry>" }` | `true` |
| `scoreboard.teamRemoveEntry` | `{ "name": "<name>", "entry": "<entry>" }` | `true` |
| `scoreboard.teamGetEntries` | `{ "name": "<name>" }` | `string[]` |

---

## Player Binding

### `scoreboard.setPlayerBoard`

- **Request**: `{ "uuid": "<uuid>", "board": "<handle>" }`
- **Returns**: `true` \| `false`

Set a player's scoreboard to a specified custom scoreboard. If `board` is not passed, sets to the main scoreboard.

> For the complete list of value domains (displaySlot, team colors, teamOption/optionStatus), see the [Value Domain Appendix](../values.md).
