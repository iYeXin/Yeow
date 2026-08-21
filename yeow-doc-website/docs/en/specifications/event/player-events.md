# Player Events

## `playerJoin`

| Field          | Type          | Description                                        |
|----------------|---------------|----------------------------------------------------|
| `player`       | string (UUID) | The joined player                                  |
| `joinMessage`  | string        | The join message (**writable**: `mods.joinMessage`) |

## `playerQuit`

| Field          | Type          | Description                                        |
|----------------|---------------|----------------------------------------------------|
| `player`       | string (UUID) | The player who quit                                |
| `quitMessage`  | string        | The quit message (**writable**: `mods.quitMessage`) |

## `playerChat`

| Field     | Type          | Description                                   |
|-----------|---------------|-----------------------------------------------|
| `player`  | string (UUID) | The player who sent the message               |
| `message` | string        | The message content (**writable**: `mods.message`) |
| `format`  | string        | The message format (**writable**: `mods.format`) |

## `playerMove`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `player` | string (UUID) | The moving player |
| `from`   | Location      | The start position of the move (read-only) |
| `to`     | Location      | The target position of the move (**writable**: `mods.to` is `{x,y,z,yaw?,pitch?,world?}`; when world is omitted, the current world is used) |

## `playerInteract`

| Field      | Type           | Description |
|------------|----------------|-------------|
| `player`   | string (UUID)  | The interacting player |
| `action`   | string         | The interaction type (`LEFT_CLICK_AIR`, `RIGHT_CLICK_BLOCK`, etc.) |
| `material` | string \| null | The material key of the item in hand |
| `block`    | object \| null | `{ "x": <int>, "y": <int>, "z": <int>, "type": "<key>" }` |

## `playerCommand`

| Field     | Type          | Description |
|-----------|---------------|-------------|
| `player`  | string (UUID) | The player who ran the command |
| `message` | string        | The full command string (including `/`) (**writable**: `mods.message` rewrites the command) |

## `playerDeath`

| Field           | Type                                                     | Description |
|-----------------|----------------------------------------------------------|-------------|
| `player`        | string (UUID)                                            | The player who died |
| `deathMessage`  | [Message object](../task/player.md#message-对象可翻译组件) | The death message: `{ "key": "<translation key>", "args": [...], "text": "<plain text>" }` — the translatable component (`key`/`args`, when the original message is a translatable component) and the `text` plain-text fallback are **passed together**. **Writable**: `mods.deathMessage` is a Message object or a string |
| `deathType`     | string                                                   | The damage type registry key, e.g. `minecraft:lava` (see value ranges in appendix R1) |

> Plugins can directly forward `deathMessage` to a message-sending API (`broadcast`, `player.sendMessage`, etc.) — the Message object is naturally compatible.

## `playerRespawn`

| Field              | Type          | Description |
|--------------------|---------------|-------------|
| `player`           | string (UUID) | The respawning player |
| `respawnLocation`  | Location      | The respawn location (**writable**: `mods.respawnLocation` is `{x,y,z,yaw?,pitch?,world?}`) |

## `playerTeleport`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `player` | string (UUID) | The teleported player |
| `from`   | Location      | The teleport start position (read-only) |
| `to`     | Location      | The teleport target position (**writable**: `mods.to`, same as `playerMove`) |
| `cause`  | string        | The teleport cause (`PLUGIN`, `COMMAND`, `SPECTATE`, etc.) (read-only) |

## `playerItemConsume`

| Field      | Type          | Description |
|------------|---------------|-------------|
| `player`   | string (UUID) | The player who consumed the item |
| `itemType` | string        | The material key of the consumed item |

## `playerDropItem`

| Field      | Type          | Description |
|------------|---------------|-------------|
| `player`   | string (UUID) | The player who dropped the item |
| `itemType` | string        | The item's material key |
| `amount`   | number        | The quantity |

## `playerPickupItem`

| Field      | Type          | Description |
|------------|---------------|-------------|
| `player`   | string (UUID) | The player who picked up the item |
| `itemType` | string        | The item's material key |
| `amount`   | number        | The quantity |

## `playerBucketFill`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `player` | string (UUID) | The operating player |
| `bucket` | string        | The bucket item's material key |

## `playerBucketEmpty`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `player` | string (UUID) | The operating player |
| `bucket` | string        | The bucket item's material key |

## `playerExpChange`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `player` | string (UUID) | The player |
| `amount` | number        | The experience change amount |

## `playerLevelChange`

| Field      | Type          | Description |
|------------|---------------|-------------|
| `player`   | string (UUID) | The player |
| `oldLevel` | number        | The old level |
| `newLevel` | number        | The new level |

## `playerGameModeChange`

| Field          | Type          | Description |
|----------------|---------------|-------------|
| `player`       | string (UUID) | The player |
| `newGameMode`  | string        | The new game mode (`creative`, `survival`, `adventure`, `spectator`, lowercase, consistent with `player.getGamemode`) |

## `playerAdvancementDone`

| Field         | Type                                                            | Description |
|---------------|-----------------------------------------------------------------|-------------|
| `player`      | string (UUID)                                                   | The player |
| `advancement` | string                                                          | The advancement namespaced key (e.g. `minecraft:story/root`) |
| `title`       | [Message object](../task/player.md#message-对象可翻译组件) (optional) | The advancement title: a translatable component (`{key, args, text}`, vanilla advancement titles are translatable components) or plain text (`{text}`); missing when the advancement is hidden |
| `description` | [Message object](../task/player.md#message-对象可翻译组件) (optional) | The advancement description (same as above) |

## `playerToggleSneak`

| Field      | Type          | Description |
|------------|---------------|-------------|
| `player`   | string (UUID) | The player |
| `sneaking` | boolean       | Whether the player entered the sneaking state |

## `playerToggleFlight`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `player` | string (UUID) | The player |
| `flying` | boolean       | Whether the player entered the flying state |

## `foodLevelChange`

| Field           | Type          | Description |
|-----------------|---------------|-------------|
| `player`        | string (UUID) | The player |
| `oldFoodLevel`  | number        | The old food level |
| `newFoodLevel`  | number        | The new food level (**writable**: `mods.newFoodLevel`) |
