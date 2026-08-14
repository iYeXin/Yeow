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
| `assets:extract` / `assets:extractDir` | 解压资源到磁盘（单文件 / 目录提取，**两个独立节点**）                                    |

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

> 修改 `permissions` 后需重新构建并完整重载插件（`/yeow reload` 或重启服务器）；开发模式热重载只重载代码，不更新权限。

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

## 二、原生服务可信性声明

插件（或依赖包）可在 `yeow.config.json` 声明 `native` 字段，**固定原生服务二进制的 SHA-256**——构建时自动计算打包后的哈希并写入 `yeow.json`；运行时注册原生服务时校验，哈希不匹配则**拒绝加载**（Promise reject）。

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

- `serviceId`：注册 `registerNativeService` 时的服务名；`files`：本包 `assets/` 下的二进制原始路径；`source`：来源链接（可选）
- 主项目与依赖包均可声明；**相同 `serviceId` 在构建时合并**到一项（files 归并）
- 构建产物 `yeow.json` 的 `native` 格式：`[{ "serviceId": "...", "files": [{ "<打包后路径>": "<sha256>" }, ...], "source": "..." }]`

**运行时行为**：

- 有声明且匹配 → 正常加载（日志显示校验通过）
- 有声明但不匹配（文件被替换/篡改）→ **拒绝加载服务**，`registerNativeService` 的 Promise reject
- **无论是否声明**，加载原生服务时都会打印风险日志：未声明 → 警告"无可信 SHA-256 声明，视为不可信"；已声明 → 提示校验结果
- **可信性声明只对单文件模式有效**（`string` / `{file}`）；目录模式（`{dir, entry}`）暂不支持声明与校验

### 批准（默认需要）

**默认情况下，声明了原生服务的插件需要批准才能加载**（目前全部原生服务均视为不安全，即使有哈希声明）。插件加载时检测到 `native` 声明且未批准 → **拒绝加载本插件**，服务器控制台打印醒目的提示信息（一次性批准码）：

```
/yeow approve <code>    # code 为 6 位 36 进制一次性码（仅控制台可见）
                        # 批准后自动加载被拒的插件，无需手动 reload
```

- 拒绝加载 → 插件不运行（`onLoad` 不会执行），控制台提示包含 `/yeow approve <code>`
- **一次性 code 机制**：每次拒绝加载时生成随机 6 位 36 进制 code（仅出现在控制台日志）——插件本身未加载，无法读取日志后 `dispatchCommand` 自动批准；code 用后即作废
- **配置**：`plugins/Yeow/runtime/config.yml` 的 `native-service-require-approval`（默认 `true`；`false` = 默认批准）。**运行时直接修改即生效**（config.yml 为信任源）
- **批准存储**：`plugins/Yeow/runtime/approve.json`（插件名 → 批准时间戳）。**runtime 目录受 fs 写保护**——插件无法通过 fs API 修改其中的文件（config.yml / approve.json）

> **开发者**：错误处理与降级示例（区分"服务已存在 / 可执行文件被篡改"）见 [Service API](api/service.md) 与 [封装 Service 的依赖包](package-service.md)。

> **未来展望**：Yeow 官方或社区可能维护一份已知安全的 SHA-256 列表——若二进制哈希命中该列表，插件发布时可能被标记为安全，加载时不再提示风险、无需批准。

## 三、相关文档

- **平台规范 · 权限模型**（运行时实现者视角）：[specifications/README.md#权限模型](specifications/README.md#权限模型)
- **依赖包权限声明**（npm 包如何声明）：[编写依赖包 - 权限](package-author.md)
- **运行时配置**（`native-service-require-approval` 等）：[运行时运维 - 配置](operations.md#运行时配置)
