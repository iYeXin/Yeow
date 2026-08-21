# BossBar API

A player-facing health bar (OOP object-based API).

```js
import { BossBar } from 'yeow-api';
```

## Creation

```js
const bar = await BossBar.create('<rainbow>Boss Title</rainbow>', {
    color: 'PURPLE',       // PINK | BLUE | RED | GREEN | YELLOW | PURPLE | WHITE
    style: 'SOLID',        // SOLID | SEGMENTED_6 | SEGMENTED_10 | SEGMENTED_12 | SEGMENTED_20
    progress: 0.75,        // 0.0 ~ 1.0
    visible: true,
});
```

## Methods / Properties

```js
// Methods (each has a Sync variant)
await bar.setTitle('<red>New Title</red>');
await bar.setProgress(0.5);
await bar.setColor('RED');
await bar.setStyle('SEGMENTED_6');
await bar.setVisible(false);

// Property sugar (synchronous writes)
bar.title = '<red>New Title</red>';
bar.progress = 0.5;
bar.color = 'RED';
bar.style = 'SEGMENTED_6';
bar.visible = false;

// Player binding (accepts a Player object or a uuid)
await bar.addPlayer(player);
await bar.addPlayer('uuid-...');
await bar.removePlayer(player);
await bar.removeAllPlayers();

// Flags
await bar.addFlag('CREATE_FOG');
await bar.removeFlag('DARKEN_SKY');

// Event comparison (handle id, e.g. compare against the inventoryClick event field)
const id = bar.toString();   // or bar.handle

// Destroy
await bar.destroy();
```

> For the BossBar color/style/flag value domains (BarColor / BarStyle / BarFlag), see [Value Appendix · Directly Maintained Enum List](../specifications/values.md#ii-directly-maintained-enum-listing).

## Example

```js
const bar = await BossBar.create('<red>Loading...</red>', {
    color: 'RED', progress: 0.0
});
await bar.addPlayer(player);

for (let i = 0; i <= 100; i += 10) {
    await bar.setProgress(i / 100);
    // wait a bit...
}
await bar.destroy();
```
