# 环境信息（Env）

同步获取运行时环境信息（`env` 通道）。

```js
import { getEnv } from 'yeow-api';

const env = getEnv();
console.log(env.arch, env.minecraftVersion, env.yeow.version);
```

返回 `EnvInfo`：

| 字段               | 类型                  | 说明                                                       |
| ------------------ | --------------------- | ---------------------------------------------------------- |
| `cpus`             | number                | CPU 逻辑核心数                                             |
| `memory`           | number                | JVM 总内存（字节）                                         |
| `arch`             | string                | 系统架构（如 `windows-x64` / `linux-x64` / `linux-arm64`） |
| `minecraftVersion` | string                | Minecraft 版本（如 `1.21.4`）                              |
| `yeow`             | { platform, version } | 运行时信息（如 `{ platform: 'paper', version: '0.1.0' }`） |
| `now`              | number                | **epoch 微秒**时间戳                                       |

> `now` 为微秒级时间戳——通信开销在微秒级，纳秒无意义。
