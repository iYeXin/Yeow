# Server API

```js
import { broadcast, broadcastSync, dispatchCommand, dispatchCommandSync, setMotd, setMotdSync, setIcon, setIconSync, getMotd, getVersion } from 'yeow-api';
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

## 服务器图标

```js
// 平台特异，不保证有效性
await setIcon(base64Png)                // Promise — base64 编码的 PNG
setIconSync(base64Png)                  // void — 同步
```

图标需要 64x64 PNG，以 base64 传入（不含 `data:image/png;base64,` 前缀）。

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
