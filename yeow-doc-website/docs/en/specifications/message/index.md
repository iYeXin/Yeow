# Message Channel Specification

## Overview

In addition to the `task` channel (which goes through the scheduler), Yeow also provides other non-scheduler channels. They are handled directly by the plugin thread and are not limited by scheduler time slices.

All channels use the same `$send(channel, payload)` entry point, where `payload` is an object containing the operation name and its arguments.

---

## Channel List

| Channel        | Description                                | Specification                          |
| -------------- | ------------------------------------------ | -------------------------------------- |
| `task`         | Game tasks (enters the scheduler)          | [task module specification](../task/index.md) |
| `timer`        | Timers                                     | [timer channel](timer.md)              |
| `fs`           | File system                                | [fs channel](fs.md)                    |
| `http`         | HTTP client / server                       | [http channel](http.md)                |
| `assets`       | Reading the plugin's built-in assets       | [assets channel](assets.md)            |
| `lifecycle`    | Lifecycle acknowledgement + resource reclamation | [lifecycle channel](lifecycle.md) |
| `log`          | Logging                                    | [log channel](log.md)                  |
| `env`          | Runtime environment info + microsecond timestamps | See below                        |
| `debug`        | Debugging / error reporting / Ping         | [debug channel](debug.md)              |
| `service`      | Service registration / request / subscribe / publish | [service channel](service.md) |
| `util`         | gzip + UTF-8 ↔ byte conversion             | [util channel](util.md)                |
| `worker`       | Virtual plugin (Worker) control / messaging | [worker channel](worker.md)            |

---

## Common Channels

### `env`

- **Request**: any string (synchronous)
- **Return**: `object` — environment info JSON:

```json
{
  "cpus": 16,
  "memory": 17179869184,
  "arch": "windows-x64",
  "minecraftVersion": "1.21.4",
  "yeow": { "platform": "paper", "version": "0.5.0" },
  "now": 1723100000000000,
  "pluginDir": "plugins/my-plugin"
}
```

- `cpus`: number of CPU logical cores; `memory`: JVM total memory (bytes)
- `arch`: system architecture (`<os>-<arch>`, e.g. `windows-x64`)
- `minecraftVersion`: Minecraft version; `yeow`: runtime info (platform/version)
- `now`: **epoch microseconds** timestamp (communication overhead is on the microsecond scale, so nanoseconds are meaningless)
- `pluginDir`: **plugin data directory path** (e.g. `plugins/<pluginName>`; the former `dir` channel has been merged in; in a Worker it is the main plugin directory)

---

## Common Error Format

When a channel fails to execute, it returns:

```json
{ "err": "<error message>" }
```
