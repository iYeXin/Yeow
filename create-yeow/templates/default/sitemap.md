# 站点地图（Sitemap）

> 面向 **Vibe Coding / AI 查阅**：本站全部页面的标题 + 摘要 + 绝对 URL。站点根 `https://yeow.yeside.top`（base `/v1/`，cleanUrls）。
> Markdown 源位于仓库 `Yeow-Docs/zh/`；本地预览：`yeow-doc-website` 下 `npm run dev`。

## 指南（根）

| 页面                    | URL                                                | 摘要                                                                                                                                                                                            |
| ----------------------- | -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 概览                    | `https://yeow.yeside.top/v1/overview`              | 项目总览：用 TypeScript 写 Paper 插件（QuickJS 引擎，每插件独立线程）。按角色（初学者/开发者/服主/平台实现者）的文档入口导引 + 关键概念速览                                                     |
| AI 辅助启动指南         | `https://yeow.yeside.top/v1/ai-agent`              | 面向 AI 代理 / Vibe Coding：Yeow 项目简介、启动命令（`--ts`）、下一步、文档查阅策略（站点地图/docs.zip/Harness 用法）                                                                           |
| 快速开始                | `https://yeow.yeside.top/v1/getting-started`       | 从零开始：`npm create yeow` 建项目 → `npm run dev` 开发（热重载）→ `npm run build` 构建 → 部署方式。含插件示例（/back 传送）、异步/同步约定、权限声明、原生服务批准、/yeow 管理命令、运行时配置 |
| CLI 参考                | `https://yeow.yeside.top/v1/cli`                   | create-yeow 脚手架与 dev-server 的命令行用法：交互式/非交互创建、开发服务器参数（-y/--stop/--proxy）、构建脚本、调试体验（source-map 错误定位与异步调用链）                                     |
| 构建与分发              | `https://yeow.yeside.top/v1/distribution`          | 两种产物：标准 Paper JAR（plugins/）与平台无关 .yeow.zip（plugins/Yeow/ 自动扫描或 /yeow install）；分发建议与 Modrinth 发布                                                                    |
| 运行时警告              | `https://yeow.yeside.top/v1/runtime-warning`       | 预警引擎：heartbeat.timeout / event.slow / plugin.hung / budget.congested 等告警的触发条件、含义与解决方案；配置阈值；动态扩容机制                                                              |
| 进阶知识                | `https://yeow.yeside.top/v1/advanced`              | 进阶索引（拆分后）：架构/调度器/事件/生命周期/通道/服务/运维与安全的入口                                                                                                                        |
| 进阶 · 架构与线程模型   | `https://yeow.yeside.top/v1/advanced/architecture` | 包结构、启动流程、线程模型、插件实体抽象、Worker（虚拟插件）、开发模式错误回显、资源路径机制（getAssetsPath）                                                                                   |
| 进阶 · 调度器与任务     | `https://yeow.yeside.top/v1/advanced/scheduler`    | 三级优先级调度器（时间片预算/自动降级/空闲自旋）、异步 vs 同步、手动分片、任务执行时机（onLoad/onInit）                                                                                         |
| 进阶 · 事件与回调       | `https://yeow.yeside.top/v1/advanced/events`       | 事件桥（EventBridge）：并发/串行、事件数据、处理器操作与模式选择                                                                                                                                |
| 进阶 · 生命周期与热重载 | `https://yeow.yeside.top/v1/advanced/lifecycle`    | onInit/onLoad/onUnload、统一回调系统、热重载、生产 /yeow reload/unload                                                                                                                          |
| 进阶 · 环境能力与通道   | `https://yeow.yeside.top/v1/advanced/channels`     | $_send/$send 封装、各消息通道、运行时配置（config.yml）                                                                                                                                         |
| 进阶 · 服务机制         | `https://yeow.yeside.top/v1/advanced/service`      | Plugin Service（插件间通信）与 Native Service（原生扩展）                                                                                                                                       |
| 进阶 · 运行时运维与安全 | `https://yeow.yeside.top/v1/advanced/operations`   | 预警引擎、动态扩容（BudgetScaler）、全量分析、平台无关性、定时器资源管理、安全                                                                                                                  |
| 关于 Yeow               | `https://yeow.yeside.top/v1/advanced/about`        | 关于 Yeow                                                                                                                                                                                       |
| 编写依赖包              | `https://yeow.yeside.top/v1/package-author`        | 将共享逻辑与资源封装为 npm 依赖包：assets 命名空间、三类 Service 包（SDK / JS 服务 / 原生服务）、权限声明、native 可信性声明                                                                    |
| 路线图                  | `https://yeow.yeside.top/v1/todo`                  | v1 方向性规划：API/事件覆盖、调试工具、Folia 支持；Worker API 已实现                                                                                                                            |
| 索引（README）          | `https://yeow.yeside.top/v1/`                      | 文档首页：快速上手命令、为什么用 Yeow（工程化/线程分离/平台无关/原生扩展）、对比表、文档与工具链索引                                                                                            |

## API 参考（/v1/api/）

| 页面        | URL                                          | 摘要                                                                                                                                                                             |
| ----------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| API 索引    | `https://yeow.yeside.top/v1/api/`            | 全部 API 模块的分组索引                                                                                                                                                          |
| Player      | `https://yeow.yeside.top/v1/api/player`      | 玩家：属性（血量/饥饿/经验/飞行等）、位置、消息（Message 对象）、Title/ActionBar、音效、资源包、传送、权限、`performCommand`、手持物品（ItemStack 快照）                         |
| Server      | `https://yeow.yeside.top/v1/api/server`      | 服务器级：广播、MOTD（setMotd 全局默认）、serverPing 事件回写（按次覆盖 motd/icon/人数）、版本、TPS、最大玩家数                                                                  |
| Env         | `https://yeow.yeside.top/v1/api/env`         | 运行时环境信息（同步）：CPU 核心数、内存、系统架构、Minecraft 版本、Yeow 运行时信息、epoch 微秒时间戳                                                                            |
| Permission  | `https://yeow.yeside.top/v1/api/permission`  | 权限：`registerPermission`（default: all/op/none）、命令权限、检查流程、`permissionCheck` Yeow 生态钩子（优先级/触发范围/无限循环提醒）、Paper 兼容（permissions.yml/LuckPerms） |
| World       | `https://yeow.yeside.top/v1/api/world`       | 世界：时间/天气/难度/规则、方块（getBlock/setBlock，Block 对象）、区块、光照、生物群系、实体查询、掉落/闪电/爆炸/生成                                                            |
| Chunk       | `https://yeow.yeside.top/v1/api/chunk`       | 区块快照（进阶性能工具）：3D 完整快照与 2D 顶部快照（short[] base64 零拷贝视图 + 方块索引映射）                                                                                  |
| Location    | `https://yeow.yeside.top/v1/api/location`    | 坐标与朝向：x/y/z/yaw/pitch/world                                                                                                                                                |
| Block       | `https://yeow.yeside.top/v1/api/block`       | 统一方块概念：数据描述符（type/state）+ 可选 location；静态数据语义（快照）；材料级判断委托 Material；breakNaturally 需 location                                                 |
| Material    | `https://yeow.yeside.top/v1/api/material`    | 材料注册表查询（getMaterials/getBlocks/getItems）+ 材料级静态判断对象（isSolid/isLiquid/isAir，不依赖坐标/状态）                                                                 |
| Entity      | `https://yeow.yeside.top/v1/api/entity`      | 实体：类型/名称/位置、发光/无敌/静默/重力、乘客/载具、碰撞盒、生命值；LivingEntity                                                                                               |
| Potion      | `https://yeow.yeside.top/v1/api/potion`      | 药水效果：添加/移除/清除/查询（类型、时长、等级）                                                                                                                                |
| Particle    | `https://yeow.yeside.top/v1/api/particle`    | 粒子生成：类型、位置、数量、偏移、颜色/方块/物品粒子                                                                                                                             |
| GUI         | `https://yeow.yeside.top/v1/api/gui`         | 容器界面：创建（大小/标题）、开合、设物品（ItemStack）、填充、清空；生命周期（gc-collect 自动回收）                                                                              |
| Inventory   | `https://yeow.yeside.top/v1/api/inventory`   | 玩家物品栏：槽位读写、增减物品、清空                                                                                                                                             |
| BossBar     | `https://yeow.yeside.top/v1/api/bossbar`     | 血条：标题/进度/颜色/样式/可见性、玩家绑定、Flag                                                                                                                                 |
| Scoreboard  | `https://yeow.yeside.top/v1/api/scoreboard`  | 计分板：Board、目标（显示槽位/分数）、队伍（前后缀/颜色/选项/成员）                                                                                                              |
| Advancement | `https://yeow.yeside.top/v1/api/advancement` | 进度：授予/撤销、进度查询、判据授予/撤销                                                                                                                                         |
| Recipe      | `https://yeow.yeside.top/v1/api/recipe`      | 配方：有序/无序合成、熔炉/高炉/烟熏/营火；添加/移除/按产物查询                                                                                                                   |
| Event       | `https://yeow.yeside.top/v1/api/event`       | 事件订阅：`eventOn`/`eventOff`、自动/手动模式（取消、回写）、全事件字段表、消息（Message 对象）                                                                                  |
| Command     | `https://yeow.yeside.top/v1/api/command`     | 命令注册 + Tab 补全（含 yeow-utils 重载式命令 Command.create 与模式化参数）                                                                                                      |
| ItemStack   | `https://yeow.yeside.top/v1/api/item`        | 物品纯数据描述符：type/amount/meta（显示名/附魔/自定义模型等）；值语义（快照，不绑定真实物品）                                                                                   |
| Service     | `https://yeow.yeside.top/v1/api/service`     | 插件间服务（registerService/request/subscribe/publish）与原生服务（registerNativeService，spawn 子进程 + TCP 通信）                                                              |
| HTTP        | `https://yeow.yeside.top/v1/api/http`        | 底层 HTTP 客户端：request（异步）/requestSync（同步阻塞）/fetch                                                                                                                  |
| HTTP Server | `https://yeow.yeside.top/v1/api/http-server` | 高层 `createServer`（yeow-utils）：洋葱中间件、路由、mount/mountAssets 静态挂载、二进制响应（bodyBase64）、返回对象自动 JSON、资源包下载闭环                                     |
| Worker      | `https://yeow.yeside.top/v1/api/worker`      | 虚拟插件（独立线程）：createWorker（仅注册）/load/unload/reload、双向 postMessage、Worker 侧 onMessage/postMessage；共享数据目录/权限、禁嵌套、/yeow 不覆盖                      |
| FS          | `https://yeow.yeside.top/v1/api/fs`          | 文件系统：plugin/server/outer 三级（路径安全）、读写/追加/二进制、目录操作、systemPaths、path 工具                                                                               |
| Assets      | `https://yeow.yeside.top/v1/api/assets`      | 打包资源：`getAssetsPath`（yeow-dev，构建期注入命名空间）+ 读取/解压（单文件与目录）                                                                                             |
| PDC         | `https://yeow.yeside.top/v1/api/pdc`         | 持久数据容器：实体/方块的键值数据                                                                                                                                                |
| Log         | `https://yeow.yeside.top/v1/api/log`         | 日志：`log`/`Logger`/`console`（自动插件名前缀）                                                                                                                                 |
| Text        | `https://yeow.yeside.top/v1/api/text`        | 文本与 MiniMessage：标记语法、转义规则（真实换行 vs 字面 `\n`）、Message 对象（可翻译组件 {key,args,text}）                                                                      |

## 平台规范（/v1/specifications/）

| 页面           | URL                                                         | 摘要                                                                                                                                              |
| -------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| 规范总览       | `https://yeow.yeside.top/v1/specifications/`                | 协议层总纲：包结构（yeow.json/.yeow/main.js/assets）、加载流程、权限模型、运行时架构、任务执行器、事件/命令桥、Native Service、合格运行时检查清单 |
| Java 插件集成  | `https://yeow.yeside.top/v1/specifications/java-api`        | 其他 Java 插件调用 Yeow 服务（requestService 请求-响应、subscribeService 订阅事件）、提交游戏任务、约束                                           |
| 适配器规范     | `https://yeow.yeside.top/v1/specifications/adapter/`        | 多语言/社区适配器：PluginEntity 接口、消息契约、submitTask、注册 API、检查清单                                                                    |
| 运行时环境标准 | `https://yeow.yeside.top/v1/specifications/runtime/`        | JS 环境：语言标准（ES2025+SecU8）、回调系统（cb 语义）、事件循环、通道总览与权限、全局变量（$send/$dev/fetch/timers）                             |
| 原生服务       | `https://yeow.yeside.top/v1/specifications/native-service/` | 原生子进程协议：平台选择、提取、TCP JSON line（ready/request/response/publish）                                                                   |

### 消息通道（/v1/specifications/message/）

| 页面      | URL                                                           | 摘要                                                                                                                                |
| --------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 通道总览  | `https://yeow.yeside.top/v1/specifications/message/`          | 全部通道索引（task/timer/fs/http/assets/lifecycle/log/env/dir/debug/service/worker）+ 通用通道说明                                  |
| Task      | `https://yeow.yeside.top/v1/specifications/message/task`      | task 通道：游戏任务请求/响应格式、同步 vs 异步（cb）、错误格式                                                                      |
| Timer     | `https://yeow.yeside.top/v1/specifications/message/timer`     | 定时器通道：timeout/interval 消息格式                                                                                               |
| FS        | `https://yeow.yeside.top/v1/specifications/message/fs`        | fs 通道：plugin/server/outer 三级、各操作（读写/删除/列出/base64/systemPaths）请求格式与路径规则                                    |
| HTTP      | `https://yeow.yeside.top/v1/specifications/message/http`      | http 通道：listen/respond（含 bodyBase64 二进制）/close/request/requestAsync 消息格式                                               |
| Assets    | `https://yeow.yeside.top/v1/specifications/message/assets`    | assets 通道：read/readBase64/extract/extractDir 消息格式（命名空间路径）                                                            |
| Service   | `https://yeow.yeside.top/v1/specifications/message/service`   | service 通道：注册（plugin/native）、请求、订阅/发布、原生 terminate 回调                                                           |
| Log       | `https://yeow.yeside.top/v1/specifications/message/log`       | log 通道：日志消息格式                                                                                                              |
| Lifecycle | `https://yeow.yeside.top/v1/specifications/message/lifecycle` | lifecycle 通道：unloadDone 确认、gc-collect 资源回收                                                                                |
| Debug     | `https://yeow.yeside.top/v1/specifications/message/debug`     | debug 通道：reportError 错误上报、ping-pong 心跳                                                                                    |
| Worker    | `https://yeow.yeside.top/v1/specifications/message/worker`    | worker 通道：create（仅注册）/load/unload/post/reload/postToMain 消息格式、生命周期、origin 错误字段、约束（禁嵌套/共享数据与权限） |

### 任务类型（/v1/specifications/task/）

| 页面            | URL                                                            | 摘要                                                                                                                        |
| --------------- | -------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| 任务总览        | `https://yeow.yeside.top/v1/specifications/task/`              | 全部 task 类型索引与请求/返回约定                                                                                           |
| Player          | `https://yeow.yeside.top/v1/specifications/task/player`        | player.* 任务：属性/位置/消息（Message 对象格式）/Title/音效/资源包/传送/权限/手持物品                                      |
| Server          | `https://yeow.yeside.top/v1/specifications/task/server`        | server.* 任务：广播（Message）、MOTD、版本、TPS、最大玩家数；Material 查询                                                  |
| World           | `https://yeow.yeside.top/v1/specifications/task/world`         | world.* 任务：时间/天气/方块（BlockData 状态）/区块/光照/生物群系/实体/爆炸/生成；block.breakNaturally、material.* 静态判断 |
| Entity          | `https://yeow.yeside.top/v1/specifications/task/entity`        | entity.* 任务：类型/名称/属性/位置/碰撞盒/生命值/药水效果                                                                   |
| Scoreboard      | `https://yeow.yeside.top/v1/specifications/task/scoreboard`    | scoreboard.* 任务：Board/Objective/Score/Team 全操作                                                                        |
| Recipe          | `https://yeow.yeside.top/v1/specifications/task/recipe`        | recipe.* 任务：配方定义（shaped/shapeless/熔炉系）与添加/移除                                                               |
| PDC             | `https://yeow.yeside.top/v1/specifications/task/pdc`           | pdc.* 任务：实体/方块持久键值数据                                                                                           |
| Inventory & GUI | `https://yeow.yeside.top/v1/specifications/task/inventory-gui` | inventory.* / gui.* 任务：物品栏操作、GUI 生命周期；ItemStack 完整格式                                                      |
| Command         | `https://yeow.yeside.top/v1/specifications/task/command`       | command.* 任务：注册/执行/补全（回调协议）                                                                                  |
| Event System    | `https://yeow.yeside.top/v1/specifications/task/event-system`  | event.* 任务：subscribe/unsubscribe/complete（eventId 匹配）；并发/串行模式                                                 |
| Advancement     | `https://yeow.yeside.top/v1/specifications/task/advancement`   | advancement.* 任务：授予/撤销/进度查询/判据                                                                                 |
| BossBar         | `https://yeow.yeside.top/v1/specifications/task/bossbar`       | bossbar.* 任务：创建/销毁/标题/进度/样式/玩家                                                                               |

### 事件（/v1/specifications/event/）

| 页面           | URL                                                                | 摘要                                                                                                                             |
| -------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------- |
| 事件总览       | `https://yeow.yeside.top/v1/specifications/event/`                 | 全部事件类型索引                                                                                                                 |
| Player 事件    | `https://yeow.yeside.top/v1/specifications/event/player-events`    | 玩家事件字段：加入/退出/聊天/移动/交互/死亡（Message 对象）/重生/掉落/拾取/桶/经验/等级/游戏模式/进度/潜行/飞行/传送/进食/资源包 |
| Entity 事件    | `https://yeow.yeside.top/v1/specifications/event/entity-events`    | 实体事件字段：伤害/死亡/生成/爆炸/回血/目标/弹射物                                                                               |
| Block 事件     | `https://yeow.yeside.top/v1/specifications/event/block-events`     | 方块事件字段：破坏/放置/消退/生长/蔓延/爆炸                                                                                      |
| Inventory 事件 | `https://yeow.yeside.top/v1/specifications/event/inventory-events` | 背包事件字段：打开/关闭/点击（槽位/按键/动作）                                                                                   |
| Server 事件    | `https://yeow.yeside.top/v1/specifications/event/server-events`    | 服务器事件：serverPing（回写 motd/icon/maxPlayers/numPlayers）、serverCommand、资源包状态                                        |

## 侧边栏导航结构

```
开始：概览 · 快速开始 · CLI 参考 · 构建与分发 · 运行时警告 · 路线图 · 站点地图
进阶（默认折叠）：关于 Yeow · 进阶索引（架构/调度器/事件/生命周期/通道/服务/运维与安全）
依赖包开发：编写依赖包
API 参考：索引 → 玩家与服务器(Player/Server/Env) · 世界与方块(World/Chunk/Location/Block/Material) · 实体(Entity/Potion/Particle) · 交互界面(GUI/Inventory/BossBar/Scoreboard/Advancement/Recipe) · 事件与命令(Event/Command) · 物品(ItemStack) · 服务与网络(Service/HTTP/HTTP Server) · 多线程(Worker) · 文件与数据(FS/Assets/PDC) · 文本(Text) · 日志(Log)
平台规范：规范总览 → 消息通道 · 任务类型 · 事件 · 运行时 · 原生服务 · 适配器
```
