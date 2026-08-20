# BossBar 任务

BossBar 创建、属性修改、玩家绑定。所有任务通过 `task` 通道发送，`id` 为创建时分配的资源标识符。

### 资源生命周期

由插件通过 `bossbar.create` 创建，通过 `bossbar.destroy` 显式销毁，或通过 [gc-collect](../message/lifecycle.md#gc-collect) 自动回收。

---

## 创建与销毁

### `bossbar.create`

- **请求**：`{ "id": "<handle>", "title": "<text>", "color": "<color>", "style": "<style>", "progress": <double>, "visible": <bool> }`
- **返回**：`string` (id)

| 字段 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `title` | 是 | — | 标题文本（MiniMessage / `§` 格式） |
| `color` | 否 | `"PURPLE"` | 颜色：`PINK`、`BLUE`、`RED`、`GREEN`、`YELLOW`、`PURPLE`、`WHITE` |
| `style` | 否 | `"SOLID"` | 样式：`SOLID`、`SEGMENTED_6`、`SEGMENTED_10`、`SEGMENTED_12`、`SEGMENTED_20` |
| `progress` | 否 | `1.0` | 进度值（0.0 ~ 1.0） |
| `visible` | 否 | `true` | 是否可见 |

### `bossbar.destroy`

- **请求**：`{ "id": "<handle>" }`
- **返回**：`true`

---

## 属性修改

| 任务 | 请求 | 返回 |
|------|------|------|
| `bossbar.setTitle` | `{ "id": "<handle>", "title": "<text>" }` | `true` |
| `bossbar.setProgress` | `{ "id": "<handle>", "progress": <double> }` | `true` |
| `bossbar.setColor` | `{ "id": "<handle>", "color": "<color>" }` | `true` |
| `bossbar.setStyle` | `{ "id": "<handle>", "style": "<style>" }` | `true` |
| `bossbar.setVisible` | `{ "id": "<handle>", "visible": <bool> }` | `true` |

---

## 玩家绑定

| 任务 | 请求 | 返回 |
|------|------|------|
| `bossbar.addPlayer` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `bossbar.removePlayer` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `bossbar.removeAll` | `{ "id": "<handle>" }` | `true` |

---

## Flag

| 任务 | 请求 | 返回 |
|------|------|------|
| `bossbar.addFlag` | `{ "id": "<handle>", "flag": "<flag>" }` | `true` |
| `bossbar.removeFlag` | `{ "id": "<handle>", "flag": "<flag>" }` | `true` |

`flag` 可选值：`CREATE_FOG`、`DARKEN_SKY`、`PLAY_BOSS_MUSIC`。

> 涉及值域（color/style/flag）的完整清单见 [值域附录](../values.md)。
