# Event Specification

## Overview

The Yeow event system is the mechanism by which the runtime delivers game events to plugins. Plugins subscribe to events via `event.subscribe`, and the runtime delivers event data through the `cb` channel.

---

## Registration and Unregistration

Plugins manage event subscriptions through the `event.subscribe` and `event.unsubscribe` methods on the task channel. See the [Event System task specification](../task/event-system.md) for details.

## Field Adaptation Rules

In event data:

- The `player` field holds the player's UUID string; the caller can look up the Player by UUID
- The `from`, `to`, and `respawnLocation` fields hold location objects of the form `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }`
- Events use the `_cancellable` field to indicate whether they can be cancelled

---

## Event Name Table

All event names use `camelCase` format. Complete list:

| Event name                | Category  | Cancellable | Description              |
| ------------------------- | --------- | ----------- | ------------------------ |
| `playerJoin`              | Player    | No          | Player joined           |
| `playerQuit`              | Player    | No          | Player quit             |
| `playerChat`              | Player    | Yes         | Player chatted          |
| `playerMove`              | Player    | Yes         | Player moved            |
| `playerInteract`          | Player    | Yes         | Player interacted       |
| `playerCommand`           | Player    | Yes         | Player ran a command    |
| `playerDeath`             | Player    | No          | Player died             |
| `playerRespawn`           | Player    | No          | Player respawned        |
| `playerTeleport`          | Player    | Yes         | Player teleported       |
| `playerItemConsume`       | Player    | Yes         | Player consumed an item |
| `playerDropItem`          | Player    | Yes         | Player dropped an item  |
| `playerPickupItem`        | Player    | Yes         | Player picked up an item|
| `playerBucketFill`        | Player    | Yes         | Player filled a bucket with liquid |
| `playerBucketEmpty`       | Player    | Yes         | Player emptied a bucket  |
| `playerExpChange`         | Player    | No          | Player experience changed |
| `playerLevelChange`       | Player    | No          | Player level changed    |
| `playerGameModeChange`    | Player    | No          | Player game mode changed|
| `playerAdvancementDone`   | Player    | No          | Player completed an advancement |
| `playerToggleSneak`       | Player    | No          | Player toggled sneaking |
| `playerToggleFlight`      | Player    | No          | Player toggled flying   |
| `foodLevelChange`         | Player    | Yes         | Player hunger changed   |
| `entityDamage`            | Entity    | Yes         | Entity damaged          |
| `entityDeath`             | Entity    | No          | Entity died             |
| `entitySpawn`             | Entity    | No          | Entity spawned          |
| `entityExplode`           | Entity    | Yes         | Entity exploded         |
| `entityRegainHealth`      | Entity    | No          | Entity regained health  |
| `entityTarget`            | Entity    | No          | Entity changed target   |
| `projectileLaunch`        | Entity    | No          | Projectile launched     |
| `projectileHit`           | Entity    | No          | Projectile hit          |
| `blockBreak`              | Block     | Yes         | Block broken            |
| `blockPlace`              | Block     | Yes         | Block placed            |
| `blockFade`               | Block     | Yes         | Block faded             |
| `blockGrow`               | Block     | No          | Block grew              |
| `blockSpread`             | Block     | No          | Block spread            |
| `blockExplode`            | Block     | Yes         | Block exploded          |
| `inventoryOpen`           | Inventory | Yes         | Inventory opened        |
| `inventoryClose`          | Inventory | No          | Inventory closed        |
| `inventoryClick`          | Inventory | Yes         | Inventory clicked       |
| `serverPing`              | Server    | No          | Server pinged           |
| `serverCommand`           | Server    | Yes         | Console command         |
| `playerResourcePackStatus`| Resource Pack | No      | Resource pack status changed |
| `permissionCheck`         | Permission| No          | Yeow ecosystem permission check (not a Paper-based event; see below) |

## `permissionCheck` (Yeow ecosystem permission check)

**Yeow plugins** intercept permission checks by returning `{ "allowed": <bool> }` from the handler; **not returning a value is treated as unhandled**. When multiple handlers return conflicting results, the **last one returned wins** (execution order is not guaranteed).

- **Trigger scope (Yeow ecosystem only)**:
  - The `player.hasPermission` task
  - **Execution-time checks** for commands registered by Yeow plugins
  - **`hasPermission` / command execution from other Java plugins does not trigger this** — this check is not bound to other ecosystems
- **Priority**: when `permissionCheck` produces a result, it **overrides the Paper-based `hasPermission`**; when unhandled, it falls back to the Paper-based one
- **Node merge**: permission nodes are still registered into the Paper permission system at the same time (so traditional Java plugins / permission plugins can manage them); Yeow's check just has higher priority

| Field        | Type   | Description |
|--------------|--------|-------------|
| `target`     | string | The check target: a player UUID or `"CONSOLE"` |
| `node`       | string | The permission node (e.g. `myplugin.home`) |
| `permission` | object | The permission object: `{ "node": "<node>", "default": "all" \| "op" \| "none" }` (`default` is the registered default for that node, omitted when not registered) |

Example:

```json
{ "t": "cb", "p": "<cbId>", "r": { "target": "<uuid>", "node": "myplugin.home", "permission": { "node": "myplugin.home", "default": "all" } } }
// handler returns: { "allowed": true } or { "allowed": false }; or returns nothing (unhandled)
```

---

## Module Documentation

| Module                                   | Description                                   |
| ---------------------------------------- | --------------------------------------------- |
| [player-events](player-events.md)        | 21 player events                             |
| [entity-events](entity-events.md)        | 6 entity events + 2 projectile events        |
| [block-events](block-events.md)          | 6 block events                               |
| [inventory-events](inventory-events.md)  | 3 inventory events                           |
| [server-events](server-events.md)        | 3 server events                              |
