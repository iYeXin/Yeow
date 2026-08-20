# PDC 任务

自定义持久化数据（Persistent Data Container）。pdc 通用键值存储，支持 Player、Entity、World、Block 四种持有者。

所有值类型为 `string`（JSON 序列化由 yeow-api 层自动处理）。

---

## 通用操作

以下任务适用于 Player 和 Entity（通过 `uuid` 寻址）。

| 任务 | 请求 | 返回 |
|------|------|------|
| `pdc.get` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `string` \| `null` |
| `pdc.set` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>", "value": "<value>" }` | `true` |
| `pdc.has` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `boolean` |
| `pdc.remove` | `{ "uuid": "<uuid>" \| "world": "<name>", "key": "<key>" }` | `true` |
| `pdc.keys` | `{ "uuid": "<uuid>" \| "world": "<name>" }` | `string[]`（完整 key 格式，含命名空间） |
| `pdc.getAll` | `{ "uuid": "<uuid>" \| "world": "<name>" }` | `{ "<key>": "<value>" }`（**本插件命名空间**的键值，key 不含命名空间） |

> `pdc.getAll`（2026-08-13）：仅返回当前插件命名空间的键——配合 `_plugin` 归属注入，避免跨插件数据串扰。

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

Key 支持 `namespace:key` 格式（如 `myplugin:mykey`）。**纯字符串（无冒号）默认使用插件命名空间**（任务参数 `_plugin`，运行时注入）——不同插件的裸 key 互不冲突；`_plugin` 缺失时回退 `yeow`。key 在存储前自动转为小写，允许字符受限于 `[a-z0-9/._-]`。

```json
// 插件 folia-test 场景下的等价关系：
{ "key": "myplugin:mykey" }    // 显式命名空间
{ "key": "mykey" }             // → <插件名>:mykey（如 folia-test:mykey）
{ "key": "MyPlugin.DeathLoc" } // → <插件名>:myplugin.deathloc（自动转为小写）

// 旧数据兼容：历史版本默认 yeow 命名空间，迁移读取：
{ "key": "yeow:mykey" }
```
