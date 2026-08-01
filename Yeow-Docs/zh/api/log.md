# Log API

控制台日志。默认自动添加 `[插件名]` 前缀。

```js
import { log, Logger } from 'yeow-api';
```

## log（模块级）

```ts
log.info('Server started');
log.warn('Low memory');
log.error('Failed to load config');
// 控制台输出: [MyPlugin] Server started
```

自动添加 `[插件名]` 前缀。

## Logger（自定义前缀）

```ts
const dbLog = new Logger('[DB] ');
dbLog.info('Connected');        // → [DB] Connected

const raw = new Logger();       // 无前缀
raw.info('bare message');
```

## console

`console.log / warn / error / info` 直接输出，同样自动添加 `[插件名]` 前缀（由运行时提供）。

```ts
console.log('hello');       // → [MyPlugin] hello
console.warn('warning');
```
