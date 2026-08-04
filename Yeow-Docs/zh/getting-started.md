# 快速开始

> Yeow 暂未正式发布。Yeow 在正式发布前不保证 API 的稳定性。

> 如果 Modrinth 上的 Yeow 项目仍未结束 Under Review 状态，可以[点此下载](https://raw.githubusercontent.com/iYeXin/Yeow/main/create-yeow/templates/default/.yeow/assets/yeow-runtime-0.1.0.jar) Yeow 运行时插件。

## 创建项目

```bash
npm create yeow@latest -- -y               # JavaScript（默认）
npm create yeow@latest -- -y --ts          # TypeScript
cd my-plugin
npm install
```

![创建 Yeow 项目](assets/create-yeow.png)

## 开发

```bash
npm run dev                    # 启动 Paper 服务器 + 热重载
npm run dev -- -y              # 跳过交互，自动接受 EULA
npm run dev -- --stop=30m      # 30 分钟后自动停止
```

编辑 `src/` 或 `assets/` 下的文件自动触发热重载，无需重启服务器。

开发模式下，运行时错误会自动定位到**源码位置**（source-map 反解）并附带完整异步调用链，直接在终端展示：

![dev-server 错误定位](assets/error-show.png)

## 构建与部署

```bash
npm run build                  # 生产产物 → dist/<name>-<version>.jar + .yeow.zip
```

`npm run build` 产出两种部署形态：

| 产物                             | 说明                                                                         |
| -------------------------------- | ---------------------------------------------------------------------------- |
| `dist/<name>-<version>.jar`      | 标准 Paper JAR（含 Bootstrap 类 + `depend: Yeow`），放入 `plugins/` 即可运行 |
| `dist/<name>-<version>.yeow.zip` | **平台无关插件包**（纯 ZIP：`.yeow/main.js` + `assets/` + `yeow.json`）      |

三种部署方式（任选其一）：

1. **JAR 方式**：把 `yeow-runtime-0.1.0.jar` 和插件 JAR 一同放入 `plugins/`（与原生 Java 插件部署一致）
2. **自动扫描**：把插件 `.yeow.zip` 放入 `plugins/Yeow/`，服务器启动时自动加载
3. **命令加载**：服务器运行中执行 `/yeow load <path>`（本地临时加载）、`/yeow load <url>`（下载临时加载）、`/yeow install <url>`（下载并安装到 `plugins/Yeow/`）、`/yeow update <url>`（替换旧版本）

> **同名实例唯一**：无论通过哪种方式加载，一个插件名只能存在一个实例——重复加载会被拒绝并输出警告。

> **分发建议**：两种产物应同时上传（`.yeow.zip` 推荐、`.jar` 兼容），并提供 `/yeow install <url>` 一键安装。详见 [构建与分发](distribution.md)。

> **平台无关**：`.yeow.zip` 本身不依赖 Java 或 Bukkit——任何实现 [平台规范](specifications/README.md) 的运行时（理解包结构、调度器、执行器、JS 桥）都能运行同一份插件。Paper/Bukkit 的 yeow-runtime 是官方实现示例。

## 项目结构

```
my-plugin/
├── src/
│   └── index.ts              ← 入口（TS 默认）
├── assets/                   ← 打包资源（图片、配置、原生程序）
├── .yeow/                    ← 构建脚本 + Paper + 运行时 JAR
├── dist/                     ← 构建产物
├── yeow.config.json          ← 插件配置
├── tsconfig.json             ← 仅 TS 模式
└── package.json
```

## 第一个插件

实现 `/back`：记录玩家死亡位置，输入 `/back` 传送回去。

```ts
import {
  onLoad, onUnload, registerCommand, eventOn,
  Player, Location, pdcSet, pdcGet, log,
} from 'yeow-api';

onLoad(() => {
  // 记录所有死亡位置，并通知玩家
  eventOn('playerDeath', async (e) => {
    const loc = e.player.location;
    if (loc) {
      const data = JSON.stringify({ x: loc.x, y: loc.y, z: loc.z, world: loc.world || e.player.world });
      pdcSet(e.player.uuid, 'back.deathLocation', data);
      await e.player.sendMessage(
        `<red>You died!</red> <gray>Use</gray> <click:run_command:/back><aqua><u>/back</u></aqua></click> <gray>to return</gray>`,
      );
    }
  });

  // /back — 返回死亡位置
  registerCommand('back', {
    description: 'Teleport to your death location',
    executor: async (p) => {
      const raw = await pdcGet(p.sender.uuid, 'back.deathLocation');
      if (!raw) return p.sender.sendMessage('<red>No death location recorded</red>');

      const loc = JSON.parse(raw);
      const player = await Player.get(p.sender.uuid);
      if (player) {
        await player.teleport(new Location(loc.x, loc.y, loc.z, 0, 0, loc.world));
        p.sender.sendMessage('<green>Teleported to death location</green>');
      }
    },
  });

  log.info('MyPlugin loaded');
});

onUnload(() => {
  log.info('MyPlugin unloaded');
});
```

> **生命周期**：游戏 API 操作需在 `onLoad` 内进行。`onLoad` 外的顶层代码只能注册回调，不能操作游戏。

## 异步优先

Yeow API 默认异步（`Promise`），同步操作加 `Sync` 后缀：

```ts
// 异步 — 不阻塞 JS 线程
await player.sendMessage('<green>Hello</green>');
await world.setBlock(0, 65, 0, 'minecraft:stone');
await broadcast('Hello!');
const p = await Player.get('Notch');

// 同步 — 阻塞直到完成
player.sendMessageSync('<red>Urgent!</red>');
const q = Player.getSync('Notch');
```

属性访问（`player.ping`、`world.time`）始终同步。

> 大量重复操作请用异步 API（`await` 循环），避免阻塞 JS 线程。详见 [进阶知识](advanced.md)。

## 常用 API 速览

| 想做什么                 | 用什么                                                       | 文档                      |
| ------------------------ | ------------------------------------------------------------ | ------------------------- |
| 玩家发消息 / 传送 / 属性 | `player.sendMessage()` / `player.teleport()` / `player.ping` | [Player](api/player.md)   |
| 设置方块 / 时间 / 天气   | `world.setBlock()` / `world.time`                            | [World](api/world.md)     |
| 订阅事件                 | `eventOn('playerJoin', handler)`                             | [Event](api/event.md)     |
| 注册命令 + Tab 补全      | `registerCommand()` 或 `Command.create()`                    | [Command](api/command.md) |
| 读写插件数据文件         | `fs.readFileSync()` / `fs.writeFileSync()`                   | [FS](api/fs.md)           |
| 读取打包资源             | `getAssetsPath()`（`yeow-dev`）+ `assetsReadSync()`          | [Assets](api/assets.md)   |
| 插件间通信 / 原生程序    | `registerService()` / `registerNativeService()`              | [Service](api/service.md) |
| 日志                     | `log.info()` / `console.log()`                               | [Log](api/log.md)         |

完整索引见 [API 参考](api/README.md)。

## 权限声明

Yeow 对**敏感消息节点**实施声明式权限。插件在 `yeow.config.json` 的 `permissions` 中声明所需权限（构建时自动计算最终权限并写入 `yeow.json`）：

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

**默认需要声明（未声明则调用返回错误）：**

| 权限节点                     | 覆盖范围                                                                                             |
| ---------------------------- | ---------------------------------------------------------------------------------------------------- |
| `fs:server.*` / `fs:outer.*` | fs 通道 `server` / `outer` 前缀节点（服务器根 / 任意路径）；`fs:plugin.*` 节点（插件数据目录）免声明 |
| `http:*`                     | HTTP 全部操作（`http:request`、`http:requestAsync`、`http:listen` 等）                               |
| `service:registerNative`     | 注册原生服务（spawn 子进程）                                                                         |
| `assets:extract`             | 解压资源到磁盘                                                                                       |

> **节点概念**：权限只按**消息节点**考虑（如 `fs:plugin.readFile`、`fs:server.readFile`）。节点名中的段（`plugin` / `server` / `outer`、`task:player.get` 的 `player`）是业务/访问范围命名，**不是层级**——权限匹配不看命名段含义。

粒度规则：

- **节点级**：声明 `fs:server.readFile` 只授予该节点，其他 fs 节点仍被拒绝
- **整组通配**：声明 `fs:server.*` 授予 `server` 前缀全部节点
- **通道通配**：声明 `fs:*` 授予整个 fs 通道（含 server/outer）
- 未声明而调用 → 返回错误（`Permission denied: <node>`），异步 API 以 Promise reject 呈现
- 其余消息节点（如 `service:request`、`assets:read`）默认允许，无需声明

> **⚠ 权限建议**：直接声明 `fs:*` 是**危险且不专业的**。只读写插件自己的配置文件时**无需声明任何 fs 权限**（`fs:plugin.*` 节点默认允许）。确需访问服务器文件时，**尽可能精确声明**（如 `fs:server.readFile`、`fs:outer.systemPaths`），而非整组或通道通配。

> [!WARNING]
> 全局 `fetch` 底层依赖 `http:requestAsync` —— 未声明 http 权限时 `fetch` 会返回 `Permission denied: http:requestAsync`。使用 `fetch` / `request` 前请确保声明了 `"http:*"` 或 `"http:requestAsync"`。

> 修改 `permissions` 后需重新构建并完整重载插件（`/yeow reload` 或重启服务器）；开发模式热重载只重载代码，不更新权限。

> **控制台核对**：Yeow 运行时加载插件时会把权限清单打印到服务器控制台（`Loaded plugin: <name> ... — permissions: ...`。

> **最终权限（computedPermissions）**：构建时自动合并主项目与依赖包的声明（去重 + 通配归一化：`fs:*` 覆盖 `fs:server.*`、`fs:server.readFile` 等；`fs:server.*` 覆盖 `fs:server.readFile`），结果回写到 `yeow.config.json` 的 `computedPermissions` 字段并打包进 `yeow.json`。声明 `fs:*` 会被**自动展开**为 `fs:outer.*, fs:server.*`。可用 `npm run permissions` 查看计算过程与每个权限的来源分布（来自哪个包）。

## 原生服务可信性声明

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

> **开发者**：错误处理与降级示例（区分"服务已存在 / 可执行文件被篡改"）见 [Service API](api/service.md) 与 [编写依赖包](package-author.md)。

> **未来展望**：Yeow 官方或社区可能维护一份已知安全的 SHA-256 列表——若二进制哈希命中该列表，插件发布时可能被标记为安全，加载时不再提示风险、无需批准。

## 插件管理命令

运行时提供 `/yeow` 命令，支持 Tab 补全：

> **权限**：`yeow.admin`（管理命令：load / install / update / unload / uninstall / reload）与 `yeow.profile`（性能命令）均由运行时注册，**默认授予 OP**（`default: op`，可在权限插件中单独调整）。

| 命令                                     | 说明                                                                                                                                                                                 |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `/yeow load <path\|url>`                 | **临时**加载插件包（`.yeow.zip` 或 JAR）。`<path>` 为本地路径；`<url>` 为直接指向 `.yeow.zip` 的下载地址（下载到缓存，不落盘到 `plugins/Yeow/`，重启后不保留）                       |
| `/yeow install <url>`                    | 下载并**安装**：重命名为标准格式 `<name>-<version>.yeow.zip` 保存到 `plugins/Yeow/`（下次启动自动扫描加载），并立即加载                                                              |
| `/yeow update <url>`                     | 下载并**强制替换**旧版本：扫描 `plugins/Yeow/` 内所有 `.yeow.zip`，按 `yeow.json` 中的 `name` 匹配旧版本 → 旧文件移入 `plugins/Yeow/.backup/` → 写入新版本；若插件正在运行则自动重载 |
| `/yeow unload <plugin\|all>`             | 卸载插件（与热重载相同的卸载逻辑，5s 强制终止）                                                                                                                                      |
| `/yeow uninstall <plugin>`               | 卸载并把 `plugins/Yeow/` 下对应 `.yeow.zip` 移入 `plugins/Yeow/.backup/`（数据目录 `plugins/<plugin>/` 需手动清理）                                                                  |
| `/yeow reload <plugin\|all> [path\|url]` | 重新加载。`<plugin>` 可选 `path` 或 `url` 从新来源加载（URL 为临时，不持久化）；`all` 按原路径全部重载                                                                               |
| `/yeow approve <code>`                   | 用控制台提示中的**一次性批准码**批准插件（声明原生服务的插件被拒后，批准会**自动加载**它；code 用后作废，关闭时写回 `approve.json`）                                                                |
| `/yeow profile`                          | 性能快照（需 `profile.enabled: true` 开启全量分析）                                                                                                                                  |
| `/yeow track <plugin> <seconds>`         | 单插件深度追踪（需 `profile.enabled: true`）                                                                                                                                         |

```bash
/yeow load plugins/Yeow/my-plugin-1.0.0.yeow.zip              # 服务器运行时动态加载
/yeow load https://example.com/my-plugin.yeow.zip             # 直接下载加载（临时，重启不保留）
/yeow install https://example.com/my-plugin.yeow.zip          # 下载并安装到 plugins/Yeow/（标准格式）
/yeow update https://example.com/my-plugin.yeow.zip           # 替换旧版本（旧包备份到 plugins/Yeow/.backup/）
/yeow reload my-plugin https://example.com/my-plugin.yeow.zip # 从新来源重载（临时）
```

> **`.yeow.zip` 优先规则**：Yeow 的管理命令（load / install / update / reload）以 `.yeow.zip` 为主要支持对象（JAR 仅 `load`/`reload` 支持本地路径）。**如果一个插件同时部署了模板 JAR（`plugins/<name>.jar`）和 `.yeow.zip`（`plugins/Yeow/`），两者会注册同一个插件名，产生冲突警告（重复加载被拒绝）**——需要手动解决：保留其一、移除另一个。

重复加载同名插件时输出警告并拒绝（无论通过自动扫描、命令还是模板 JAR 注册）。

## 运行时配置

首次启动后在 `plugins/Yeow/runtime/config.yml` 生成：

```yaml
tick-budget-ms: 20               # 每 tick 任务时间预算（ms）
priority-ratios: [0.5, 0.3, 0.2] # 三级优先级比例
auto-demote: true                # 自动降级
demote-threshold: 200            # 降级阈值（次/秒）
idle-spin-us: 100                # 空闲自旋（us），0 关闭

native-service-require-approval: true  # 声明原生服务的插件需要批准（默认 true；false = 默认批准）。
                                       # 运行时直接修改即生效（config.yml 为信任源）。

profile:
  enabled: false                 # 全量性能分析（逐任务采集），默认关闭
  warnings-enabled: true         # 预警引擎（默认开启，与全量分析独立）
  warn-cooldown-seconds: 1800    # 同类警告冷却时间（30min）
  latency-warn-threshold-ms: 200 # 心跳超时阈值（ms）
  event-slow-threshold-ms: 2000  # 事件响应警告阈值（ms；超时仍为 5000）
  tab-slow-threshold-ms: 500     # 补全响应警告阈值（ms；超时仍为 1000）
  callback-timeout-event-ms: 5000 # 事件回调等待上限（ms，运行时生效）
  callback-timeout-tabcomplete-ms: 1000 # 命令补全等待上限（ms，运行时生效）
  suspend-warn-seconds: 30       # 插件挂起检测阈值（s）
  backlog-threshold: 35          # 扩容信号：40 tick 中积压次数阈值
  backlog-window-ticks: 40
  scheduler-saturation-pct: 80   # 调度饱和告警百分比

  scaler:
    enabled: true                # 动态扩容
    expansion-factor: 1.3        # 每次扩容倍数
    max-multiplier: 3.0          # 最大扩容上限
```

## 遇到问题？

插件出现异常行为时，运行时会在控制台输出结构化警告（心跳超时、事件超时、队列积压等），并给出排查建议：

![运行时警告](assets/warning-log.png)

- 运行时警告（心跳超时、事件超时等）→ [运行时警告指南](runtime-warning.md)
- 详细架构与线程模型 → [进阶知识](advanced.md)
