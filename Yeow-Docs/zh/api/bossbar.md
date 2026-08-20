# BossBar API

面向玩家的血条（OOP 对象式 API）。

```js
import { BossBar } from 'yeow-api';
```

## 创建

```js
const bar = await BossBar.create('<rainbow>Boss Title</rainbow>', {
    color: 'PURPLE',       // PINK | BLUE | RED | GREEN | YELLOW | PURPLE | WHITE
    style: 'SOLID',        // SOLID | SEGMENTED_6 | SEGMENTED_10 | SEGMENTED_12 | SEGMENTED_20
    progress: 0.75,        // 0.0 ~ 1.0
    visible: true,
});
```

## 方法 / 属性

```js
// 方法（均有 Sync 变体）
await bar.setTitle('<red>New Title</red>');
await bar.setProgress(0.5);
await bar.setColor('RED');
await bar.setStyle('SEGMENTED_6');
await bar.setVisible(false);

// 属性糖（同步写）
bar.title = '<red>New Title</red>';
bar.progress = 0.5;
bar.color = 'RED';
bar.style = 'SEGMENTED_6';
bar.visible = false;

// 玩家绑定（接受 Player 对象或 uuid）
await bar.addPlayer(player);
await bar.addPlayer('uuid-...');
await bar.removePlayer(player);
await bar.removeAllPlayers();

// Flag
await bar.addFlag('CREATE_FOG');
await bar.removeFlag('DARKEN_SKY');

// 事件比对（句柄 id，如与 inventoryClick 事件字段比对）
const id = bar.toString();   // 或 bar.handle

// 销毁
await bar.destroy();
```

> BossBar 颜色/样式/Flag 取值域（BarColor / BarStyle / BarFlag）见 [值域附录 · 直接维护的枚举清单](../specifications/values.md#二直接维护的枚举清单)。

## 示例

```js
const bar = await BossBar.create('<red>Loading...</red>', {
    color: 'RED', progress: 0.0
});
await bar.addPlayer(player);

for (let i = 0; i <= 100; i += 10) {
    await bar.setProgress(i / 100);
    // 等待一段时间...
}
await bar.destroy();
```
