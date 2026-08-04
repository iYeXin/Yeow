# Player API

```js
import { Player } from 'yeow-api';
```

## 静态方法

默认为异步（`Promise`），同步版本加 `Sync` 后缀。

| 方法 | 返回 | 说明 |
|------|------|------|
| `Player.get(identifier)` | `Promise<Player \| null>` | UUID 或名字，离线返回 null |
| `Player.getSync(identifier)` | `Player \| null` | 同步版 |
| `Player.getAll()` | `Promise<Player[]>` | 所有在线玩家 |
| `Player.getAllSync()` | `Player[]` | 同步版 |

## 属性

| 属性 | 类型 | 读写 | 说明 |
|------|------|:----:|------|
| `uuid` | `string` | 只读 | UUID |
| `name` | `string` | 只读 | 玩家名 |
| `ping` | `number` | 只读 | 延迟（ms） |
| `world` | `string` | 只读 | 所在世界名 |
| `location` | `Location \| null` | 只读 | 当前位置 |
| `displayName` | `string` | 读写 | 显示名 |
| `saturation` | `number` | 只读 | 饱和度 0.0-? |
| `totalExperience` | `number` | 只读 | 总经验值 |
| `gamemode` | `string` | 读写 | SURVIVAL / CREATIVE / ADVENTURE / SPECTATOR |
| `health` | `number` | 读写 | 生命值（半心） |
| `foodLevel` | `number` | 读写 | 饱食度 0-20 |
| `exp` | `number` | 读写 | 等级进度 0.0-1.0 |
| `level` | `number` | 读写 | 经验等级 |
| `allowFlight` | `boolean` | 读写 | 允许飞行 |
| `isFlying` | `boolean` | 读写 | 正在飞行 |
| `isSneaking` | `boolean` | 只读 | 正在潜行 |
| `isSprinting` | `boolean` | 只读 | 正在疾跑 |
| `bedLocation` | `Location \| null` | 只读 | 床的重生点 |
| `walkSpeed` | `number` | 读写 | 行走速度 0-1 |
| `flySpeed` | `number` | 读写 | 飞行速度 0-1 |
| `isOp` | `boolean` | 只读 | 是否 OP |
| `online` | `boolean` | 只读 | 是否在线 |

## 方法

默认为异步（`Promise`），同步版本加 `Sync` 后缀。

### 消息

```js
player.sendMessage(msg)             // Promise
player.sendMessageSync(msg)
player.kick(reason?)                 // Promise
player.kickSync(reason?)
```

### 标题与音效

```js
player.sendTitle(title?, subtitle?, fadeIn?, stay?, fadeOut?)   // Promise
player.sendTitleSync(...)
player.playSound(sound, volume?, pitch?)                        // Promise
player.playSoundSync(...)
```

```js
// 世界级音效 参见 World API
import { playSound } from 'yeow-api';
await playSound('world', 'entity.creeper.primed', 0, 65, 0, 1.0, 1.0);
```

```js
// 停止音效
import { stopSound, stopAllSounds } from 'yeow-api';
await stopSound(uuid, 'block.note_block.pling');
await stopAllSounds(uuid);
```

### 经验

```js
player.giveExp(amount)              // Promise
player.giveExpSync(amount)
```

### 权限与传送

```js
player.hasPermission(node)          // Promise<boolean>
player.hasPermissionSync(node)      // boolean
player.teleport(loc)                // Promise
player.teleportSync(loc)
```

### 手持物品

```js
player.getItemInMainHand()          // Promise<ItemStack | null>
player.getItemInMainHandSync()      // ItemStack | null
player.getItemInOffHand()           // Promise<ItemStack | null>
player.getItemInOffHandSync()       // ItemStack | null
```

返回完整 `ItemStack`（含 meta），手心为空时返回 `null`。

### ActionBar 与资源包

```js
player.sendActionBar(message)       // Promise
player.sendActionBarSync(message)
player.sendResourcePack(url, hash?, prompt?, force?)  // Promise
```

### 异步属性访问

以下属性同时提供同步 getter（属性访问）和异步方法：

| 同步 getter | 异步方法 | 返回 |
|------------|---------|------|
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
| `player.online` | `player.getOnline()` | `Promise<boolean>` |
| `player.isFlying` | `player.isFlyingAsync()` | `Promise<boolean>` |
| `player.allowFlight` | `player.getAllowFlight()` | `Promise<boolean>` |
| `player.isSneaking` | `player.isSneakingAsync()` | `Promise<boolean>` |
| `player.isSprinting` | `player.isSprintingAsync()` | `Promise<boolean>` |
| `player.bedLocation` | `player.getBedLocation()` | `Promise<Location \| null>` |
| `player.walkSpeed` | `player.getWalkSpeed()` | `Promise<number>` |
| `player.flySpeed` | `player.getFlySpeed()` | `Promise<number>` |

异步 setter：

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

## 示例

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
