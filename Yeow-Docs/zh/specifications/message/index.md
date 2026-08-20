# Message 通道规范

## 概述

除 `task` 通道（通过调度器）外，Yeow 还存在其他非调度器通道。它们直接由插件线程处理，不受调度器时间片限制。

所有通道使用相同的 `$send(channel, payload)` 入口，`payload` 为包含操作名称和参数的对象。

---

## 通道列表

| 通道        | 说明                    | 规范文档                          |
| ----------- | ----------------------- | --------------------------------- |
| `task`      | 游戏任务（进入调度器）  | [task 模块规范](../task/index.md) |
| `timer`     | 定时器                  | [timer 通道](timer.md)            |
| `fs`        | 文件系统                | [fs 通道](fs.md)                  |
| `http`      | HTTP 客户端/服务端      | [http 通道](http.md)              |
| `assets`    | 插件的内置资源读取      | [assets 通道](assets.md)          |
| `lifecycle` | 生命周期确认 + 资源回收 | [lifecycle 通道](lifecycle.md)    |
| `log`       | 日志                    | [log 通道](log.md)                |
| `env`       | 运行时环境信息 + 微秒时间戳 | 见下方                         |
| `debug`     | 调试 / 错误上报 / Ping  | [debug 通道](debug.md)            |
| `service`   | 服务注册/请求/订阅/发布 | [service 通道](service.md)        |
| `util`      | gzip + UTF-8 ↔ 字节转换 | [util 通道](util.md)              |
| `worker`    | 虚拟插件（Worker）控制/消息 | [worker 通道](worker.md)        |

---

## 通用通道

### `env`

- **请求**：任意字符串（同步）
- **返回**：`object` — 环境信息 JSON：

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

- `cpus`：CPU 逻辑核心数；`memory`：JVM 总内存（字节）
- `arch`：系统架构（`<os>-<arch>`，如 `windows-x64`）
- `minecraftVersion`：Minecraft 版本；`yeow`：运行时信息（platform/version）
- `now`：**epoch 微秒**时间戳（通信开销在微秒级，纳秒无意义）
- `pluginDir`：**插件数据目录路径**（如 `plugins/<pluginName>`；原 `dir` 通道并入，Worker 中为主插件目录）

---

## 通用错误格式

各通道在执行失败时返回：

```json
{ "err": "<error message>" }
```
