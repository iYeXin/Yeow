# Environment Info (Env)

Synchronously retrieves runtime environment information (via the `env` channel).

```js
import { getEnv } from 'yeow-api';

const env = getEnv();
console.log(env.arch, env.minecraftVersion, env.yeow.version);
```

Returns `EnvInfo`:

| Field             | Type                  | Description                                                                                        |
| ----------------- | --------------------- | -------------------------------------------------------------------------------------------------- |
| `cpus`            | number                | Number of logical CPU cores                                                                        |
| `memory`          | number                | Total JVM memory (in bytes)                                                                        |
| `arch`            | string                | System architecture (e.g. `windows-x64` / `linux-x64` / `linux-arm64`)                             |
| `minecraftVersion`| string                | Minecraft version (e.g. `1.21.4`)                                                                  |
| `yeow`            | { platform, version } | Runtime information (e.g. `{ platform: 'paper', version: '0.5.0' }`)                              |
| `timestamp`       | number                | Epoch milliseconds (fractional part is microseconds; microsecond precision)                        |
| `pluginDir`       | string                | Plugin data directory path (e.g. `plugins/my-plugin` on Paper/Folia; the main plugin directory in a Worker) |

> `timestamp` is in milliseconds with microsecond precision (the fractional part is microseconds).
