# Log API

Console logging. A `[PluginName]` prefix is added automatically by default.

```js
import { log, Logger } from 'yeow-api';
```

## log (module-level)

```ts
log.info('Server started');
log.warn('Low memory');
log.error('Failed to load config');
// Console output: [MyPlugin] Server started
```

The `[PluginName]` prefix is added automatically.

## Logger (custom prefix)

```ts
const dbLog = new Logger('[DB] ');
dbLog.info('Connected');        // → [DB] Connected

const raw = new Logger();       // no prefix
raw.info('bare message');
```

## console

`console.log / warn / error / info` output directly, and likewise get the `[PluginName]` prefix added automatically (provided by the runtime).

```ts
console.log('hello');       // → [MyPlugin] hello
console.warn('warning');
```
