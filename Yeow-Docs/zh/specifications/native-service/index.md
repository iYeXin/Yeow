# Native Service 协议

Yeow Native Service 是通过子进程启动的可执行文件，通过 TCP 与 Yeow-Runtime 通信，实现原生能力扩展（如机器学习、图像处理等）。

## 启动参数

可执行文件启动时接收两个命令行参数：

```
<executable> <yeowPort> <serviceId>
```

| 参数        | 说明                         |
| ----------- | ---------------------------- |
| `yeowPort`  | Yeow-Runtime 的 TCP 监听端口 |
| `serviceId` | 为此服务分配的唯一 ID        |

## 启动流程

```
1. Yeow-Runtime spawn 子进程: svc.exe <yeowPort> <serviceId>
2. 子进程启动后主动连接 TCP: connect("127.0.0.1", yeowPort)
3. 立即发送就绪消息
4. 开始处理请求
```

JS 端可通过 `registerNativeService` 返回的 `ready()` 方法等待就绪：

```js
import { registerNativeService } from 'yeow-api';
import { getAssetsPath } from 'yeow-dev';

const { serviceId, ready } = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});
await ready(); // Promise resolve 表示 TCP 连接已建立、就绪消息已收到
```

> **路径必须经 `getAssetsPath()` 解析**：构建时资源会获得命名空间前缀（如 `image-svc.exe` → `assets/a1b2c3d4/image-svc.exe`），硬编码原始路径在运行时找不到文件。

若进程在发送就绪消息前异常退出，`ready()` 会 reject。Error 对象包含：

| 属性       | 类型     | 说明                     |
| ---------- | -------- | ------------------------ |
| `message`  | `string` | 错误描述（含退出码）     |
| `exitCode` | `number` | 进程退出码               |
| `output`   | `string` | stdout + stderr 合并输出 |

```js
try {
    await ready();
} catch (e) {
    console.error(e.message);   // "Native service image-svc exited with code 1"
    console.error(e.exitCode);  // 1
    console.error(e.output);    // "error: cannot load library ...\nat main.go:42\n"
}
```

## 通信协议

TCP 连接上使用 **JSON line** 协议（每行一个完整 JSON 对象，`\n` 分隔）。Yeow-Runtime 为服务端（被动监听），子进程为客户端（主动连接）。实现应确保读缓冲区足够容纳最大预期行大小（如 Go 的 `bufio.Scanner` 默认 64KB，需自行扩容以支持 base64 编码等大载荷）。

收到的 JSON 对象中，应为 `int` 的 `number` 不保证为 `int`，建议使用**浮点型**接收并手动转换。

### 1. 就绪消息 (child → runtime)

子进程就绪后必须立即发送：

```json
{"type":"ready","serviceId":"mySvc_a1b2","servicePort":12345}
```

| 字段          | 说明                                     |
| ------------- | ---------------------------------------- |
| `serviceId`   | 与启动参数一致的 serviceId               |
| `servicePort` | 子进程内部监听的端口（预留，当前未使用） |

### 2. 请求 (runtime → child)

Yeow 插件调用服务请求时：

```json
{"type":"request","requestId":"svcreq_1","path":"/api/process","body":{"key":"value"}}
```

| 字段        | 说明                        |
| ----------- | --------------------------- |
| `requestId` | 请求唯一 ID，响应时必须回传 |
| `path`      | 请求路径                    |
| `body`      | 请求体（JSON 对象）         |

### 3. 响应 (child → runtime)

```json
{"type":"response","requestId":"svcreq_1","body":{"result":"ok"}}
```

| 字段        | 说明                |
| ----------- | ------------------- |
| `requestId` | 与请求完全一致的 ID |
| `body`      | 响应体（JSON 对象） |

### 4. 发布事件 (child → runtime)

```json
{"type":"publish","eventPath":"status","body":{"health":0.95}}
```

| 字段        | 说明                |
| ----------- | ------------------- |
| `eventPath` | 事件路径            |
| `body`      | 事件体（JSON 对象） |

### 5. 关闭 (runtime → child)

运行时停止服务时（插件卸载 / hot-reload / 运行时关闭）推送：

```json
{"type":"shutdown","reason":"unregistered"}
```

| 字段     | 说明                                             |
| -------- | ------------------------------------------------ |
| `reason` | `unregistered`（卸载）/ `shutdown`（运行时关闭） |

子进程收到后应**自行进行资源清理**（关闭文件、刷新持久化、停止内部线程）并退出进程——运行时通过进程退出作为完成信号；等待 3 秒未退出则 `destroy()`，再等 3 秒仍未退出则 `destroyForcibly()` 强制终止。

## 发现与通信拓扑

```
Yeow-Runtime (TCP 服务端)
  ↑ connect
  │
  ├─ svc1.exe ── TCP ──→ accepts requests, sends responses, publishes events
  ├─ svc2.exe ── TCP ──→ same
  └─ ...
```

Yeow-Runtime 是多路复用中转站：插件通过 `request` / `subscribe` / `publish` 与 Native Service 交互，运行时负责转发。单个 TCP 服务端处理所有 Native Service 连接（通过 `serviceId` 字段区分）。

## 退出

- 子进程退出时：
  - 连接断开 → Yeow-Runtime 将该服务标记为不可用，之后请求返回 `{"err":"service not ready"}`
  - 插件 unload / hot-reload → 运行时推送 `shutdown` 消息，子进程自行清理后退出（最多 6 秒等待，超时 `destroyForcibly()` 强制终止）

## 打包与部署

可执行文件放置在插件的 `assets/` 目录下。注册时通过 `platforms` 参数指定各平台配置：
**单文件模式（file/string）：**
```json
{ "windows": "native/win/my-svc.exe" }
```

**目录+入口模式（dir+entry）：**
```json
{ "windows": { "dir": "native/win/", "entry": "start.ps1" } }
```
此模式下 `dir` 指定的目录下所有文件被提取到临时目录，然后运行 `entry` 文件。
适用于多文件依赖的复杂原生服务（如 Python 脚本、Node.js 项目）。

**提取目录：`<TEMP>/yeow-native-services/<serviceId>/`**
- 每次 Runtime 启动时自动清理该目录
- 插件热重载时自动清理并重新提取

## 可信性声明与批准（SHA-256）

插件或依赖包可在 `yeow.config.json` 声明 `native` 字段固定二进制哈希（构建时计算打包后路径的 SHA-256 写入 `yeow.json` 的 `native` 字段）。**声明只对单文件模式有效**（`string` / `{file}`）；目录模式（`{dir, entry}`）暂不支持。

**批准（插件加载层）**：默认情况下（`native-service-require-approval: true`）全部原生服务视为不安全。声明了原生服务的插件**加载时被拒绝**——控制台打印醒目的提示信息，含一次性批准码（6 位 36 进制，仅控制台可见；插件未加载，无法预知 code 或自动批准）：管理员执行 `/yeow approve <code>` 后**自动加载**该插件。

**哈希校验（运行时）**：插件加载后，注册原生服务时校验所选二进制（单文件模式）SHA-256：与声明不一致 → **拒绝加载**（`ready()` reject，错误含声明/实际哈希——可执行文件可能被篡改）。

**配置与批准持久化**：

- `config.yml` 的 `native-service-require-approval` 为**信任源**——运行时直接修改即生效
- 文件位于 `plugins/Yeow/runtime/`（`config.yml`、`approve.json`）——该目录受 fs 写保护，插件无法通过 fs API 修改

未声明/未批准时照常打印风险日志（视为不可信）。未来 Yeow 官方或社区可能维护一份已知安全 SHA-256 列表：命中列表的二进制在插件发布时可能被标记为安全，加载时无提醒。
