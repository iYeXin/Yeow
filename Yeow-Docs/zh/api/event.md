# Event API

```js
import { eventOn, eventOff } from 'yeow-api';
```

## eventOn(eventType, handler)

| 参数                    | 类型                     | 说明                         |
| ----------------------- | ------------------------ | ---------------------------- |
| `eventType`             | `string`                 | 事件类型名                   |
| `handler`               | `(e: EventType) => void` | 事件处理器                   |
| `options.manualRelease` | `boolean`                | 手动控制事件结束（详见下文） |

返回一个取消订阅函数：`() => void`。

> **同一事件可注册多个 handler，全部生效**（按注册顺序串行调用；并发模式下并行投递）。`eventOff` 按 handler 引用移除对应回调；全部移除后才停止订阅该事件。

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

| 类型                    | 字段                                                        | 可取消 |
| ----------------------- | ----------------------------------------------------------- | :----: |
| `playerJoin`            | player, joinMessage                                         |        |
| `playerQuit`            | player, quitMessage                                         |        |
| `playerChat`            | player, message, format                                     |   ✔    |
| `playerMove`            | player, from, to                                            |   ✔    |
| `playerInteract`        | player, action, material, block                             |   ✔    |
| `playerCommand`         | player, message                                             |   ✔    |
| `playerDeath`           | player, deathMessage(Message), deathType                    |   ✔    |
| `playerRespawn`         | player, respawnLocation                                     |        |
| `playerTeleport`        | player, from, to, cause                                     |   ✔    |
| `playerItemConsume`     | player, itemType                                            |        |
| `playerDropItem`        | player, itemType, amount                                    |   ✔    |
| `playerPickupItem`      | player, itemType, amount                                    |   ✔    |
| `playerBucketFill`      | player, bucket                                              |   ✔    |
| `playerBucketEmpty`     | player, bucket                                              |   ✔    |
| `playerExpChange`       | player, amount                                              |        |
| `playerLevelChange`     | player, oldLevel, newLevel                                  |        |
| `playerGameModeChange`  | player, newGameMode                                         |   ✔    |
| `playerAdvancementDone` | player, advancement, title(Message)?, description(Message)? |        |
| `playerToggleSneak`     | player, sneaking                                            |        |
| `playerToggleFlight`    | player, flying                                              |        |
| `foodLevelChange`       | player, oldFoodLevel, newFoodLevel                          |   ✔    |

### 实体事件

| 类型                 | 字段                                          | 可取消 |
| -------------------- | --------------------------------------------- | :----: |
| `entityDamage`       | entity, damage, cause, entityType             |   ✔    |
| `entityDeath`        | entity, entityType, entityName                |        |
| `entitySpawn`        | entity, entityType, x, y, z, world            |   ✔    |
| `entityExplode`      | entity, entityType, x, y, z, blockCount       |   ✔    |
| `entityRegainHealth` | entity, amount, reason                        |        |
| `entityTarget`       | entity, target                                |   ✔    |
| `projectileLaunch`   | entity, projectileType, shooter?              |   ✔    |
| `projectileHit`      | entity, projectileType, hitEntity?, hitBlock? |        |

### 世界事件

| 类型           | 字段                                 | 可取消 |
| -------------- | ------------------------------------ | :----: |
| `blockBreak`   | player, block, x, y, z               |   ✔    |
| `blockPlace`   | player, block, blockAgainst, x, y, z |   ✔    |
| `blockFade`    | block, x, y, z                       |        |
| `blockGrow`    | block, x, y, z                       |        |
| `blockSpread`  | block, x, y, z                       |        |
| `blockExplode` | block, x, y, z                       |   ✔    |

### 背包事件

| 类型             | 字段                                                                                                                               | 可取消 |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- | :----: |
| `inventoryOpen`  | player, inventoryType, title                                                                                                       |        |
| `inventoryClose` | player, inventoryType, **inventoryId?**                                                                                            |        |
| `inventoryClick` | player, slot, hotbarKey, action, inventoryType, isLeftClick, isRightClick, isShiftClick, clickedItem, cursorItem, **inventoryId?** |   ✔    |

> **inventoryId**（2026-08-13）：当事件发生在 Yeow 自定义 Inventory（`Inventory.create` 创建）上时携带该 Inventory 的句柄 id（`inventory.toString()`）——多自定义 Inventory 场景用 `e.inventoryId === inventory.toString()` 识别点击/关闭归属。非自定义 Inventory（背包、箱子等）时缺省。

### 服务器事件

| 类型                       | 字段                                  | 可取消 |
| -------------------------- | ------------------------------------- | :----: |
| `serverPing`               | address, numPlayers, maxPlayers, motd |   ✔    |
| `serverCommand`            | command, sender                       |   ✔    |
| `playerResourcePackStatus` | player, status, hash                  |        |

### 权限检查（Yeow 规范）

| 类型              | 字段         | 可取消 |
| ----------------- | ------------ | :----: |
| `permissionCheck` | target, node |        |

`permissionCheck` 用于 **Yeow 生态**权限拦截（仅 `player.hasPermission` 任务与 Yeow 命令执行检查触发；其他 Java 插件的权限检查不经过）。handler 返回 `{ allowed }` 决定结果（覆盖 Paper 系）；不返回视为未处理；多 handler 返回冲突以最后返回的为准。事件含 `permission` 对象（`{ node, default }`）。**⚠ 普通插件不建议监听（性能）；handler 中调用 `hasPermission` 会无限循环**。详见 [Permission](permission.md)：

```js
eventOn('permissionCheck', (e) => {
    const { target, node, permission } = e;
    if (node === 'myplugin.home' && isVip(target)) {
        return { allowed: true };   // 覆盖 Paper 系 结果
    }
    // 不返回 → 回退 Paper 系 hasPermission
});
```

> `player` 字段在 JS 侧自动转为 `new Player(uuid)`（直接构造，零往返；`name` 首次访问时惰性获取并缓存）。`block` 为命名空间 ID（如 `minecraft:stone`）。

## 事件回写

事件结果通过**三种方式**回写。回写只在 handler 的**同步段**生效——返回 Promise 时事件立即释放，`await` 之后的修改一律无效（见下文各方式的约束）。

### 方式 1：返回值（mods）— 自动模式

handler 的**返回值**合并到事件回写（mods）：

```js
eventOn('serverPing', (e) => {
    return { motd: 'Hello!' };   // 合并回写，修改服务器列表 MOTD
});
```

返回 **Promise 时视为无修改，立即释放，不等待其完成**（async handler 的异步结果不会回写）：

```js
eventOn('blockBreak', async (e) => {
    e.cancelled = true;           // ✅ 同步段生效
    await fetchData();            // 事件已释放
    return { cancelled: false };  // ❌ 无效
});
```

### 方式 2：修改事件参数（event）— 自动模式

自动模式下**直接赋值事件字段**同样回写（与返回值合并，直接赋值优先于返回值；`cancelled` 之外的所有字段都收集）：

```js
eventOn('playerDeath', (e) => {
    e.deathMessage = { text: '§cA hero has fallen.' };  // 回写死亡消息（Message 对象或字符串）
});

eventOn('serverPing', (e) => {
    e.motd = 'Hello!';            // 与 return { motd } 等价
});

eventOn('blockBreak', (e) => {
    e.cancelled = true;           // 取消（可取消事件）
});
```

> **支持回写的字段**（运行时实际应用；其余字段赋值会被发送但运行时忽略——只读字段）：
>
> | 事件 | 可回写字段 | 说明 |
> |------|-----------|------|
> | `playerJoin` | `joinMessage` | 加入消息 |
> | `playerQuit` | `quitMessage` | 退出消息 |
> | `playerChat` | `message` / `format` | 聊天内容 / 格式 |
> | `playerCommand` | `message` | 命令字符串（含 `/`） |
> | `playerMove` / `playerTeleport` | `to` | 目标位置：`{x, y, z, yaw?, pitch?, world?}`（world 缺省用当前世界；`from` / `cause` 只读） |
> | `playerRespawn` | `respawnLocation` | 重生位置（同上形状） |
> | `playerDeath` | `deathMessage` | Message 对象 `{key, args}` / `{text}` 或字符串 |
> | `foodLevelChange` | `newFoodLevel` | 新饥饿值 |
> | `entityDamage` | `damage` | 伤害值 |
> | `entityRegainHealth` | `amount` | 回复量 |
> | `entityTarget` | `target` | 目标实体 UUID 或 `null`（清除目标） |
> | `inventoryClick` | `clickedItem` / `cursorItem` | 点击物品 / 光标物品：`{type, amount?}`（`cursorItem` 的 `amount: 0` 表示清空光标） |
> | `serverPing` | `motd` / `maxPlayers` / `numPlayers` / `icon` | 见事件表（`numPlayers` 仅 Paper 支持——Folia 的 `ServerListPingEvent` 基类无 setter） |
> | 全部可取消事件 | `cancelled` | 取消 |

### 方式 3：手动模式 complete(mods)

设置 `{ manualRelease: true }` 后，handler 接收 `(event, complete)`，通过调用 `complete(result)` 控制何时结束事件。适合需要异步获取数据后再决定结果的场景：

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

| 场景                                          | 推荐                    | 说明                                   |
| --------------------------------------------- | ----------------------- | -------------------------------------- |
| 仅触发逻辑（发消息、日志、API 调用）          | 自动 + async            | 不阻塞主线程，自由使用异步 API         |
| 需要同步决定结果（取消、改死亡消息、MOTD 等） | 自动 + 同步 handler     | 直接赋值事件字段（或 return mods）即可 |
| 需要异步获取数据后决定结果                    | 手动模式 + `complete()` | 用户主动控制结束时机                   |
