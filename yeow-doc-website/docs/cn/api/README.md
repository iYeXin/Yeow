# API 参考

按模块分组的完整 API 索引。标注 ⭐ 为**常用 API**（绝大多数插件都会用到的核心能力）。

## 玩家与实体

| 文档 | 说明 |
|------|------|
| ⭐ [Player](player.md) | 玩家：属性、位置、消息、传送、权限、手持物品 |
| ⭐ [Entity](entity.md) | 实体/活体：类型、位置、状态、生命、速度、目标（Player 亦属实体） |
| ⭐ [Location](location.md) | 坐标与朝向（参数/返回通用） |

## 世界与方块

| 文档 | 说明 |
|------|------|
| ⭐ [World](world.md) | 世界：时间、天气、难度、游戏规则、方块、实体、爆炸 |
| ⭐ [Block](block.md) | 方块：数据描述符（type/state）、世界操作 |
| ⭐ [Material](material.md) | 材料级静态判断（isSolid/isAir/getMaxDurability）+ 注册表查询 |
| [Chunk](chunk.md) | 区块快照（批量方块索引，进阶性能工具） |

## 实体附属

| 文档 | 说明 |
|------|------|
| [Potion](potion.md) | 药水效果：添加/移除/清除/查询 |
| [Particle](particle.md) | 粒子生成 |

## 物品与数据

| 文档 | 说明 |
|------|------|
| ⭐ [ItemStack](item.md) | 物品纯数据描述符：type/amount/meta |
| ⭐ [Inventory](inventory.md) | 统一容器：玩家物品栏 / 容器方块 / 自定义 Inventory |
| ⭐ [PDC](pdc.md) | 持久数据容器：Player/Block 实例方法、JSON 自动序列化 |
| [BossBar](bossbar.md) | 血条：标题、进度、颜色、玩家绑定 |
| [Scoreboard](scoreboard.md) | 计分板：目标、队伍 |
| [Advancement](advancement.md) | 进度：授予/撤销 |
| [Recipe](recipe.md) | 配方：添加/移除 |

## 事件与命令

| 文档 | 说明 |
|------|------|
| ⭐ [Event](event.md) | 事件订阅：`eventOn` / `eventOff` |
| ⭐ [Command](command.md) | 命令注册 + Tab 补全 |

## 权限

| 文档 | 说明 |
|------|------|
| ⭐ [Permission](permission.md) | 权限节点：`registerPermission`、命令权限、`permissionCheck` 生态钩子 |

## 文件与网络

| 文档 | 说明 |
|------|------|
| ⭐ [FS](fs.md) | 文件系统读写（含 `path` 工具） |
| [Assets](assets.md) | 打包资源：`getAssetsPath`（`yeow-dev`）+ 读取/解压 |
| [HTTP](http.md) | 底层 HTTP 客户端：`request` / 全局 `fetch` |
| [HTTP Server](http-server.md) | HTTP 服务端（`yeow-server` `createServer`） |
| [Service](service.md) | 插件间/原生服务：注册、请求、订阅（**较进阶**，多数插件用不到） |

## 服务器与运行环境

| 文档 | 说明 |
|------|------|
| [Server](server.md) | 服务器级：广播、MOTD、版本、TPS |
| [Env](env.md) | 运行时环境信息 + 微秒时间戳 |
| [Worker](worker.md) | 虚拟插件（独立线程）：`createWorker`（**进阶**） |
| [Util](util.md) | 数据工具：gzip、UTF-8 ↔ 字节 |

## 文本与日志

| 文档 | 说明 |
|------|------|
| [Text](text.md) | 文本与 MiniMessage：标记语法、转义规则 |
| [Log](log.md) | 日志：`log` / `Logger` / `console` |
