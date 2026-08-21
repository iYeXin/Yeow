# Server API

```js
import { broadcast, broadcastSync, dispatchCommand, dispatchCommandSync, setMotd, setMotdSync, getMotd, getVersion } from 'yeow-api';
```

## Broadcast

```js
broadcast(msg)                // Promise — async
broadcastSync(msg)            // void — sync
```

## Execute command

```js
await dispatchCommand('op Notch')       // Promise<boolean>
dispatchCommandSync('say hello')        // boolean — sync
```

## Server MOTD

```js
await setMotd('<red>New MOTD</red>')    // Promise — supports MiniMessage
setMotdSync('A Minecraft Server')       // void — sync
```

## Server list response (serverPing event)

**To override the server list (MOTD / icon / player count) per-ping, modify it in the `serverPing` event** — `setMotd` sets the **global default MOTD** (persistent, visible to all clients); the write-back in `serverPing` only affects **that** ping response and has **higher priority, overriding the result of `setMotd`/`server-icon.png`**:

```js
eventOn('serverPing', (e) => {
    return {
        motd: '<green>Hi</green>',        // override that ping's MOTD (not overridden if not returned)
        icon: base64Png,                  // override that ping's icon (not overridden if not returned)
        maxPlayers: 100,                  // override that ping's displayed max player count (not recommended to change)
        numPlayers: 42,                   // override that ping's displayed online player count (not recommended to change)
    };
});
```

- The MOTD is parsed by Yeow's text handling (MiniMessage first, falling back to legacy when it contains `§`); the icon is base64 of a 64x64 PNG (without the `data:image/png;base64,` prefix); non-64x64 images are auto-scaled; invalid images are ignored
- Setting the server list icon at runtime has been removed (Paper 1.20.5+ no longer provides a runtime setter); the icon can only be modified in `serverPing`
- `maxPlayers`/`numPlayers` are **not recommended to change** — they only affect display, not the actual join limit; spoofing the online player count may violate server list policies
- When multiple handlers write back, the last one wins; fields not returned keep their defaults. See the [serverPing event](event.md)

## Server info

```js
await getMotd()          // Promise<string>
getMotdSync()            // string — sync
await getVersion()       // Promise<string>
getVersionSync()         // string — sync
```

## Server TPS

```js
await getTps()           // Promise<{ tps1m, tps5m, tps15m }>
getTpsSync()             // { tps1m, tps5m, tps15m } — sync
```

> ⚠ **Not guaranteed across platforms**: TPS is a runtime metric of the host platform (on the Paper platform it is based on `Bukkit.getTPS`) — other platform runtimes are not guaranteed to support it, and the concept of TPS may change in the future. Validate the return values yourself before using them.

> **Folia platform behavior**: Folia uses regionized multithreading and has **no global TPS concept** — the `tps1m` / `tps5m` / `tps15m` values of `getTps` (and `getTpsSync`) all return `null` (no throw, no reject). Plugins should detect that TPS is unavailable based on this and degrade gracefully, for example:

```js
const tps = await getTps();
if (tps.tps1m == null) { /* */ }
```

## Max players

```js
await getMaxPlayers()    // Promise<number>
getMaxPlayersSync()      // number — sync
```
