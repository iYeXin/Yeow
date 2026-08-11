# 概览

Yeow 是一个面向 Minecraft Paper 服务器的插件开发框架：用 **TypeScript / JavaScript** 编写插件，运行时（QuickJS 引擎）为每个插件启动**独立的 JS 线程**，通过消息桥与游戏主线程交互。

插件代码不阻塞服务器主线程，一个插件崩溃不影响其他插件；构建产出平台无关的 `.yeow.zip` 插件包，任何实现[平台规范](specifications/README.md)的运行时都能运行同一份插件。

> Yeow 暂未正式发布。Yeow 在正式发布前不保证 API 的稳定性。

> 如果 Modrinth 上的 Yeow 项目仍未结束 Under Review 状态，可以[点此下载](https://raw.githubusercontent.com/iYeXin/Yeow/main/create-yeow/templates/default/.yeow/assets/yeow-runtime-0.1.0.jar) Yeow 运行时插件。

文档内容较多，不确定从哪里开始？按你的角色找到入口：

## 按角色导引

| 你的角色         | 目标                               | 从这里开始                                                                                                                                      |
| ---------------- | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| **初学者**       | 了解 Yeow 能做什么、上手第一个插件 | [快速开始](getting-started.md) 从零创建项目并部署；[构建与分发](distribution.md) 了解两种产物格式                                               |
| **插件开发者**   | 日常写插件、查 API                 | [快速开始](getting-started.md) 掌握生命周期与异步/同步约定；[API 参考](api/README.md) 按模块查文档                                              |
| **进阶学习者**   | 理解底层机制、排查性能问题         | [进阶知识](advanced.md) 架构、线程模型、三级调度器；[运行时警告](runtime-warning.md) 告警类型与解决方案                                         |
| **依赖包开发者** | 封装可复用的 npm 包                | [编写依赖包](package-author.md) 资源封装、三类 Service 包（SDK / JS 服务 / 原生服务）与权限声明                                                 |
| **插件使用者**   | 服务器管理员：安装与管理插件       | [构建与分发](distribution.md) 部署方式与一键安装；[快速开始 - 插件管理命令](getting-started.md#插件管理命令)（`/yeow load/install/update/...`） |
| **平台实现者**   | 实现 Yeow 兼容运行时               | [平台规范](specifications/README.md) 包结构、消息协议、任务/事件/运行时环境标准                                                                 |
| **适配器开发者** | 让其他语言/引擎接入 Yeow           | [适配器规范](specifications/adapter/index.md) `PluginEntity` 接口、注册 API 与消息契约                                                          |

## 文档地图

```
快速开始      getting-started.md     创建 → 开发 → 构建 → 部署（生命周期、权限声明、/yeow 命令）
API 参考      api/README.md           按模块分组的完整索引（Player / World / Event / Service …）
进阶知识      advanced.md             架构、线程模型、调度器、热重载、安全
构建与分发    distribution.md         .jar 与 .yeow.zip 两种格式、一键安装
编写依赖包    package-author.md       资源与 Service 封装、权限声明
运行时警告    runtime-warning.md      告警类型、原因、解决方案
路线图        todo.md                 开发调试工具 / Folia 支持 / Worker API（v1 规划）
平台规范      specifications/          协议层（包结构、消息、任务、事件、运行时）
```

## 关键概念速览

- **生命周期**：游戏 API 操作需在 `onLoad` 内进行；`onInit` 前仅注册回调
- **异步优先**：API 默认返回 `Promise`，同步操作加 `Sync` 后缀；大量操作用 `await` 循环而非同步阻塞
- **权限声明**：`fs` / `http` / 原生服务 / 资源解压等敏感操作需在 `yeow.config.json` 中声明，未声明调用返回错误
- **插件包**：`.yeow.zip` 平台无关，放入 `plugins/Yeow/` 自动加载，或 `/yeow install <url>` 一键安装

> 要求：Node.js 18+（开发）· Java 21+（可选，开发服务器必需）。

> **版本范围**：Yeow 插件的目标运行环境是 **Minecraft 1.18+**（更低版本理论上可运行，但可能出现功能异常）。开发服务器默认使用 Paper **1.21.4**——这不是硬性要求，可在 `yeow.config.json` 的 `paperVersion` / `paperJar` 中配置为任意支持的 Paper 版本。
