# Player Tasks

Player-related operations. All tasks are sent via the `task` channel.

---

## Lookup

### `player.get`

Find an online player by UUID or player name.

- **Request**: `{ "identifier": "<uuid|name>" }`
- **Returns**: `{ "uuid": "<uuid>", "name": "<name>" }` | `null`

### `player.getAll`

Get all online players.

- **Request**: `{}`
- **Returns**: `[{ "uuid": "<uuid>", "name": "<name>" }, ...]`

---

## Property Read/Write

In the following tasks, `uuid` is the player UUID string.

### Basic Properties

| Task | Request | Returns |
|------|---------|---------|
| `player.getPing` | `{ "uuid": "<uuid>" }` | `number` (ms) |
| `player.getGamemode` | `{ "uuid": "<uuid>" }` | `"creative" \| "survival" \| "adventure" \| "spectator"` |
| `player.setGamemode` | `{ "uuid": "<uuid>", "value": "<gamemode>" }` | `true` |
| `player.getHealth` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setHealth` | `{ "uuid": "<uuid>", "value": <double> }` | `true` |
| `player.getFood` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setFood` | `{ "uuid": "<uuid>", "value": <int> }` | `true` |
| `player.getExp` | `{ "uuid": "<uuid>" }` | `number` (0.0-1.0) |
| `player.setExp` | `{ "uuid": "<uuid>", "value": <float> }` | `true` |
| `player.getLevel` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setLevel` | `{ "uuid": "<uuid>", "value": <int> }` | `true` |
| `player.getSaturation` | `{ "uuid": "<uuid>" }` | `number` |
| `player.getTotalExperience` | `{ "uuid": "<uuid>" }` | `number` |

### Boolean Properties

| Task | Request | Returns |
|------|---------|---------|
| `player.isOp` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.isOnline` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.isFlying` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.setFlying` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true` |
| `player.isSneaking` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.isSprinting` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.getBedLocation` | `{ "uuid": "<uuid>" }` | `{ "x","y","z","yaw","pitch","world" }` | `null` |
| `player.getAllowFlight` | `{ "uuid": "<uuid>" }` | `boolean` |
| `player.setAllowFlight` | `{ "uuid": "<uuid>", "value": <boolean> }` | `true` |

### Speed Properties

| Task | Request | Returns |
|------|---------|---------|
| `player.getWalkSpeed` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setWalkSpeed` | `{ "uuid": "<uuid>", "value": <float> }` | `true` |
| `player.getFlySpeed` | `{ "uuid": "<uuid>" }` | `number` |
| `player.setFlySpeed` | `{ "uuid": "<uuid>", "value": <float> }` | `true` |

### Location and World

| Task | Request | Returns |
|------|---------|---------|
| `player.getWorld` | `{ "uuid": "<uuid>" }` | `string` (world name) |
| `player.getLocation` | `{ "uuid": "<uuid>" }` | `{ "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` |
| `player.teleport` | `{ "uuid": "<uuid>", "x": <double>, "y": <double>, "z": <double>, "yaw": <double>, "pitch": <double>, "world": "<name>" }` | `true` |
| `player.sendBlockChange` | `{ "uuid": "<uuid>", "x": <double>, "y": <double>, "z": <double>, "world": "<name>"?, "blockType": "<key>", "state": { ... }? }` | `true` | Sends a fake block change to the player (client-side visual only, does not modify the real world). `blockType` is the block key (input is lenient: `minecraft:` may be omitted, case-insensitive); `state` is a block state key-value map (values preserve type — number/boolean/string); when `world` is omitted, defaults to the player's current world |
| `player.getDisplayName` | `{ "uuid": "<uuid>" }` | `string` |
| `player.setDisplayName` | `{ "uuid": "<uuid>", "value": "<name>" }` | `true` |

---

## Interaction

### Messages and Notifications

| Task | Request | Returns | Description |
|------|---------|---------|-------------|
| `player.sendMessage` | `{ "uuid": "<uuid>", "message": <Message> }` | `true` | Sends a message (`message` is a [Message object](#message-object-translatable-component) or plain text) |
| `player.sendActionBar` | `{ "uuid": "<uuid>", "message": <Message> }` | `true` | Sends an action bar message (same as above) |
| `player.sendTitle` | `{ "uuid": "<uuid>", "title": "<text>", "subtitle": "<text>", "fadeIn": <int>, "stay": <int>, "fadeOut": <int> }` | `true` | Sends title/subtitle (MiniMessage). `fadeIn`/`stay`/`fadeOut` are in ticks (default 10/70/20); pass `null` to `title`/`subtitle` to clear the respective field |
| `player.playSound` | `{ "uuid": "<uuid>", "sound": "<key>", "volume": <float>, "pitch": <float> }` | `true` | Plays a sound effect (sound is a Paper Sound enum name, e.g. `block.note_block.pling`) |
| `player.stopSound` | `{ "uuid": "<uuid>", "sound": "<key>" }` | `true` | Stops a specified sound. `sound` is resolved by registry key (e.g. `minecraft:block.note_block.pling`); **returns an error for unknown sounds** (will not accidentally stop all sounds) |
| `player.stopAllSounds` | `{ "uuid": "<uuid>" }` | `true` | Stops all sounds |
| `player.kick` | `{ "uuid": "<uuid>", "reason": "<text>" }` | `true` | Kicks the player |
| `player.giveExp` | `{ "uuid": "<uuid>", "amount": <int> }` | `true` | Grants experience points |
| `player.hasPermission` | `{ "uuid": "<uuid>", "permission": "<node>" }` | `boolean` | Checks a permission. `permission` is a node string; also accepts an object `{ "node": "<node>" }` (consistent with the permission object format used in command registration); goes through the `permissionCheck` ecosystem event first, falls back to Bukkit if unhandled |
| `player.performCommand` | `{ "uuid": "<uuid>", "command": "<cmd>" }` | `boolean` | Executes a command as the player (**without the `/` prefix**, e.g. `say hi`; counterpart to the server `command.dispatch` (console)) |

### Message Object (Translatable Component)

Payloads involving text (such as the `message` field) accept a **Message object** or a plain string:

```json
// 可翻译组件 + 纯文本兜底（key 与 text 可同时存在）
{ "key": "death.attack.player", "args": ["Steve", "Zombie"], "text": "§cSteve 被 Zombie 杀死了" }
// 纯文本（MiniMessage/legacy 解析）
{ "text": "<red>你死了</red>" }
// 纯字符串等价于 { "text": "<string>" }
```

| Field | Type | Description |
|-------|------|-------------|
| `key` | string | Minecraft translation key (e.g. `death.attack.player`); when present, constructs a translatable component |
| `args` | array | Translation arguments: string / number / nested Message (optional) |
| `text` | string | Plain text fallback (used when `key` is absent; both are passed when present alongside `key`) |

**Implementation note**: All implementations must at least support the `text` field (plain text); `key`/`args` are for translatable component support. When both `key` and `text` are present, **both are passed** (`key` for localization, `text` as a cross-implementation forwarding fallback).

### Resource Pack

| Task | Request | Returns | Description |
|------|---------|---------|-------------|
| `player.sendResourcePack` | `{ "uuid": "<uuid>", "url": "<url>", "hash": "<sha1>", "prompt": <Message>, "force": <boolean> }` | `true` | Prompts the client to download a resource pack. `hash` is a SHA-1 hex string; `prompt` is a [Message object](#message-object-translatable-component) or plain text |

### Held Items

| Task | Request | Returns | Description |
|------|---------|---------|-------------|
| `player.getItemInMainHand` | `{ "uuid": "<uuid>" }` | `ItemStack` \| `null` | Reads the main hand item; returns `null` when empty |
| `player.getItemInOffHand` | `{ "uuid": "<uuid>" }` | `ItemStack` \| `null` | Reads the off hand item; returns `null` when empty |
| `player.setItemInMainHand` | `{ "uuid": "<uuid>", "item": <ItemStack \| null> }` | `true` | Sets the main hand item (full ItemStack with meta; null clears it) |
| `player.setItemInOffHand` | `{ "uuid": "<uuid>", "item": <ItemStack \| null> }` | `true` | Sets the off hand item (same as above) |

### Tab List / Border (2026-08-13)

| Task | Request | Returns | Description |
|------|---------|---------|-------------|
| `player.sendTabHeader` | `{ "uuid": "<uuid>", "header": "<text>"?, "footer": "<text>"? }` | `true` | Tab list header/footer (MiniMessage; null clears the respective field) |
| `player.setPlayerListName` | `{ "uuid": "<uuid>", "name": "<text>"? }` | `true` | Tab list display name (null restores default) |

`ItemStack` return format (**pure data**, values are a snapshot at the time of reading):

```json
{
  "type": "minecraft:diamond_sword",
  "amount": 1,
  "meta": {
    "displayName": "Sharp Sword",
    "lore": ["A very sharp sword"],
    "customModelData": 100,
    "unbreakable": true,
    "enchantments": { "minecraft:sharpness": 5 }
  }
}
```

---

## Text Formatting Conventions

All `message`, `title`, `subtitle`, `displayName`, `prompt`, `reason` fields that accept text support:

- [MiniMessage](https://docs.advntr.dev/minimessage/format) format (starting with `<`)
- Legacy `§` section separator format (backward compatible)

> For format rules and lists covering value domains (game modes, sounds, potion effects, enchantments, ItemFlags, damage types, etc.), see the [Values Appendix](../values.md).
