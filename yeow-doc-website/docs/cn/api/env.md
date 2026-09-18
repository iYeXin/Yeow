# 环境信息（Env）

同步获取运行时环境信息（`env` 通道）。

```js
import { getEnv } from 'yeow-api';

const env = getEnv();
console.log(env.arch, env.minecraftVersion, env.yeow.version);
```

返回 `EnvInfo`：

| 字段               | 类型                  | 说明                                                                              |
| ------------------ | --------------------- | --------------------------------------------------------------------------------- |
| `cpus`             | number                | CPU 逻辑核心数                                                                    |
| `memory`           | number                | JVM 总内存（字节）                                                                |
| `arch`             | string                | 系统架构（如 `windows-x64` / `linux-x64` / `linux-arm64`）                        |
| `minecraftVersion` | string                | Minecraft 版本（如 `1.21.4`）                                                     |
| `yeow`             | { platform, version } | 运行时信息（如 `{ platform: 'paper', version: '0.5.0' }`）                        |
| `timestamp`        | number                | epoch 毫秒时间戳（小数部分为微秒，微秒精度）                                      |
| `pluginDir`        | string                | 插件数据目录路径（Paper/Folia 平台如 `plugins/my-plugin`；Worker 中为主插件目录） |

> `timestamp` 单位毫秒、精度微秒（小数部分即微秒）。
