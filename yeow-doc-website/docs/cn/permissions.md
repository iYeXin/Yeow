# 权限与原生服务可信性

> 插件开发者视角的**安全主题完整参考**。快速上手只需知道：**只读写自己插件数据目录时无需声明任何权限**；确需敏感能力（服务器文件、HTTP、原生进程、解压资源）时在此声明。平台规范（实现者视角）的权限模型见 [平台规范 · 权限模型](specifications/README.md#权限模型)。

## 一、声明式权限

Yeow 对**敏感消息节点**实施声明式权限：插件在 `yeow.config.json` 的 `permissions` 中声明所需权限（构建时自动计算最终权限并写入 `yeow.json`，运行时读取并**固定**——加载后不可变更）：

```json
{
    "name": "my-plugin",
    "permissions": [
        "fs:server.*",
        "http:requestAsync",
        "service:registerNative"
    ]
}
```

### 默认需要声明（未声明则调用返回错误）

| 权限节点                     | 覆盖范围                                                                                             |
| ---------------------------- | ---------------------------------------------------------------------------------------------------- |
| `fs:server.*` / `fs:outer.*` | fs 通道 `server` / `outer` 前缀节点（服务器根 / 任意路径）；`fs:plugin.*` 节点（插件数据目录）**免声明** |
| `http:*`                     | HTTP 全部操作（`http:request`、`http:requestAsync`、`http:listen`、`http:respond`、`http:close`） |
| `service:registerNative`     | 注册原生服务（spawn 子进程）                                                                         |

> **assets 通道不设权限拦截**：`assets` 只读打包资源，或解压到**本插件数据目录**内（目标强制限定，越界返回错误），因此无需声明权限。

> **节点概念**：权限只按**消息节点**考虑（如 `fs:plugin.readFile`、`fs:server.readFile`）。节点名中的段（`plugin` / `server` / `outer`、`task:player.get` 的 `player`）是业务/访问范围命名，**不是层级**——权限匹配不看命名段含义。

### 粒度规则

- **节点级**：声明 `fs:server.readFile` 只授予该节点，其他 fs 节点仍被拒绝
- **整组通配**：声明 `fs:server.*` 授予 `server` 前缀全部节点
- **通道通配**：声明 `fs:*` 授予整个 fs 通道（含 server/outer）
- 未声明而调用 → 返回错误（`Permission denied: <node>`），异步 API 以 Promise reject 呈现
- 其余消息节点（如 `service:request`、`assets:read`）默认允许，无需声明

> **⚠ 权限建议**：直接声明 `fs:*` 是**危险且不专业的**。只读写插件自己的配置文件时**无需声明任何 fs 权限**（`fs:plugin.*` 节点默认允许）。确需访问服务器文件时，**尽可能精确声明**（如 `fs:server.readFile`、`fs:outer.systemPaths`），而非整组或通道通配。

> [!WARNING]
> 全局 `fetch` 底层依赖 `http:requestAsync` —— 未声明 http 权限时 `fetch` 会返回 `Permission denied: http:requestAsync`。使用 `fetch` / `request` 前请确保声明了 `"http:*"` 或 `"http:requestAsync"`。

> [!WARNING]
> **HTTP 服务器需要 `http:listen` + `http:respond` 两个节点**——只声明 `http:listen` 而漏掉 `http:respond` 时，服务器能启动、请求能到达回调，但 `respond` 被拒绝 → 响应永不发送 → **客户端请求挂起超时**（curl 超时 / CLOSE_WAIT，服务端日志无异常）。声明 `"http:*"`，或同时声明 `"http:listen"` 与 `"http:respond"`。

> 修改 `permissions` 后需重新构建——开发模式热重载会**一并重新加载权限**（构建时 `computedPermissions` 随热重载消息刷新）；生产环境需完整重载插件（`/yeow reload` 或重启服务器）。

> **统一门控**：所有消息节点（`channel:op`）统一过权限门控；`task:*` 默认拥有（无需声明）。Worker 可在 `createWorker` 时用 `permissions.allow` / `permissions.deny` 收紧（详见 [Worker API · 权限覆盖](api/worker.md)）：默认继承主插件权限，`deny` 优先级最高，`allow` 为白名单且**不可提权**（主插件未声明的权限，`allow` 无效）。

### 最终权限（computedPermissions）

构建时自动合并主项目与依赖包的声明（去重 + 通配归一化：`fs:*` 覆盖 `fs:server.*`、`fs:server.readFile` 等；`fs:server.*` 覆盖 `fs:server.readFile`），结果回写到 `yeow.config.json` 的 `computedPermissions` 字段并打包进 `yeow.json`。声明 `fs:*` 会被**自动展开**为 `fs:outer.*, fs:server.*`（语义等价，让服主对影响范围有明确感知）。

可用 `npm run permissions` 查看计算过程与每个权限的来源分布（来自哪个包）：

```
── Permissions by source ─────────────────────────
  fs:server.*                 ← my-plugin-1.0.0
  http:*                      ← yeow-test-pkg-1.0.0

── Computed permissions (2) ─────────────────
  fs:server.*
  http:*
```

**控制台核对**：运行时加载插件时会把权限清单打印到服务器控制台（`Loaded plugin: <name> ... — permissions: ...`）。

## 二、原生服务可信性声明（强制）

插件（或依赖包）**必须**在 `yeow.config.json` 声明 `native` 字段，固定原生服务二进制的 SHA-256——构建时遍历主项目与全部依赖包，按各自命名空间计算打包后的哈希并合并写入 `yeow.json`；运行时把它作为插件元数据保存，注册原生服务时强制校验。

```json
{
    "native": [
        {
            "serviceId": "iyexin.image-svc.v1",
            "files": ["native/win/image-svc.exe"],
            "source": "https://github.com/iyexin/image-svc"
        }
    ]
}
```

- `serviceId`：注册 `registerNativeService` 时的服务名；`files`：**本包** `assets/` 下的二进制原始路径；`source`：来源链接（可选）
- 主项目与依赖包各自声明自己的 `native`；**相同 `serviceId` 在构建时合并**（files 归并，保留各自命名空间路径）
- 构建产物 `yeow.json` 的 `native` 格式：`[{ "serviceId": "...", "files": [{ "<打包后路径>": "<sha256>" }, ...], "source": "..." }]`
- **仅支持单文件模式**（`string` / `{file}`）；目录模式（`{dir, entry}`）已移除

**强制声明（构建 + 加载）**：

- **构建**：申请了 `service:registerNative`（或 `service:*`）权限、但合并后的 `native` 清单为空 → **构建失败**；声明的文件缺失也失败
- **加载**：申请了该权限但清单为空 → **拒绝加载**
- **不申请 `service:registerNative` 权限的插件无需声明 `native`**

**运行时行为**：

- serviceId 已声明 + 二进制路径已声明 + SHA-256 匹配 → 注册成功（日志显示校验通过）
- serviceId 未声明 / 二进制路径未声明 / SHA-256 不匹配 → **拒绝注册**，`registerNativeService` 的 Promise reject
- **声明 ≠ 可信**：无论是否声明，注册时都打印风险日志（视为不可信）——声明只固定内容，不代表来源可信

### 不可信服务开关（默认允许加载并警告）

**默认情况下全部原生服务均视为不可信**（即使有哈希声明）。申请了 `service:registerNative` 权限的插件正常加载，但控制台会打印醒目的不可信警告（是否固定 SHA-256、有无 `native` 声明一并说明）：

- 允许加载 → 插件正常运行（`onLoad` 执行），控制台警告提示其将以子进程运行不可信二进制
- **配置**：`plugins/Yeow/runtime/config.yml` 的 `native-service-allow-untrusted`（默认 `true`；`false` = 申请了 `service:registerNative` 权限的插件**拒绝加载**，控制台提示改配置项）。已有配置缺失该字段时，运行时加载会自动合并默认并写回（平滑升级）
- **runtime 目录受 fs 写保护**——插件无法通过 fs API 修改其中的文件（`config.yml`）

> **开发者**：错误处理与降级示例（区分"服务已存在 / 可执行文件被篡改"）见 [Service API](api/service.md) 与 [封装 Service 的依赖包](package-service.md)。

> **未来展望**：在线安全性校验（比对官方维护的安全清单）能力已在规划中——若二进制哈希命中官方安全清单，加载时将不再提示风险（当前版本命中清单与否均按不可信处理并警告）。

## 三、相关文档

- **平台规范 · 权限模型**（运行时实现者视角）：[specifications/README.md#权限模型](specifications/README.md#权限模型)
- **依赖包权限声明**（npm 包如何声明）：[编写依赖包 - 权限](package-author.md)
- **运行时配置**（`native-service-allow-untrusted` 等）：[运行时运维 - 配置](operations.md#运行时配置)
