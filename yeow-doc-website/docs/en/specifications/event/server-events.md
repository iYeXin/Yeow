# Server Events

## `serverPing`

| Field        | Type   | Description |
|--------------|--------|-------------|
| `address`    | string | The source IP |
| `numPlayers` | number | The current number of online players |
| `maxPlayers` | number | The maximum number of players |
| `motd`       | string | The MOTD text |

**Callback write-back**: the handler can return or directly assign event fields (with multiple handlers, the last one wins; this only affects **that particular** ping response and does not change the server's persistent state):
- `{ "motd": "<text>" }` / `e.motd = ...` — overrides the MOTD for that ping response (parsed through Yeow text parsing: MiniMessage takes priority, falls back to legacy § formatting when § is present)
- `{ "icon": "<PNG base64>" }` — overrides the server list icon for that ping response (automatically scaled to 64×64; invalid images are ignored)
- `{ "maxPlayers": <number> }` — overrides the displayed maximum player count for that ping response. **Not recommended to modify** (only affects display, does not change the actual join limit)
- `{ "numPlayers": <number> }` — overrides the displayed online player count for that ping response. **Not recommended to modify** (disguising the online count may violate server list policies)

Paper 1.20.5+ removed the runtime `setServerIcon`, so the icon can only be modified in the ping event.

## `serverCommand`

| Field     | Type   | Description |
|-----------|--------|-------------|
| `command` | string | The full command string (including `/`) |
| `sender`  | string | The name of the executor (console or player name) |

## `playerResourcePackStatus`

| Field    | Type          | Description |
|----------|---------------|-------------|
| `player` | string (UUID) | The related player |
| `status` | string        | The status: `SUCCESSFULLY_LOADED` \| `DECLINED` \| `FAILED_DOWNLOAD` \| `ACCEPTED` |
| `hash`   | string        | The resource pack hash |
