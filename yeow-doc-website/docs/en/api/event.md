# Event API

```js
import { eventOn, eventOff } from 'yeow-api';
```

## eventOn(eventType, handler)

| Parameter               | Type                     | Description                    |
| ----------------------- | ------------------------ | ------------------------------ |
| `eventType`             | `string`                 | Event type name                |
| `handler`               | `(e: EventType) => void` | Event handler                  |
| `options.manualRelease` | `boolean`                | Manual control of event end (see details below) |

Returns an unsubscribe function: `() => void`.

> **Same event can register multiple handlers, all effective** (called serially in registration order; delivered concurrently in concurrent mode). `eventOff` removes corresponding callback by handler reference; only stops subscribing to that event after all are removed.

```js
const unsubscribe = eventOn('playerJoin', (e) => { ... });
unsubscribe();  // Unsubscribe

// Auto mode (default): Synchronous return immediately ends, async function returns Promise immediately ends, doesn't wait
eventOn('blockBreak', (e) => { e.cancelled = true; });
eventOn('blockBreak', async (e) => {
    e.cancelled = true;  // ✅ Synchronous segment effective
    await something();    // Event already ended before this, setting value after is invalid
});
```

## eventOn(eventType, options, handler)

Manual end mode:

```js
eventOn('blockBreak', { manualRelease: true }, (e, complete) => {
    fetchPermission(result => {
        e.cancelled = !result.allowed;
        complete({ cancelled: e.cancelled });  // Manually end event
    });
});
```

## eventOff(eventType, handler)

Unsubscribe. Needs to pass same handler reference as `eventOn`.

```js
const handler = (e) => { ... };
eventOn('playerJoin', handler);
eventOff('playerJoin', handler);
```

## Event Parameters

In TS mode there are complete type definitions:

```ts
import type { PlayerJoinEvent, BlockBreakEvent } from 'yeow-api';

eventOn('playerJoin', (e: PlayerJoinEvent) => {
    console.log(e.player.name);   // Player object
    console.log(e.joinMessage);
});
```

In JS mode event object field names match type table, `player` field automatically converts to `Player` object.

## Event List

> Event field value domains (interaction/click action `action`, damage/teleport cause `cause`, container type `inventoryType` etc.) see [Value Domain Appendix](../specifications/values.md) (action/container types see "Directly Maintained Enumeration List", damage/health regen causes see "Reference Implementation").

### Player Events

| Type                    | Fields                                                      | Cancellable |
| ----------------------- | ----------------------------------------------------------- | :---------: |
| `playerJoin`            | player, joinMessage                                         |             |
| `playerQuit`            | player, quitMessage                                         |             |
| `playerChat`            | player, message, format                                     |      ✔      |
| `playerMove`            | player, from, to                                            |      ✔      |
| `playerInteract`        | player, action, material, block                             |      ✔      |
| `playerCommand`         | player, message                                             |      ✔      |
| `playerDeath`           | player, deathMessage(Message), deathType                    |      ✔      |
| `playerRespawn`         | player, respawnLocation                                     |             |
| `playerTeleport`        | player, from, to, cause                                     |      ✔      |
| `playerItemConsume`     | player, item(ItemStack)                                    |             |
| `playerDropItem`        | player, item(ItemStack)                                    |      ✔      |
| `playerPickupItem`      | player, item(ItemStack)                                    |      ✔      |
| `playerBucketFill`      | player, bucket                                              |      ✔      |
| `playerBucketEmpty`     | player, bucket                                              |      ✔      |
| `playerExpChange`       | player, amount                                              |             |
| `playerLevelChange`     | player, oldLevel, newLevel                                  |             |
| `playerGameModeChange`  | player, newGameMode                                         |      ✔      |
| `playerAdvancementDone` | player, advancement, title(Message)?, description(Message)? |             |
| `playerToggleSneak`     | player, sneaking                                            |             |
| `playerToggleFlight`    | player, flying                                              |             |
| `foodLevelChange`       | player, oldFoodLevel, newFoodLevel                          |      ✔      |

### Entity Events

| Type                 | Fields                                        | Cancellable |
| -------------------- | --------------------------------------------- | :---------: |
| `entityDamage`       | entity, damage, cause, entityType             |      ✔      |
| `entityDeath`        | entity, entityType, entityName                |             |
| `entitySpawn`        | entity, entityType, location(Location)        |      ✔      |
| `entityExplode`      | entity, entityType, location(Location), blockCount |      ✔      |
| `entityRegainHealth` | entity, amount, reason                        |             |
| `entityTarget`       | entity, target                                |      ✔      |
| `projectileLaunch`   | entity, projectileType, shooter?              |      ✔      |
| `projectileHit`      | entity, projectileType, hitEntity?, hitBlock? |             |

### World Events

| Type           | Fields                               | Cancellable |
| -------------- | ------------------------------------ | :---------: |
| `blockBreak`   | player, block, location(Location)    |      ✔      |
| `blockPlace`   | player, block, blockAgainst, location(Location) |      ✔      |
| `blockFade`    | block, location(Location)            |             |
| `blockGrow`    | block, location(Location)            |             |
| `blockSpread`  | block, location(Location)            |             |
| `blockExplode` | block, location(Location)            |      ✔      |

### Inventory Events

| Type             | Fields                                                                                                                                 | Cancellable |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------- | :---------: |
| `inventoryOpen`  | player, inventoryType, title                                                                                                           |             |
| `inventoryClose` | player, inventoryType, **inventoryId?**                                                                                                |             |
| `inventoryClick` | player, slot, hotbarKey, action, inventoryType, isLeftClick, isRightClick, isShiftClick, clickedItem, cursorItem, **inventoryId?** |      ✔      |

> **inventoryId**: When event occurs on Yeow custom Inventory (`Inventory.create` created), carries that Inventory's handle id (`inventory.toString()`) — in multi custom Inventory scenarios use `e.inventoryId === inventory.toString()` to identify click/close attribution. When not custom Inventory (inventory, chest etc.) it's absent.

### Server Events

| Type                       | Fields                                | Cancellable |
| -------------------------- | ------------------------------------- | :---------: |
| `serverPing`               | address, numPlayers, maxPlayers, motd |      ✔      |
| `serverCommand`            | command, sender                       |      ✔      |
| `playerResourcePackStatus` | player, status, hash                  |             |

### Permission Check (Yeow Specification)

| Type              | Fields       | Cancellable |
| ----------------- | ------------ | :---------: |
| `permissionCheck` | target, node |             |

`permissionCheck` is for **Yeow ecosystem** permission interception (only triggered by `player.hasPermission` task and Yeow command execution check; other Java plugins' permission checks don't pass through). Handler returns `{ allowed }` to determine result (overrides Paper system); not returning means untreated; multiple handler return conflicts take last returned. Event contains `permission` object (`{ node, default }`). **⚠ Regular plugins not recommended to listen (performance); calling `hasPermission` in handler causes infinite loop**. See [Permission](permission.md) for details:

```js
eventOn('permissionCheck', (e) => {
    const { target, node, permission } = e;
    if (node === 'myplugin.home' && isVip(target)) {
        return { allowed: true };   // Override Paper system result
    }
    // Not returning → Falls back to Paper system hasPermission
});
```

> `player` field on JS side automatically converts to `new Player(uuid)` (direct construction, zero roundtrip; `name` lazily obtained and cached on first access). `block` is namespace ID (e.g., `minecraft:stone`).

## Event Writeback

Event results write back through **three methods**. Writeback only effective in handler's **synchronous segment** — returning Promise immediately releases event, modifications after `await` are all invalid (see constraints of each method below).

### Method 1: Return Value (mods) — Auto Mode

Handler's **return value** merges into event writeback (mods):

```js
eventOn('serverPing', (e) => {
    return { motd: 'Hello!' };   // Merge writeback, modify server list MOTD
});
```

Returning **Promise treated as no modification, immediately released, doesn't wait for completion** (async handler's async results won't writeback):

```js
eventOn('blockBreak', async (e) => {
    e.cancelled = true;           // ✅ Synchronous segment effective
    await fetchData();            // Event already released
    return { cancelled: false };  // ❌ Invalid
});
```

### Method 2: Modify Event Parameters (event) — Auto Mode

In auto mode **directly assigning event fields** also writes back (merges with return value, direct assignment takes priority over return value; all fields except `cancelled` collected):

```js
eventOn('playerDeath', (e) => {
    e.deathMessage = { text: '§cA hero has fallen.' };  // Writeback death message (Message object or string)
});

eventOn('serverPing', (e) => {
    e.motd = 'Hello!';            // Equivalent to return { motd }
});

eventOn('blockBreak', (e) => {
    e.cancelled = true;           // Cancel (cancellable event)
});
```

> **Fields supporting writeback** (runtime actually applies; other field assignments sent but runtime ignores — read-only fields):
>
> | Event | Writeable Fields | Description |
> | ----- | ---------------- | ----------- |
> | `playerJoin` | `joinMessage` | Join message |
> | `playerQuit` | `quitMessage` | Quit message |
> | `playerChat` | `message` / `format` | Chat content / format |
> | `playerCommand` | `message` | Command string (including `/`) |
> | `playerMove` / `playerTeleport` | `to` | Target position: `{x, y, z, yaw?, pitch?, world?}` (world defaults to current world; `from` / `cause` read-only) |
> | `playerRespawn` | `respawnLocation` | Respawn position (same shape as above) |
> | `playerDeath` | `deathMessage` | Message object `{key, args}` / `{text}` or string |
> | `foodLevelChange` | `newFoodLevel` | New food level |
> | `entityDamage` | `damage` | Damage amount |
> | `entityRegainHealth` | `amount` | Recovery amount |
> | `entityTarget` | `target` | Target entity UUID or `null` (clear target) |
> | `inventoryClick` | `clickedItem` / `cursorItem` | Clicked item / cursor item: `{type, amount?}` (`cursorItem`'s `amount: 0` means clear cursor) |
> | `serverPing` | `motd` / `maxPlayers` / `numPlayers` / `icon` | See event table (`numPlayers` only Paper supported — Folia's `ServerListPingEvent` base class has no setter) |
> | All cancellable events | `cancelled` | Cancel |

### Method 3: Manual Mode complete(mods)

After setting `{ manualRelease: true }`, handler receives `(event, complete)`, controls when to end event by calling `complete(result)`. Suitable for scenarios needing to asynchronously obtain data before deciding result:

```js
eventOn('blockBreak', { manualRelease: true }, (e, complete) => {
    doAsyncCheck((result) => {
        e.cancelled = result;
        complete({ cancelled: result });
    });
});
```

`complete` only takes effect once, subsequent calls ignored.

## Selection Guide

| Scenario                                            | Recommended             | Description                              |
| --------------------------------------------------- | ----------------------- | ---------------------------------------- |
| Only trigger logic (send message, log, API call)    | Auto + async            | Doesn't block main thread, freely use async API |
| Need to synchronously decide result (cancel, change death message, MOTD etc.) | Auto + sync handler | Directly assign event fields (or return mods) |
| Need to asynchronously obtain data before deciding result | Manual mode + `complete()` | User actively controls end timing |