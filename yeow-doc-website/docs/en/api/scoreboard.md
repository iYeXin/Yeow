# Scoreboard API

Scoreboard (an OOP, object-based API): `Scoreboard` / `Objective` / `Team` objects, with all operations invoked on the objects.

```js
import { Scoreboard } from 'yeow-api';
```

> [!WARNING]
> **Folia platform limitation**: Folia's Scoreboard implementation does **not support creating** scoreboard objects —
> `board.createObjective(...)` / `board.createTeam(...)` reject on Folia
> (`Folia does not support creating new objectives/teams`).
> Reason: all overloads of `registerNewObjective` / `registerNewTeam` in Folia (1.21) directly throw
> `UnsupportedOperationException`; only the ability to **read and modify existing objects** is implemented
> (modifying operations such as `setScore`, `team.setPrefix`, `obj.setDisplay` are available).
> **There is no such limitation on Paper.**

## Getting a scoreboard

```js
const board = await Scoreboard.create('myBoard');   // custom scoreboard
const main = Scoreboard.main();                     // the main scoreboard (server default)
```

## Objective

```js
// Create an objective (criteria: dummy(manual), deathCount, playerKillCount, totalKillCount, health)
const obj = await board.createObjective('kills', 'dummy', '<red>Kills</red>');

// Set the display position (slot: "BELOW_NAME" | "PLAYER_LIST" | "SIDEBAR" | null(cancel))
await obj.setDisplay('SIDEBAR');

// Scores (target accepts a Player object or an entry string)
await obj.setScore(player, 42);
await obj.setScore('Notch', 42);
const score = await obj.getScore(player);    // number | null
await obj.resetScore(player);

// Query all objectives / delete
const objs = await board.getObjectives();    // Objective[]
await obj.delete();
```

## Team

```js
const team = await board.createTeam('red_team');
await team.setPrefix('<red>[Red]</red>');
await team.setSuffix('<gray> [PvP]</gray>');
await team.setColor('RED');
await team.setFriendlyFire(false);
await team.setSeeInvisible(false);
await team.setOption('COLLISION_RULE', 'PUSH_OWN_TEAM');

// Member management (accepts a Player object or its name/entry string)
await team.add(player);
await team.add('Notch');
await team.remove(player);
const members = await team.getEntries();    // string[] (snapshot)

// Query / delete
const t = await board.getTeam('red_team');  // Team | null
const teams = await board.getTeams();       // Team[]
await team.delete();
```

> Scoreboard value domains (DisplaySlot, TeamOption, team colors, etc.) are described in the [Values Appendix · Directly Maintained Enum List](../specifications/values.md#二直接维护的枚举清单).

## Player individual scoreboards

```js
await board.attach(player);        // set the player's personal scoreboard to this one (accepts a Player object or uuid)
await Scoreboard.main().attach(player);   // reset to the main scoreboard
```

## Example

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
