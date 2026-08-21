# Recipe API

Adding, removing, and querying recipes.

```js
import { addRecipe, removeRecipe, getRecipesForItem } from 'yeow-api';
```

## Shaped recipes (shaped)

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

## Shapeless recipes (shapeless)

```js
await addRecipe({
    type: 'shapeless',
    key: 'yeow:diamond_from_dirt',
    result: { type: 'minecraft:diamond', amount: 3 },
    ingredients: ['minecraft:dirt', 'minecraft:stone'],
});
```

`ingredients` can be a string (Material) or a `{ type, amount }` object.

## Furnace recipes

```js
// Furnace
await addRecipe({
    type: 'furnace',
    key: 'yeow:smelt_test',
    input: 'minecraft:iron_ore',
    result: { type: 'minecraft:iron_ingot', amount: 1 },
    experience: 0.7,
    cookingTime: 200,  // tick
});

// Blast furnace (blast) / smoker / campfire
await addRecipe({
    type: 'blast', key: 'yeow:blast_test', input: 'minecraft:iron_ore',
    result: { type: 'minecraft:iron_ingot', amount: 1 }, cookingTime: 100,
});
```

## Remove and query

```js
await removeRecipe('myplugin:custom_sword');

const recipes = await getRecipesForItem({ type: 'minecraft:diamond' });
// → ["minecraft:diamond_block", "yeow:diamond_from_dirt", ...]
```
