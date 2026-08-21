# Player API

```js
import { Player } from 'yeow-api';
```

**Type relationship: `Player extends LivingEntity extends Entity`** — Entity common capabilities (`velocity` (`setVelocity`), `fireTicks`, `getBoundingBox`, `getPassengers`, `teleport` etc.) and living capabilities (`health`, `maxHealth`, `damage`, `setTarget` etc.) are directly available on Player, see [Entity API](entity.md) for details. Player-specific `health`/`world`/`location`/`teleport` etc. override base class same-name members (go through player-side tasks).

## Static Methods

Default is async (`Promise`), synchronous version adds `Sync` suffix.

| Method | Return | Description |
| ------ | ------ | ----------- |
| `Player.get(identifier)` | `Promise<Player \| null>` | UUID or name, returns null if offline |
| `Player.getSync(identifier)` | `Player \| null` | Synchronous version |
| `Player.getAll()` | `Promise<Player[]>` | All online players |
| `Player.getAllSync()` | `Player[]` | Synchronous version |

## Properties

| Property | Type | Read/Write | Description |
| -------- | ---- | :--------: | ----------- |
| `uuid` | `string` | Read-only | UUID |
| `name` | `string` | Read-only | Player name |
| `ping` | `number` | Read-only | Latency (ms) |
| `world` | `string` | Read-only | Current world name |
| `location` | `Location \| null` | Read-only | Current location |
| `displayName` | `string` | Read/Write | Display name |
| `saturation` | `number` | Read-only | Saturation 0.0-? |
| `totalExperience` | `number` | Read-only | Total experience |
| `gamemode` | `string` | Read/Write | SURVIVAL / CREATIVE / ADVENTURE / SPECTATOR |
| `health` | `number` | Read/Write | Health (half hearts) |
| `foodLevel` | `number` | Read/Write | Food level 0-20 |
| `exp` | `number` | Read/Write | Level progress 0.0-1.0 |
| `level` | `number` | Read/Write | Experience level |

> Game mode values (`gamemode`) see [Value Domain Appendix · Directly Maintained Enumeration List](../specifications/values.md#2-directly-maintained-enumeration-list).

| `allowFlight` | `boolean` | Read/Write | Allow flight |
| `isFlying` | `boolean` | Read/Write | Currently flying |
| `isSneaking` | `boolean` | Read-only | Currently sneaking |
| `isSprinting` | `boolean` | Read-only | Currently sprinting |
| `bedLocation` | `Location \| null` | Read-only | Bed respawn point |
| `walkSpeed` | `number` | Read/Write | Walk speed 0-1 |
| `flySpeed` | `number` | Read/Write | Fly speed 0-1 |
| `isOp` | `boolean` | Read-only | Is OP |
| `online` | `boolean` | Read-only | Is online |

## Methods

Default is async (`Promise`), synchronous version adds `Sync` suffix.

### Messages

```js
player.sendMessage(msg)             // Promise — msg is plain text or Message object (translatable component)
player.sendMessageSync(msg)
player.kick(reason?)                 // Promise
player.kickSync(reason?)
```

`msg` accepts [Message object](text.md#message-object-translatable-component) or plain string:

```js
await p.sendMessage('Welcome back!');                                   // Plain text (MiniMessage)
await p.sendMessage({ text: '<red>You died!</red>' });                // Plain text (equivalent)
await p.sendMessage({ key: 'death.attack.player', args: ['Steve'] }); // Translatable component (client localization)
```

### Title & Sound

```js
player.sendTitle(title?, subtitle?, fadeIn?, stay?, fadeOut?)   // Promise
player.sendTitleSync(...)
player.playSound(sound, volume?, pitch?)                        // Promise
player.playSoundSync(...)
```

```js
// World-level sound see World API
import { playSound } from 'yeow-api';
await playSound('world', 'entity.creeper.primed', 0, 65, 0, 1.0, 1.0);
```

```js
// Stop sound (Player instance method, symmetric with playSound)
await p.stopSound('block.note_block.pling');
await p.stopSoundSync('block.note_block.pling');
await p.stopAllSounds();
await p.stopAllSoundsSync();
```

### Advancement (Achievement)

```js
player.grantAdvancement(key)                  // Promise<boolean> — Grant all advancement criteria
player.grantAdvancementSync(key)
player.revokeAdvancement(key)                 // Promise<boolean> — Revoke advancement
player.awardCriteria(key, criteria)           // Promise<boolean> — Grant single criterion
player.awardCriteriaSync(key, criteria)
player.revokeCriteria(key, criteria)          // Promise<boolean> — Revoke single criterion
player.getAdvancementProgress(key)            // Promise<AdvancementProgress | null>
player.getAdvancementProgressSync(key)
```

`key` is advancement namespace key (e.g., `minecraft:story/mine_stone`). See [Advancement](advancement.md) for details.

### Experience

```js
player.giveExp(amount)              // Promise
player.giveExpSync(amount)
```

### Permissions, Commands & Teleport

```js
player.hasPermission(node)          // Promise<boolean> — Via Yeow permission check (permissionCheck event priority, falls back to Paper system if no handler)
player.hasPermissionSync(node)      // boolean
player.performCommand(cmd)          // Promise<boolean> — Execute command as player (without / prefix, e.g., 'say hi')
player.performCommandSync(cmd)      // boolean
player.teleport(loc)                // Promise
player.teleportSync(loc)
```

### Fake Block (Client Visual)

```js
player.sendBlockChange(loc, block)      // Promise — Client visual only, doesn't change real world
player.sendBlockChangeSync(loc, block)
```

`block` is [Block](block.md) object or string (same as `world.setBlock`, string has no state):

```js
await p.sendBlockChange(new Location(0, 80, 0), 'minecraft:stone');
await p.sendBlockChange(new Location(0, 80, 1), Block.of('minecraft:chest', { facing: 'north' }));
```

### Held Items

```js
player.getItemInMainHand()          // Promise<ItemStack | null>
player.getItemInMainHandSync()      // ItemStack | null
player.getItemInOffHand()           // Promise<ItemStack | null>
player.getItemInOffHandSync()       // ItemStack | null

player.setItemInMainHand(item)      // Promise — Set main hand (complete ItemStack including meta; null clears)
player.setItemInMainHandSync(item)
player.setItemInOffHand(item)       // Promise — Set off hand (same as above)
player.setItemInOffHandSync(item)
```

Returns complete `ItemStack` (including meta), returns `null` when hand is empty. `ItemStack` is pure data (snapshot), see [ItemStack](item.md) for details.

### Tab List

```js
player.sendTabHeader(header, footer)    // Promise — Tab list header/footer (MiniMessage; null clears corresponding field)
player.sendTabHeaderSync(header, footer)
player.setPlayerListName(name)          // Promise — Tab list display name (null restores default)
player.setPlayerListNameSync(name)
```

### ActionBar & Resource Pack

```js
player.sendActionBar(message)       // Promise — message is plain text or Message object
player.sendActionBarSync(message)
player.sendResourcePack(url, hash?, prompt?, force?)  // Promise — prompt is plain text or Message object
```

### Async Property Access

The following properties provide both synchronous getter (property access) and async methods:

| Sync Getter | Async Method | Return |
| ----------- | ------------ | ------ |
| `player.ping` | `player.getPing()` | `Promise<number>` |
| `player.gamemode` | `player.getGamemode()` | `Promise<string>` |
| `player.health` | `player.getHealth()` | `Promise<number>` |
| `player.food` | `player.getFood()` | `Promise<number>` |
| `player.exp` | `player.getExp()` | `Promise<number>` |
| `player.level` | `player.getLevel()` | `Promise<number>` |
| `player.world` | `player.getWorld()` | `Promise<string>` |
| `player.location` | `player.getLocation()` | `Promise<Location \| null>` |
| `player.displayName` | `player.getDisplayName()` | `Promise<string>` |
| `player.saturation` | `player.getSaturation()` | `Promise<number>` |
| `player.totalExperience` | `player.getTotalExperience()` | `Promise<number>` |
| `player.isOp` | `player.isOpAsync()` | `Promise<boolean>` |
| `player.online` | `player.isOnlineAsync()` | `Promise<boolean>` |
| `player.isFlying` | `player.isFlyingAsync()` | `Promise<boolean>` |
| `player.allowFlight` | `player.getAllowFlight()` | `Promise<boolean>` |
| `player.isSneaking` | `player.isSneakingAsync()` | `Promise<boolean>` |
| `player.isSprinting` | `player.isSprintingAsync()` | `Promise<boolean>` |
| `player.bedLocation` | `player.getBedLocation()` | `Promise<Location \| null>` |
| `player.walkSpeed` | `player.getWalkSpeed()` | `Promise<number>` |
| `player.flySpeed` | `player.getFlySpeed()` | `Promise<number>` |

Async setter:

```js
player.setGamemode(mode)            // Promise
player.setHealth(value)             // Promise
player.setFood(value)               // Promise
player.setExp(value)                // Promise
player.setLevel(value)              // Promise
player.setDisplayName(name)         // Promise
player.setFlying(flag)              // Promise
player.setAllowFlight(flag)         // Promise
player.setWalkSpeed(speed)          // Promise
player.setFlySpeed(speed)           // Promise
```

## Example

```js
const p = await Player.get('Notch');
if (p) {
    p.gamemode = 'CREATIVE';
    p.health = 20;
    p.foodLevel = 20;
    p.exp = 0.5;
    p.level = 30;
    p.allowFlight = true;
    p.isFlying = true;
    await p.sendMessage('<green>Welcome!</green>');
    await p.sendTitle('Hello', 'Welcome to the server');
    await p.playSound('entity.experience_orb.pickup');
    await p.teleport(new Location(0, 80, 0));
}
```