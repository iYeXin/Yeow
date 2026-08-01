# BossBar API

```js
import { createBossBar, setBossBarTitle, setBossBarProgress, addBossBarPlayer,
    destroyBossBar, setBossBarColor, setBossBarStyle, setBossBarVisible,
    addBossBarFlag, removeBossBarFlag, removeBossBarPlayer, removeAllBossBarPlayers } from 'yeow-api';
```

## 创建

```js
const bar = await createBossBar('<rainbow>Boss Title</rainbow>', {
    color: 'PURPLE',       // PINK | BLUE | RED | GREEN | YELLOW | PURPLE | WHITE
    style: 'SOLID',        // SOLID | SEGMENTED_6 | SEGMENTED_10 | SEGMENTED_12 | SEGMENTED_20
    progress: 0.75,        // 0.0 ~ 1.0
    visible: true,
});
```

返回 `BossBarHandle`。

## 方法

```js
await setBossBarTitle(bar, '<red>New Title</red>');
await setBossBarProgress(bar, 0.5);
await setBossBarColor(bar, 'RED');
await setBossBarStyle(bar, 'SEGMENTED_6');
await setBossBarVisible(bar, false);

// 玩家绑定
await addBossBarPlayer(bar, player.uuid);
await removeBossBarPlayer(bar, player.uuid);
await removeAllBossBarPlayers(bar);

// Flag
await addBossBarFlag(bar, 'CREATE_FOG');
await removeBossBarFlag(bar, 'DARKEN_SKY');

// 销毁
await destroyBossBar(bar);
```

## 示例

```js
const bar = await createBossBar('<red>Loading...</red>', {
    color: 'RED', progress: 0.0
});
await addBossBarPlayer(bar, player.uuid);

for (let i = 0; i <= 100; i += 10) {
    await setBossBarProgress(bar, i / 100);
    // 等待一段时间...
}
await destroyBossBar(bar);
```
