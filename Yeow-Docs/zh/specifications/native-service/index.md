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

| 属性 | 类型 | 说明 |
|------|------|------|
| `message` | `string` | 错误描述（含退出码） |
| `exitCode` | `number` | 进程退出码 |
| `output` | `string` | stdout + stderr 合并输出 |

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

子进程退出时：
- 连接断开 → Yeow-Runtime 将该服务标记为不可用，之后请求返回 `{"err":"service not ready"}`
- 插件 unload / hot-reload → 子进程被 `destroyForcibly()` 终止

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
