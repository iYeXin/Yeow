# Advancement 任务

进度（成就）授予/撤销/查询操作。

---

## 通用字段

所有任务中 `uuid` 为玩家 UUID，`key` 为进度命名空间 key（如 `minecraft:story/root`）。

---

| 任务 | 请求 | 返回 |
|------|------|------|
| `advancement.grant` | `{ "uuid": "<uuid>", "key": "<key>" }` | `boolean` |
| `advancement.revoke` | `{ "uuid": "<uuid>", "key": "<key>" }` | `boolean` |
| `advancement.awardCriteria` | `{ "uuid": "<uuid>", "key": "<key>", "criteria": "<criteria>" }` | `boolean` |
| `advancement.revokeCriteria` | `{ "uuid": "<uuid>", "key": "<key>", "criteria": "<criteria>" }` | `boolean` |

### `advancement.grant`

授予指定进度的所有条件，即便只缺少部分条件也会授予。

### `advancement.revoke`

撤销指定进度的所有条件。

### `advancement.awardCriteria` / `advancement.revokeCriteria`

针对某一具体条件进行授予/撤销。

---

## 查询

### `advancement.getProgress`

- **请求**：`{ "uuid": "<uuid>", "key": "<key>" }`
- **返回**：`{ "awardedCriteria": ["<c1>", "<c2>"], "remainingCriteria": ["<c3>"] }` \| `null`（进度不存在时为 `null`）
