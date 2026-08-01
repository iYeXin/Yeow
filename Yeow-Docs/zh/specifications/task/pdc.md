# PDC 任务

自定义持久化数据（Persistent Data Container）。pdc 通用键值存储，支持 Player、Entity、World、Block 四种持有者。

所有值类型为 `string`。

---

## 通用操作

以下任务适用于 Player 和 Entity（通过 `uuid` 寻址）。

| 任务 | 请求 | 返回 |
|------|------|------|
| `pdc.get` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `string` \| `null` |
| `pdc.set` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>", "value": "<value>" }` | `true` |
| `pdc.has` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `boolean` |
| `pdc.remove` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `true` |
| `pdc.keys` | `{ "uuid": "<uuid>" \| "world": "<name>" }` | `string[]` |

---

## 持有者寻址

运行时按以下优先级解析 `uuid` 字段：

1. **Player** — 按 UUID 查找在线玩家
2. **Entity** — 按 UUID 查找已加载实体
3. **World** — 如果存在 `world` 字段，按世界名查找

## Block 寻址

通过 `world` + `x` + `y` + `z` 字段联合寻址方块：

```json
{ "world": "<name>", "x": <int>, "y": <int>, "z": <int>, "key": "<key>" }
```

---

## Key 格式

Key 支持 `namespace:key` 格式（如 `yeow:mykey`），也支持纯字符串（运行时默认命名空间为 `yeow`）。key 在存储前自动转为小写，允许字符受限于 `[a-z0-9/._-]`。

```json
// 等价
{ "key": "yeow:mykey" }
{ "key": "mykey" }          // 解析为 yeow:mykey
{ "key": "MyPlugin.DeathLoc" }  // → yeow:myplugin.deathloc（自动转为小写）
```
