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

## 服务器图标与 MOTD

运行时设置服务器列表图标已移除（Paper 1.20.5+ 不再提供运行时 setter），且 MOTD 也可按次覆盖。请在 **`serverPing` 事件**中修改：

```js
eventOn('serverPing', (e) => {
    return { motd: '<green>Hi</green>', icon: base64Png };   // handler 返回 { motd } / { icon } 修改
});
```

MOTD 经 Yeow 文本解析（MiniMessage 优先，含 § 时回退 legacy）；图标为 64x64 PNG 的 base64（不含 `data:image/png;base64,` 前缀），非 64x64 会自动缩放；无效图片忽略。详见 [serverPing 事件](event.md)。

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

> ⚠ **跨平台不保证可用**：TPS 是宿主平台的运行指标（Paper 平台基于 `Bukkit.getTPS`）——其他平台运行时不保证支持，且未来 TPS 这一概念可能发生变化。调用前请自行降级处理（如捕获异常或按平台判断）。

## 最大玩家数

```js
await getMaxPlayers()    // Promise<number>
getMaxPlayersSync()      // number — 同步
```
