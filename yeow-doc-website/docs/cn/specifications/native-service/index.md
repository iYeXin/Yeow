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

**工作目录**：子进程的默认工作目录为**服务器根目录**（Java 进程工作目录）——子进程可用相对路径直接读写服务器文件（如 `config.yml`、`plugins/` 下文件）；二进制本身被提取到临时目录运行，不影响工作目录语义。

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

const svc = await registerNativeService('image-svc', {
    windows: getAssetsPath('image-svc.exe'),
});
await svc.ready(); // Promise resolve 表示 TCP 连接已建立、就绪消息已收到
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
    await svc.ready();
} catch (e) {
    console.error(e.message);   // "Native service image-svc exited with code 1"
    console.error(e.exitCode);  // 1
    console.error(e.output);    // "error: cannot load library ...\nat main.go:42\n"
}
```

## 通信协议

TCP 连接使用**帧协议**（破坏性替换旧的 JSON line）：

```
[u32 headerLen][header JSON (UTF-8)] [u32 chunkLen][chunk bytes] ... [u32 0]
```

- **header**：JSON，承载类型与元数据（`type` / `id` / `path` / `headers` / `contentType` / `eventPath` / `reason` 等）
- **body**：0 结尾的分块序列，承载**原始字节**（无 base64），支持流式发送（无需预先知道总长）；空 body 即单个 0 长度块
- 所有长度为**大端 u32**；header ≤ 64 KiB、body ≤ 256 MiB（超限拒绝并断开连接）

Yeow-Runtime 为服务端（被动监听），子进程为客户端（主动连接）。JSON 中的 `number` 不保证为 `int`，建议按浮点接收并手动转换。

### 1. 就绪消息 (child → runtime)

子进程就绪后必须立即发送（header JSON + 空 body）：

```json
{"type":"ready","serviceId":"mySvc_a1b2","servicePort":12345}
```

| 字段          | 说明                                     |
| ------------- | ---------------------------------------- |
| `serviceId`   | 与启动参数一致的 serviceId               |
| `servicePort` | 子进程内部监听的端口（预留，当前未使用） |

### 2. 请求 (runtime → child)

Yeow 插件调用服务请求时（header JSON，随后紧跟 body 分块）：

```json
{"type":"request","id":"svcreq_1","path":"/api/process","headers":{"content-type":"application/json"},"contentType":"application/json"}
```

| 字段          | 说明                                                         |
| ------------- | ------------------------------------------------------------ |
| `id`          | 请求唯一 ID，响应时必须回传                                  |
| `path`        | 请求路径                                                     |
| `headers`     | app 级请求头键值对（`content-type` 为典型项）                |
| `contentType` | `application/json`（默认）或 `application/octet-stream`（原始二进制）；是 `headers['content-type']` 的便捷别名 |

body 为原始字节：JSON 请求即对象序列化后的 UTF-8 文本；二进制请求由 JS 侧以 base64 承载、运行时解码后**原样转发**（子进程始终拿到原始字节）。

### 3. 响应 (child → runtime)

```json
{"type":"response","id":"svcreq_1","headers":{"content-type":"application/json"},"contentType":"application/json"}
```
（其后紧跟 body 分块）

| 字段          | 说明                                                         |
| ------------- | ------------------------------------------------------------ |
| `id`          | 与请求完全一致的 ID                                          |
| `headers`     | app 级响应头键值对（`content-type` 为典型项）                |
| `contentType` | 响应体类型；JS 侧 `resp.json()` 解析 JSON，`resp.bytes()` 取二进制；是 `headers['content-type']` 的便捷别名 |

运行时把响应体（原始字节 + headers + contentType）交给消费者，JS 侧以 fetch 风格 `ServiceResponse` 消费。

### 4. 发布事件 (child → runtime)

```json
{"type":"publish","eventPath":"status"}
```
（其后紧跟 JSON body 分块）

| 字段        | 说明                       |
| ----------- | -------------------------- |
| `eventPath` | 事件路径                   |
| body        | 事件体（JSON 对象，UTF-8） |

事件分发语义不变：运行时解析 JSON body 后投递给匹配 `eventPath` 的订阅者。

### 5. 关闭 (runtime → child)

运行时停止服务时（插件卸载 / hot-reload / 运行时关闭）推送（header JSON + 空 body）：

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

可执行文件放置在插件的 `assets/` 目录下。注册时通过 `platforms` 参数指定各平台配置，**仅支持单文件模式**：

```json
{ "windows": "native/win/my-svc.exe" }
```

或等价的对象形式 `{ "windows": { "file": "native/win/my-svc.exe" } }`。目录模式（`{dir, entry}`）已移除——原生二进制需**自包含**（静态链接，或把依赖打进单一可执行文件）。

**提取目录：`<TEMP>/yeow-native-services/<serviceId>/`**
- 每次 Runtime 启动时自动清理该目录
- 插件热重载时自动清理并重新提取

## 强制声明与校验（SHA-256）

插件或依赖包**必须**在 `yeow.config.json` 声明 `native` 字段固定二进制哈希（构建时遍历主项目 + 依赖包，计算打包后路径的 SHA-256 合并写入 `yeow.json` 的 `native` 字段；加载插件时作为元数据保存）。**仅支持单文件模式**（`string` / `{file}`）。

**强制声明（构建 + 加载层）**：申请了 `service:registerNative` 权限的插件，其合并后的 `native` 清单不得为空——构建时为空则**构建失败**（声明的文件缺失同样失败），加载时为空则**拒绝加载**。不申请该权限的插件无需声明。

**注册校验（运行时，始终执行）**：注册原生服务时校验三项——serviceId 已声明、二进制路径已声明、SHA-256 匹配；缺一即**拒绝注册**（`ready()` reject，错误指明未声明或哈希不符——可执行文件可能被篡改）。

**不可信开关（插件加载层）**：**声明 ≠ 可信**。默认（`native-service-allow-untrusted: true`）声明了原生服务的插件正常加载，但控制台打印醒目的不可信警告；`false` 时申请该权限的插件**加载时被拒绝**（控制台指引改回 `true`）。

**配置持久化**：

- `config.yml` 的 `native-service-allow-untrusted`——运行时读取生效；已有配置缺失该字段时加载时自动合并默认并写回（平滑升级）
- 文件位于 `plugins/Yeow/runtime/`（`config.yml`）——该目录受 fs 写保护，插件无法通过 fs API 修改

无论是否声明，加载/注册时照常打印风险日志（视为不可信）。在线安全性校验（比对官方维护的安全清单）能力已在规划中：届时命中清单的二进制加载时不再警告。
