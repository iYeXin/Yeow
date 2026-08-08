# API 参考

按模块分组的完整 API 索引。标注 ⭐ 为常用 API。

## 玩家与服务器

| 文档 | 说明 |
|------|------|
| ⭐ [Player](player.md) | 玩家：属性、位置、消息、传送、权限 |
| [Server](server.md) | 服务器级：广播、MOTD、图标、版本 |

## 世界与方块

| 文档 | 说明 |
|------|------|
| ⭐ [World](world.md) | 世界：时间、天气、方块、实体、爆炸 |
| [Chunk](chunk.md) | 区块快照（批量方块索引，进阶性能工具） |
| ⭐ [Location](location.md) | 坐标与朝向 |
| [Block](block.md) | 方块：类型、固体/液体、自然破坏 |
| [Material](material.md) | 材料/方块/物品枚举查询 |

## 实体

| 文档 | 说明 |
|------|------|
| ⭐ [Entity](entity.md) | 实体：类型、位置、发光、无敌、传送 |
| [Potion](potion.md) | 药水效果：添加/移除/查询 |
| [Particle](particle.md) | 粒子生成 |

## 交互界面

| 文档 | 说明 |
|------|------|
| [GUI](gui.md) | 容器界面：创建、开合、设物品 |
| [Inventory](inventory.md) | 玩家物品栏：槽位、增减物品 |
| [BossBar](bossbar.md) | 血条：标题、进度、颜色 |
| [Scoreboard](scoreboard.md) | 计分板：目标、队伍 |
| [Advancement](advancement.md) | 进度：授予/撤销 |
| [Recipe](recipe.md) | 配方：添加/移除 |

## 事件与命令

| 文档 | 说明 |
|------|------|
| ⭐ [Event](event.md) | 事件订阅：`eventOn` / `eventOff` |
| ⭐ [Command](command.md) | 命令注册 + Tab 补全（含 `yeow-utils` 重载式命令） |

## 服务与网络

| 文档 | 说明 |
|------|------|
| ⭐ [Service](service.md) | 插件间/原生服务：注册、请求、订阅 |
| [HTTP](http.md) | 底层 HTTP 客户端 |
| [HTTP Server](http-server.md) | HTTP 服务端（`yeow-utils` `createServer`） |

## 文件与数据

| 文档 | 说明 |
|------|------|
| ⭐ [FS](fs.md) | 文件系统读写（含 `path` 工具） |
| ⭐ [Assets](assets.md) | 打包资源：`getAssetsPath`（`yeow-dev`）+ 读取/解压 |
| [PDC](pdc.md) | 持久数据容器 |

## 日志

| 文档 | 说明 |
|------|------|
| ⭐ [Log](log.md) | 日志：`log` / `Logger` / `console` |

## 文本

| 文档 | 说明 |
|------|------|
| ⭐ [Text](text.md) | 文本与 MiniMessage：标记语法、转义规则 |
