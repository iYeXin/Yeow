# Recipe API

配方添加、移除和查询。

```js
import { addRecipe, removeRecipe, getRecipesForItem } from 'yeow-api';
```

## 有序配方 (shaped)

```js
await addRecipe({
    type: 'shaped',
    key: 'myplugin:custom_sword',
    result: { type: 'minecraft:diamond_sword', amount: 1 },
    shape: ['AAA', 'ABA', 'AAA'],
    ingredients: {
        A: 'minecraft:iron_ingot',
        B: 'minecraft:stick',
    },
    group: 'tools',
});
```

## 无序配方 (shapeless)

```js
await addRecipe({
    type: 'shapeless',
    key: 'yeow:diamond_from_dirt',
    result: { type: 'minecraft:diamond', amount: 3 },
    ingredients: ['minecraft:dirt', 'minecraft:stone'],
});
```

`ingredients` 可以为字符串（Material）或 `{ type, amount }` 对象。

## 熔炉配方

```js
// 熔炉
await addRecipe({
    type: 'furnace',
    key: 'yeow:smelt_test',
    input: 'minecraft:iron_ore',
    result: { type: 'minecraft:iron_ingot', amount: 1 },
    experience: 0.7,
    cookingTime: 200,  // tick
});

// 高炉 (blast) / 烟熏炉 (smoker) / 营火 (campfire)
await addRecipe({
    type: 'blast', key: 'yeow:blast_test', input: 'minecraft:iron_ore',
    result: { type: 'minecraft:iron_ingot', amount: 1 }, cookingTime: 100,
});
```

## 移除与查询

```js
await removeRecipe('myplugin:custom_sword');

const recipes = await getRecipesForItem({ type: 'minecraft:diamond' });
// → ["minecraft:diamond_block", "yeow:diamond_from_dirt", ...]
```
