# Advancement Tasks

Grant/revoke/query operations for advancements (achievements).

---

## Common Fields

In all tasks, `uuid` is the player UUID and `key` is the advancement namespace key (e.g. `minecraft:story/root`).

---

| Task | Request | Return |
|------|------|------|
| `advancement.grant` | `{ "uuid": "<uuid>", "key": "<key>" }` | `boolean` |
| `advancement.revoke` | `{ "uuid": "<uuid>", "key": "<key>" }` | `boolean` |
| `advancement.awardCriteria` | `{ "uuid": "<uuid>", "key": "<key>", "criteria": "<criteria>" }` | `boolean` |
| `advancement.revokeCriteria` | `{ "uuid": "<uuid>", "key": "<key>", "criteria": "<criteria>" }` | `boolean` |

### `advancement.grant`

Grants all criteria of the specified advancement, even if only some criteria are missing.

### `advancement.revoke`

Revokes all criteria of the specified advancement.

### `advancement.awardCriteria` / `advancement.revokeCriteria`

Grants/revokes a specific single criterion.

---

## Query

### `advancement.getProgress`

- **Request**: `{ "uuid": "<uuid>", "key": "<key>" }`
- **Return**: `{ "awardedCriteria": ["<c1>", "<c2>"], "remainingCriteria": ["<c3>"] }` \| `null` (`null` when the advancement does not exist)
