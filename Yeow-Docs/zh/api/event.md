# Event API

```js
import { eventOn, eventOff } from 'yeow-api';
```

## eventOn(eventType, handler)

订阅 Bukkit 事件。

| 参数 | 类型 | 说明 |
|------|------|------|
| `eventType` | `string` | 事件类型名 |
| `handler` | `(e: EventType) => void` | 事件处理器 |
| `options.manualRelease` | `boolean` | 手动控制事件结束（详见下文） |

返回一个取消订阅函数：`() => void`。

```js
const unsubscribe = eventOn('playerJoin', (e) => { ... });
unsubscribe();  // 取消订阅

// 自动模式（默认）：同步返回立即结束，async 函数返回 Promise 时立即结束，不等待
eventOn('blockBreak', (e) => { e.cancelled = true; });
eventOn('blockBreak', async (e) => {
    e.cancelled = true;  // ✅ 同步段生效
    await something();    // 事件已在此前结束，此后设值无效
});
```

## eventOn(eventType, options, handler)

手动结束模式：

```js
eventOn('blockBreak', { manualRelease: true }, (e, complete) => {
    fetchPermission(result => {
        e.cancelled = !result.allowed;
        complete({ cancelled: e.cancelled });  // 手动结束事件
    });
});
```

## eventOff(eventType, handler)

取消订阅。需要传入与 `eventOn` 相同的 handler 引用。

```js
const handler = (e) => { ... };
eventOn('playerJoin', handler);
eventOff('playerJoin', handler);
```

## 事件参数

TS 模式下有完整类型定义：

```ts
import type { PlayerJoinEvent, BlockBreakEvent } from 'yeow-api';

eventOn('playerJoin', (e: PlayerJoinEvent) => {
    console.log(e.player.name);   // Player 对象
    console.log(e.joinMessage);
});
```

JS 模式下事件对象的字段名与类型表一致，`player` 字段自动转为 `Player` 对象。

## 事件列表

### 玩家事件

| 类型 | 字段 | 可取消 |
|------|------|:------:|
| `playerJoin` | player, joinMessage | |
| `playerQuit` | player, quitMessage | |
| `playerChat` | player, message, format | ✔ |
| `playerMove` | player, from, to | ✔ |
| `playerInteract` | player, action, material, block | ✔ |
| `playerCommand` | player, message | ✔ |
| `playerDeath` | player, deathMessage, deathType | |
| `playerRespawn` | player, respawnLocation | |
| `playerTeleport` | player, from, to, cause | ✔ |
| `playerItemConsume` | player, itemType | |
| `playerDropItem` | player, itemType, amount | ✔ |
| `playerPickupItem` | player, itemType, amount | ✔ |
| `playerBucketFill` | player, bucket | ✔ |
| `playerBucketEmpty` | player, bucket | ✔ |
| `playerExpChange` | player, amount | |
| `playerLevelChange` | player, oldLevel, newLevel | |
| `playerGameModeChange` | player, newGameMode | ✔ |
| `playerAdvancementDone` | player, advancement | |
| `playerToggleSneak` | player, sneaking | |
| `playerToggleFlight` | player, flying | |
| `foodLevelChange` | player, oldFoodLevel, newFoodLevel | ✔ |

### 实体事件

| 类型 | 字段 | 可取消 |
|------|------|:------:|
| `entityDamage` | entity, damage, cause, entityType | ✔ |
| `entityDeath` | entity, entityType, entityName | |
| `entitySpawn` | entity, entityType, x, y, z, world | ✔ |
| `entityExplode` | entity, entityType, x, y, z, blockCount | ✔ |
| `entityRegainHealth` | entity, amount, reason | |
| `entityTarget` | entity, target | ✔ |
| `projectileLaunch` | entity, projectileType, shooter? | ✔ |
| `projectileHit` | entity, projectileType, hitEntity?, hitBlock? | |

### 世界事件

| 类型 | 字段 | 可取消 |
|------|------|:------:|
| `blockBreak` | player, block, x, y, z | ✔ |
| `blockPlace` | player, block, blockAgainst, x, y, z | ✔ |
| `blockFade` | block, x, y, z | |
| `blockGrow` | block, x, y, z | |
| `blockSpread` | block, x, y, z | |
| `blockExplode` | block, x, y, z | ✔ |

### 背包事件

| 类型 | 字段 | 可取消 |
|------|------|:------:|
| `inventoryOpen` | player, inventoryType, title | |
| `inventoryClose` | player, inventoryType | |
| `inventoryClick` | player, slot, hotbarKey, action, inventoryType, isLeftClick, isRightClick, isShiftClick, clickedItem, cursorItem | ✔ |

### 服务器事件

| 类型 | 字段 | 可取消 |
|------|------|:------:|
| `serverPing` | address, numPlayers, maxPlayers, motd | ✔ |
| `serverCommand` | command, sender | ✔ |
| `playerResourcePackStatus` | player, status, hash | |

> `player` 字段在 JS 侧自动转为 `Player.get(uuid)`。`block` 为命名空间 ID（如 `minecraft:stone`）。

## 取消事件

### 自动模式（默认）

可取消事件在同步代码段中设置 `e.cancelled = true`：

```js
eventOn('blockBreak', (e) => {
    if (e.block === 'minecraft:bedrock') e.cancelled = true;
});
```

async handler 在返回 Promise 时**立即释放**，只有第一个 `await` 前的同步修改生效：

```js
eventOn('blockBreak', async (e) => {
    e.cancelled = true;           // ✅ 同步段生效
    await fetchData();
    e.cancelled = false;          // ❌ 事件已释放，无效
});
```

### 手动模式

设置 `{ manualRelease: true }` 后，handler 接收 `(event, complete)`，通过调用 `complete(result)` 控制何时结束事件。适合需要异步操作后再决定取消的场景：

```js
eventOn('blockBreak', { manualRelease: true }, (e, complete) => {
    doAsyncCheck((result) => {
        e.cancelled = result;
        complete({ cancelled: result });
    });
});
```

`complete` 只会生效一次，后续调用被忽略。

## 选择指南

| 场景 | 推荐 | 说明 |
|------|------|------|
| 仅触发逻辑（发消息、日志、API 调用） | 自动 + async | 不阻塞主线程，自由使用异步 API |
| 需要同步决定结果（取消、改掉落等） | 自动 + 同步 handler | `await` 前设值即可 |
| 需要异步获取数据后决定结果 | 手动模式 + `complete()` | 用户主动控制结束时机 |
