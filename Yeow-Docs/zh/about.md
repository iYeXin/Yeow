# 关于 Yeow

> **YEOW = Your Entry to an Open World**

## 定位

Yeow 是一个面向 **Minecraft Paper 服务器**的插件开发框架：用 **TypeScript / JavaScript** 编写插件，由运行时（QuickJS 引擎）为每个插件启动**独立的 JS 线程**，通过消息桥与游戏主线程交互。构建产出**平台无关**的插件包（`.yeow.zip`），任何实现平台规范的运行时都能运行同一份插件。

## 设计目标

- **现代工程化**：TypeScript 优先、npm 生态、热重载、构建即产物——把 Web 前端的最佳实践带到 Minecraft 插件开发
- **线程分离**：插件代码不阻塞服务器主线程；每插件独立线程，一个插件崩溃不影响其他插件
- **平台无关**：插件包是纯 ZIP（JS + 资源 + 元信息），不绑定 Java / Bukkit——协议层开放，任何平台可实现运行时
- **原生能力扩展**：Native Service 让插件携带并调用原生程序（Go/Rust/C++），兼顾重计算场景

## 基本原则

### 避免平台绑定（核心）

Yeow 的核心原则是**避免平台绑定**：插件只依赖协议层（消息通道、任务、事件、权限模型），不依赖任何宿主平台的专有 API。Paper/Bukkit 的 `yeow-runtime` 只是官方实现示例。

### 不提供调用 Java 方法的 API

Yeow **不会**提供从 JS 直接调用 Java 方法的 API。原因：

1. **技术困难**：JS 运行在独立的 QuickJS 线程/上下文中，直接调用 Java 方法需要跨线程的同步桥、对象引用与生命周期管理、类型系统映射——复杂度极高且难以保证安全（主线程阻塞、引用泄漏、线程安全）
2. **破坏跨平台兼容性**：允许调用 Java 方法意味着插件代码依赖具体实现（Paper 的类、版本化的 CraftBukkit 包），同一插件无法在其他运行时（其他平台/未来实现）运行——直接违背平台无关的核心原则

需要 Java 侧能力的场景通过**协议层**解决：游戏操作走任务/事件/命令桥；插件间通信走 Service；原生计算走 Native Service；其他 Java 插件可通过 [Java 集成接口](specifications/java-api.md) 调用 Yeow 插件注册的服务。

### 开放与标准

Yeow 的目标是成为 **Minecraft 的 Web 标准**——像 HTML/CSS/JS 之于浏览器：一个开放、跨实现、可持续演进的插件开发标准。任何服务端（Paper、Folia、其他平台）都可以实现一个运行时，运行同一份插件。

## 愿景

Yeow 的愿景是构造**面向未来的、开放的、属于所有人的创造入口**——让每个愿意创造的人，都能用自己熟悉的技术（TypeScript/JS、npm 生态、AI 辅助）进入 Minecraft 的世界，而不被平台、语言或工具链所束缚。

**YEOW = Your Entry to an Open World。**
