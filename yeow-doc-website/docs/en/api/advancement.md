# Advancement API

Grant, revoke, and query advancements (achievements). Operates on **`Player` instance methods**.

```js
const player = await Player.get('Notch');
await player.grantAdvancement('minecraft:story/mine_stone');
```

## Methods (Player instance methods)

```js
// Grant all criteria of an advancement
await player.grantAdvancement('minecraft:story/mine_stone');
await player.grantAdvancementSync('minecraft:story/mine_stone');

// Revoke an advancement
await player.revokeAdvancement('minecraft:story/mine_stone');

// Grant/revoke a specific criterion
await player.awardCriteria('minecraft:story/root', 'crafting_table');
await player.revokeCriteria('minecraft:story/root', 'crafting_table');

// Query advancement progress
const prog = await player.getAdvancementProgress('minecraft:story/root');
// → { awardedCriteria: ["crafting_table"], remainingCriteria: [] }
```

## Key format

Advancements use namespaced keys: `minecraft:story/root`, `minecraft:nether/root`, etc.

## Example

```js
const player = await Player.get('Notch');

// Grant the "Stone Age" advancement
await player.grantAdvancement('minecraft:story/mine_stone');

// Check completion
const p = await player.getAdvancementProgress('minecraft:story/root');
if (p && p.remainingCriteria.length === 0) {
    console.log('Root advancement complete!');
}
```
