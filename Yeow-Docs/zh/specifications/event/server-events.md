# Server 事件

## `serverPing`

| 字段 | 类型 | 说明 |
|------|------|------|
| `address` | string | 来源 IP |
| `numPlayers` | number | 当前在线玩家数 |
| `maxPlayers` | number | 最大玩家数 |
| `motd` | string | MOTD 文本 |

## `serverCommand`

| 字段 | 类型 | 说明 |
|------|------|------|
| `command` | string | 完整命令字符串（含 `/`） |
| `sender` | string | 执行者名称（控制台或玩家名） |

## `playerResourcePackStatus`

| 字段 | 类型 | 说明 |
|------|------|------|
| `player` | string (UUID) | 相关玩家 |
| `status` | string | 状态：`SUCCESSFULLY_LOADED` \| `DECLINED` \| `FAILED_DOWNLOAD` \| `ACCEPTED` |
| `hash` | string | 资源包哈希 |
