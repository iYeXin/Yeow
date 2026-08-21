# Recipe Tasks

Recipe add, remove, query.

---

## `recipe.add`

Add a recipe.

**Request**: Choose the corresponding structure based on recipe type.

### Shaped Recipe

```json
{
  "type": "shaped",
  "key": "myplugin:custom_sword",
  "result": { "type": "minecraft:diamond_sword", "amount": 1 },
  "shape": ["AAA", "ABA", "AAA"],
  "ingredients": {
    "A": "minecraft:iron_ingot",
    "B": "minecraft:stick"
  },
  "group": "tools"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `shape` | string[] | Recipe shape, one string per row |
| `ingredients` | object | Character → item mapping |

### Shapeless Recipe

```json
{
  "type": "shapeless",
  "key": "yeow:test",
  "result": { "type": "minecraft:diamond", "amount": 3 },
  "ingredients": ["minecraft:dirt", "minecraft:stone"],
  "group": "test"
}
```

`ingredients` can be a plain string (Material) or an object `{ "type": "<material>", "amount": <int> }` (to specify quantity).

### Furnace Recipe (furnace / blast / smoker / campfire)

```json
{
  "type": "furnace",
  "key": "yeow:smelt_test",
  "input": "minecraft:iron_ore",
  "result": { "type": "minecraft:iron_ingot", "amount": 1 },
  "experience": 0.7,
  "cookingTime": 200
}
```

`type` options:

| type | Corresponding device |
|------|---------------------|
| `furnace` | Furnace |
| `blast` | Blast furnace |
| `smoker` | Smoker |
| `campfire` | Campfire |

**Returns**: `boolean` (whether recipe was added successfully)

---

## `recipe.remove`

- **Request**: `{ "key": "<key>" }`
- **Returns**: `true`

---

## `recipe.getForItem`

Query recipes that use the specified item as a result.

- **Request**: `{ "item": { "type": "<material>" } }`
- **Returns**: `string[]` (array of recipe keys)
