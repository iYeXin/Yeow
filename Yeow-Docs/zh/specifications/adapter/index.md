# 插件适配器规范（Adapter）

Yeow 的**标准开发语言是 JavaScript**（官方 JS 适配器）。其他语言通过**社区适配器**支持——适配器是**平台相关的**：为某个宿主平台（如 NeoForge 服务端）提供适配器时，需自行适应其模组/插件结构，但适配器本身的工作量可控：在 Java 平台上，只需实现 `PluginEntity` 接口并注册。

典型适配器形态：

- **Yeow-Python**（Java Paper 插件，内置 CPython）：自行设计 Python 插件包结构、读取插件包、封装 Python 适配器后注册
- **TCP 适配器**：把插件实体映射为远端进程（JSON line 协议），`postMessage` → TCP 推送，远端回报即 pong

## 插件实体接口（PluginEntity）

运行时以 `yeow.PluginEntity` 看待每个插件。适配器实现以下方法：

| 方法                | 契约                                                                                       |
| ------------------- | ------------------------------------------------------------------------------------------ |
| `name()`            | 插件名，**全局唯一**（同名注册被拒绝）                                                     |
| `source()`          | 插件包来源（路径 / 虚拟标识；可 null）                                                     |
| `type()`            | 插件类型标记（如 `"python"`、`"tcp"`）；官方 JS 为 `"js"`                                  |
| `isVirtual()`       | 虚拟插件标记（Worker 等非包实体）——性能统计/告警按此区分                                   |
| `postMessage(json)` | 接收运行时投递的消息（见下文消息契约）                                                     |
| `ping()`            | 心跳：发起一次探测，返回往返纳秒 future；**已有 in-flight ping 时返回 null**（不重复发起） |
| `start()`           | 启动执行单元（注册后由运行时调用）                                                         |
| `stopAndWait()`     | 停止并等待退出（超时后强制终止）——**适配器负责逻辑卸载**                                   |
| `reload(code)`      | 重载（不适用的实现可忽略）                                                                 |

### 消息契约（postMessage 的 JSON）

| 消息                                 | 语义                                                                   |
| ------------------------------------ | ---------------------------------------------------------------------- |
| `{"t":"LOAD"}`                       | 生命周期：注册完成后投递；适配器应在此启动插件逻辑                     |
| `{"t":"DISABLE"}` / `{"t":"RELOAD"}` | 生命周期：停止 / 重载前投递                                            |
| `{"t":"cb","p":"<cbId>","r":<data>}` | 回调：事件数据 / 命令执行 / 命令补全 / 异步结果                        |
| `{"t":"DEBUG","p":"ping"}`           | 心跳探测：实现应回报 pong（经适配器自身通道，完成 `ping()` 的 future） |

完成回报（事件 `event.complete`、补全 `command.tabComplete`、异步结果）经 task 通道回传运行时（SyncCallbackHelper 契约）。适配器可定义自己的内部消息格式，只要满足接口语义。

## 注册 API

适配器构造好实体后调用（**同步、幂等**）：

```java
YeowRuntime.inst().registerPluginEntity(entity);   // 注册 + start + 投递 LOAD
```

运行时负责：同名唯一检查、注册表维护、Profile 指标接入（心跳/任务/事件采集）、卸载时的服务/事件/任务清理（`/yeow unload` 等走同一套 `stopAndWait`）。

### 示例（伪代码）

```java
public class YeowPython extends JavaPlugin {
    @Override public void onEnable() {
        for (var pkg : listPythonPackages()) {
            var entity = new PythonPluginEntity(name, source, script);
            YeowRuntime.inst().registerPluginEntity(entity);
        }
    }
}

class PythonPluginEntity implements PluginEntity {
    // name/source/type("python")/isVirtual(false)
    // postMessage: 投递到 CPython 解释器线程的消息队列（解析 {"t":...} 分发）
    // ping: 向解释器线程发信号并等待往返；in-flight 时返回 null
    // stopAndWait: 停止解释器、执行 Python 侧卸载钩子、等待退出
}
```

## 卸载与 /yeow 管理命令

- **逻辑卸载由适配器实现**：`stopAndWait()` / `reload()` 必须满足接口语义（等待退出、超时强制终止）
- `/yeow` 管理命令目前**不感知适配器插件的存在**——适配器插件（如 Yeow-Python 自身）自行管理其插件的卸载/重载命令
- 同名唯一约束对所有实体一致（含适配器插件注册的实体）

## 依赖与访问

适配器插件在 `plugin.yml` 声明 `depend: [Yeow]`，通过 `YeowRuntime.inst()` 访问注册 API（运行时是 Bukkit 插件实例，也可经 `Bukkit.getPluginManager().getPlugin("Yeow")` 获取）。

## 检查清单（合格适配器）

- [ ] 实现 `PluginEntity` 全部方法，`type()` 返回有意义的标记
- [ ] `postMessage` 线程安全、不阻塞；正确分发 LOAD/DISABLE/RELOAD 生命周期
- [ ] `ping()` 正确管理 in-flight（返回 null 语义）；无响应时 future 保持 pending
- [ ] `stopAndWait()` 完成逻辑卸载并在超时后强制终止
- [ ] 通过 `YeowRuntime.inst().registerPluginEntity(entity)` 注册，同名冲突时优雅处理
- [ ] 平台相关的包结构 / 引擎封装由适配器自行设计（不受本规范约束）
