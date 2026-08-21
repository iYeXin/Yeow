# Event System Tasks

Event subscription, cancellation, and completion notifications.

---

## Overview

The Yeow event system uses `event.subscribe` to register event listeners and `event.complete` to reply with the processing result, connecting the plugin and game events synchronously/asynchronously.

## `event.subscribe`

Subscribes to a game event.

- **Request**: `{ "pluginName": "<name>", "eventType": "<eventType>", "callbackId": "<cbId>" }`
- **Return**: `true`

| Field         | Required | Description                                                                                 |
| ------------ | ---- | ------------------------------------------------------------------------------------ |
| `pluginName` | Yes   | Owning plugin name                                                                           |
| `eventType`  | Yes   | Event name (camelCase, e.g. `playerJoin`), see the [event name table](../event/index.md#事件名称表) |
| `callbackId` | Yes   | Event handler callback ID (must be registered with `persistent: true`)                                  |

**Trigger timing:** When a game event occurs, the runtime delivers event data to `callbackId` (via the `cb` channel, with `r` being the event data object). The event data includes the `_cancellable` boolean field marking whether it can be cancelled, and `_eventId` (**a unique id per dispatch**, see below); the top-level `eventId` field has the same value.

## `event.unsubscribe`

Unsubscribes from an event.

- **Request**: `{ "pluginName": "<name>", "eventType": "<eventType>" }` \| `{ "pluginName": "<name>", "callbackId": "<cbId>" }`
- **Return**: `true`

The implementation should also remove the corresponding event listener, to avoid events continuing to be sent to JS callbacks that no longer respond.

## `event.complete`

The event handler completion signal — the plugin informs the runtime that event handling has finished and subsequent steps can be scheduled.

- **Request**: `{ "eventId": "<eventId>", "mods": { "cancelled": <bool> } }`
- **Return**: `true`

| Field             | Required | Description                                                                                     |
| ---------------- | ---- | ---------------------------------------------------------------------------------------- |
| `eventId`        | Yes   | The `_eventId` carried when the runtime delivers event data (top-level `eventId`), **returned as-is** — the unique id per dispatch |
| `mods`           | No   | Event modification object. When omitted or `null`, treated as `{}`                                             |
| `mods.cancelled` | Yes*  | If `true`, the runtime must cancel the event (when `mods` is present)                                       |

> **mods can carry arbitrary write-back fields**, and the runtime applies the **commonly stable fields** according to the event type (for each event's writable fields, see the fields marked "writable" in the [Event Data Specification](../event/index.md); unmarked fields are ignored). Besides `cancelled`, the following are currently supported: `joinMessage` / `quitMessage` / `message` (chat, command) / `format` / `to` / `respawnLocation` / `deathMessage` / `newFoodLevel` / `damage` / `amount` / `target` / `clickedItem` / `cursorItem` / serverPing's `motd` / `maxPlayers` / `numPlayers` / `icon`.

---

## Event Handling Flow

```
1. A game event triggers
2. The runtime finds the plugins subscribed to this event and their callbackIds
3. Event data is delivered through the cb channel → {t:"cb", p:callbackId, eventId, r:{...event data, _cancellable:bool, _eventId}}
4. The JS-side event handler executes
5. The JS side sends event.complete (params contain eventId) → received by the runtime
6. The runtime matches this dispatch by eventId and applies mods (e.g. cancelled)
```

### Multiple Plugin Concurrency

When multiple plugins subscribe to the same event, the implementation can choose:

- **Serial**: sends the event to each plugin one by one, waiting for one plugin to complete (`event.complete`) before sending to the next
- **Concurrent**: sends to all subscribed plugins at once, waiting for the slowest plugin to finish

In both modes, the merge strategy for each plugin's modification of `cancelled` is: if any plugin sets it to cancelled, it is cancelled.
