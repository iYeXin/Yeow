# 服务机制

> 服务机制：Plugin Service（插件间通信）与 Native Service（原生能力扩展）。

## 服务机制（Service）

### Plugin Service — 插件间通信

插件 A 注册服务，插件 B 调用：

```
插件 B                         ServiceManager                 插件 A
  serviceRequest(svcId, path, body)
    → service 通道 request
      → registry 定位服务归属插件
        → 通过 onRequestCb 回调投递 {_svc:"request", path, body}
          → 插件 A 的 onRequest(path, body) 处理
          → $send('service', {t:"response", requestId, body})
      → respond(requestId, consumer)
    → 插件 B 的 Promise resolve
```

**发布/订阅**：服务方用 `publish(token, eventPath, body)` 发布事件，订阅方用 `subscribe(serviceId, eventPath, handler)` 接收。`token` 是发布鉴权凭证（注册时返回）。

### Native Service — 原生能力扩展

插件通过 `registerNativeService` 注册，运行时提取可执行文件并 spawn 子进程：

```
registerNativeService(refName, platforms)
  → 按当前平台（os + arch，精确匹配回退 os）选择二进制
  → 从 JAR assets/ 提取到临时目录（命名空间路径经 getAssetsPath 解析）
  → spawn(binary, nativePort, serviceId)
  → 子进程连接 TCP → 发送 {"type":"ready"} → ready() resolve
  → 请求走 TCP JSON line：request → response
```

- **平台粒度**：`windows-x64` / `linux-arm64` 等，精确匹配优先，回退到 `windows` / `linux`
- **单一实例**：`isPublic: true` 时同名服务只启动一个进程/保留一个注册；重复注册被拒绝（返回 `err` + `serviceId`），调用方用 `err.serviceId` 以调用者身份接入既有服务
- **协议**：见 [Native Service 规范](/specifications/native-service/index)

API 用法见 [Service API](/api/service)。

## 运行时警告与动态扩容

### 调度语义

三级队列语义不同，检测与告警**只针对实时队列**：

- **HIGH / NORMAL**（实时性、交互响应）——不应存在积压，积压即问题
- **LOW**（大批量重复任务）——允许积压与延迟完成，不计入告警与健康评分

### 警告检测

预警引擎默认启用（`profile.warnings-enabled: true`），按 1s 窗口聚合检测，双语告警输出（上下两条彩色分隔线，随级别变色）：

| code                                   | 触发                                      |
| -------------------------------------- | ----------------------------------------- |
| `heartbeat.timeout`                    | JS 线程单次心跳 >200ms                    |
| `plugin.hung`                          | >30s 持续无响应（线程已死）               |
| `event.slow` / `event.timeout`         | 事件响应 >2s / 等待 >5s 被释放            |
| `tab.slow` / `tab.timeout`             | 补全响应 >500ms / 等待 >1s                |
| `budget.congested` / `budget.restored` | 40 tick 内 HIGH/NORMAL 积压 ≥35 次 / 恢复 |
| `scheduler.saturated`                  | HIGH/NORMAL 执行占 tick >80%              |

同类警告冷却 30 分钟（可配置）。详见 [运行时警告指南](/runtime-warning)。

### 动态扩容（BudgetScaler）

运行时组件（独立于预警引擎）：最近 40 tick 中 HIGH/NORMAL 积压 ≥35 次（滑动窗口）→ 预算 ×1.3（指数叠加，最大 ×3）；连续 40 tick 无积压逐级回落。

### 全量分析（profile.enabled）

逐任务级采集默认关闭。开启后 `/yeow profile` 输出健康评分 + 实时/批量队列分解，`/yeow track` 单插件深度追踪。预警引擎不依赖此开关。

## 平台无关性

Yeow 插件本身**平台无关**：

- 插件包是一个 ZIP（`.yeow.zip` 或部署为 JAR），内含 `.yeow/main.js`（打包后的 JS）、`assets/`、`yeow.json`（含权限声明）
- 不依赖 Java 环境——运行时不限语言/平台
- 放入 `plugins/Yeow/` 会被运行时自动扫描加载（也可用 `/yeow load` 手动加载）
- 任何符合 [平台规范](/specifications/README) 的运行时都能加载并运行 Yeow 插件：
  1. 理解插件包结构（读取 `yeow.json`、`.yeow/main.js`、`assets/`）
  2. 实现调度器（任务队列 + 优先级 + 时间片）
  3. 实现执行器（把任务翻译为宿主平台的游戏操作）
  4. 实现符合标准的 JS 运行时（`$_send` 桥、回调协议、生命周期消息）
  5. 实现通道（fs / http / assets / service / timer 等）

Paper 系（Paper/Purpur/Leaf 等）的 yeow-runtime 是官方实现的运行时示例。更多插件包格式见 [平台规范](/specifications/README)。

## 定时器资源管理

- 每个 PluginThread 拥有独立的 `ScheduledExecutorService`（线程名 `timer-<插件名>`）
- 所有 `ScheduledFuture` 存储在 `timerFutures` 列表
- `stop()` 时 `cancel()` 所有 Future + `shutdownNow()`
- `scheduler.purgePluginTasks(name)` 清理残留的 PendingTask

## 安全

- **路径隔离**：`fs.*`（`plugin` 段节点）限制在 `plugins/<插件名>/`，`fs.server.*` 限制在服务器根目录，均拦截 `../` 穿越；`fs.outer.*` 无范围限制（需声明权限）
- **上下文隔离**：每个插件独立 QuickJSContext，全局对象互不干扰
- **权限声明**：敏感消息节点（`fs:server.*`、`fs:outer.*`、`http:*`、`service:registerNative`、`assets:extract`）默认拒绝，必须在 `yeow.config.json` 声明（构建时计算进 `computedPermissions`）；未声明调用返回 `Permission denied: <node>`。权限只按**节点**匹配（节点名中的段是业务/访问范围命名，非层级）：节点级（`fs:server.readFile`）、整组通配（`fs:server.*`）与通道通配（`fs:*`），其余节点默认允许
- **Yeow 生态权限检查（permissionCheck）**：`player.hasPermission` 任务与 Yeow 插件注册命令的**执行时检查**会先触发 `permissionCheck` 事件（[详见事件规范](/specifications/event/index#permissioncheckyeow-生态权限检查)）——handler 返回 `{ allowed }` 决定结果（**覆盖 Paper 系 hasPermission**），不返回则回退 Paper 系；多 handler 返回冲突以最后返回的为准。**仅限 Yeow 生态**：其他 Java 插件的 hasPermission / 命令执行不经过此检查
- **命令权限**：Yeow 命令的权限节点同时注册进 Paper 系权限系统（传统插件/权限插件可管理），但命令**不设 setPermission 拦截**——执行时按上述顺序检查（permissionCheck > Paper 系）；补全不做权限过滤
- **同名唯一**：同一插件名只允许一个实例，重复加载（自动扫描 / `/yeow load` / 模板 JAR）均被拒绝并警告
