# BossBar Tasks

BossBar creation, attribute modification, and player binding. All tasks are sent through the `task` channel, and `id` is the resource identifier assigned at creation time.

### Resource Lifecycle

Created by the plugin via `bossbar.create`, explicitly destroyed via `bossbar.destroy`, or automatically reclaimed via [gc-collect](../message/lifecycle.md#gc-collect).

---

## Creation and Destruction

### `bossbar.create`

- **Request**: `{ "id": "<handle>", "title": "<text>", "color": "<color>", "style": "<style>", "progress": <double>, "visible": <bool> }`
- **Return**: `string` (id)

| Field | Required | Default | Description |
|------|------|------|------|
| `title` | Yes | — | Title text (MiniMessage / `§` format) |
| `color` | No | `"PURPLE"` | Color: `PINK`, `BLUE`, `RED`, `GREEN`, `YELLOW`, `PURPLE`, `WHITE` |
| `style` | No | `"SOLID"` | Style: `SOLID`, `SEGMENTED_6`, `SEGMENTED_10`, `SEGMENTED_12`, `SEGMENTED_20` |
| `progress` | No | `1.0` | Progress value (0.0 ~ 1.0) |
| `visible` | No | `true` | Whether it is visible |

### `bossbar.destroy`

- **Request**: `{ "id": "<handle>" }`
- **Return**: `true`

---

## Attribute Modification

| Task | Request | Return |
|------|------|------|
| `bossbar.setTitle` | `{ "id": "<handle>", "title": "<text>" }` | `true` |
| `bossbar.setProgress` | `{ "id": "<handle>", "progress": <double> }` | `true` |
| `bossbar.setColor` | `{ "id": "<handle>", "color": "<color>" }` | `true` |
| `bossbar.setStyle` | `{ "id": "<handle>", "style": "<style>" }` | `true` |
| `bossbar.setVisible` | `{ "id": "<handle>", "visible": <bool> }` | `true` |

---

## Player Binding

| Task | Request | Return |
|------|------|------|
| `bossbar.addPlayer` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `bossbar.removePlayer` | `{ "id": "<handle>", "uuid": "<uuid>" }` | `true` |
| `bossbar.removeAll` | `{ "id": "<handle>" }` | `true` |

---

## Flag

| Task | Request | Return |
|------|------|------|
| `bossbar.addFlag` | `{ "id": "<handle>", "flag": "<flag>" }` | `true` |
| `bossbar.removeFlag` | `{ "id": "<handle>", "flag": "<flag>" }` | `true` |

`flag` possible values: `CREATE_FOG`, `DARKEN_SKY`, `PLAY_BOSS_MUSIC`.

> For the complete list of value ranges involved (color/style/flag), see the [Values Appendix](../values.md).
