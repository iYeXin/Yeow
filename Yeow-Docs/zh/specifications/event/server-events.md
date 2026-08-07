# Server 事件

## `serverPing`

| 字段 | 类型 | 说明 |
|------|------|------|
| `address` | string | 来源 IP |
| `numPlayers` | number | 当前在线玩家数 |
| `maxPlayers` | number | 最大玩家数 |
| `motd` | string | MOTD 文本 |

**回调回写**：handler 可返回（多个 handler 时以最后一个为准；仅对**该次** ping 响应生效，不改变服务器持久状态）：
- `{ "motd": "<text>" }` — 覆盖该次 ping 响应的 MOTD（经 Yeow 文本解析：MiniMessage 优先，含 § 时回退 legacy § 格式）
- `{ "icon": "<PNG base64>" }` — 覆盖该次 ping 响应的服务器列表图标（自动缩放至 64×64；无效图片忽略）
- `{ "maxPlayers": <number> }` — 覆盖该次 ping 响应显示的最大玩家数。**不建议修改**（仅影响显示，不改变实际进入限制）
- `{ "numPlayers": <number> }` — 覆盖该次 ping 响应显示的在线人数。**不建议修改**（伪装在线人数可能违反服务器列表政策）

Paper 1.20.5+ 移除了运行时 `setServerIcon`，图标只能在 ping 事件中修改。

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
