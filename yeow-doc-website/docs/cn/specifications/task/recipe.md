# Recipe 任务

配方添加、移除、查询。

---

## `recipe.add`

添加配方。

**请求**：根据配方类型选择对应结构。

### 有序配方（shaped）

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

| 字段 | 类型 | 说明 |
|------|------|------|
| `shape` | string[] | 配方形状，每行一个字符串 |
| `ingredients` | object | 字符 → 物品映射 |

### 无序配方（shapeless）

```json
{
  "type": "shapeless",
  "key": "yeow:test",
  "result": { "type": "minecraft:diamond", "amount": 3 },
  "ingredients": ["minecraft:dirt", "minecraft:stone"],
  "group": "test"
}
```

`ingredients` 可以为纯字符串（Material）或对象 `{ "type": "<material>", "amount": <int> }`（指定数量）。

### 熔炉配方（furnace / blast / smoker / campfire）

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

`type` 可选值：

| type | 对应设备 |
|------|---------|
| `furnace` | 熔炉 |
| `blast` | 高炉 |
| `smoker` | 烟熏炉 |
| `campfire` | 营火 |

**返回**：`boolean`（配方是否添加成功）

---

## `recipe.remove`

- **请求**：`{ "key": "<key>" }`
- **返回**：`true`

---

## `recipe.getForItem`

查询使用指定物品作为结果的配方。

- **请求**：`{ "item": { "type": "<material>" } }`
- **返回**：`string[]`（配方 key 数组）
