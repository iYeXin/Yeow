# Server API

```js
import { broadcast, broadcastSync, dispatchCommand, dispatchCommandSync, setMotd, setMotdSync, getMotd, getVersion } from 'yeow-api';
```

## 广播

```js
broadcast(msg)                // Promise — 异步
broadcastSync(msg)            // void — 同步
```

## 执行命令

```js
await dispatchCommand('op Notch')       // Promise<boolean>
dispatchCommandSync('say hello')        // boolean — 同步
```

## 服务器 MOTD

```js
await setMotd('<red>New MOTD</red>')    // Promise — 支持 MiniMessage
setMotdSync('A Minecraft Server')       // void — 同步
```

## 服务器列表响应（serverPing 事件）

**服务器列表（MOTD / 图标 / 人数）按次回写请在 `serverPing` 事件中修改**——`setMotd` 设置的是**全局默认 MOTD**（持久，所有客户端可见）；`serverPing` 的回写只影响**该次** ping 响应，**优先级更高，覆盖 `setMotd`/`server-icon.png` 的结果**：

```js
eventOn('serverPing', (e) => {
    return {
        motd: '<green>Hi</green>',        // 覆盖该次 MOTD（未返回则不覆盖）
        icon: base64Png,                  // 覆盖该次图标（未返回则不覆盖）
        maxPlayers: 100,                  // 覆盖该次显示的最大玩家数（不建议修改）
        numPlayers: 42,                   // 覆盖该次显示的在线人数（不建议修改）
    };
});
```

- MOTD 经 Yeow 文本解析（MiniMessage 优先，含 § 时回退 legacy）；图标为 64x64 PNG 的 base64（不含 `data:image/png;base64,` 前缀），非 64x64 会自动缩放；无效图片忽略
- 运行时设置服务器列表图标已移除（Paper 1.20.5+ 不再提供运行时 setter），图标只能在 `serverPing` 中修改
- `maxPlayers`/`numPlayers` **不建议修改**——仅影响显示，不改变实际进入限制；伪装在线人数可能违反服务器列表政策
- 多个 handler 回写时以最后一个为准；未返回的字段保持默认值。详见 [serverPing 事件](event.md)

## 服务器信息

```js
await getMotd()          // Promise<string>
getMotdSync()            // string — 同步
await getVersion()       // Promise<string>
getVersionSync()         // string — 同步
```

## 服务器 TPS

```js
await getTps()           // Promise<{ tps1m, tps5m, tps15m }>
getTpsSync()             // { tps1m, tps5m, tps15m } — 同步
```

> ⚠ **跨平台不保证可用**：TPS 是宿主平台的运行指标（Paper 平台基于 `Bukkit.getTPS`）——其他平台运行时不保证支持，且未来 TPS 这一概念可能发生变化。调用前请自行判断返回值有效性。

> **Folia 平台行为**：Folia 采用区域化多线程，**不存在全局 TPS 概念**——`getTps`（及 `getTpsSync`）的 `tps1m` / `tps5m` / `tps15m` 三个值均返回 `null`（不抛错，不 reject）。插件应据此判断 TPS 不可用并降级，例如：

```js
const tps = await getTps();
if (tps.tps1m == null) { /* */ }
```

## 最大玩家数

```js
await getMaxPlayers()    // Promise<number>
getMaxPlayersSync()      // number — 同步
```
