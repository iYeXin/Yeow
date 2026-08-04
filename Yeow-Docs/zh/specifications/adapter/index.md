# 插件适配器规范（Adapter）

Yeow 的**标准开发语言是 JavaScript**（官方 JS 适配器）。其他语言通过**社区适配器**支持——适配器是**平台相关的**：为某个宿主平台（如 NeoForge 服务端）提供适配器时，需自行适应其模组/插件结构，但适配器本身的工作量可控：在 Java 平台上，只需实现 `PluginEntity` 接口并注册。

Yeow v1 的多语言支持暂不完善，我们推荐使用 JavaScript/TypeScript。如果使用其他语言进行开发，开发者体验、用户体验、性能、资源占用以及插件安全模型的可靠性，高度依赖于语言本身的特性、适配器作者的设计以及适配器的实现质量。Yeow v1 不对其他开发方案的可用性做任何保证。

下面是 Yeow 在 Paper/Bukkit 平台上的插件适配器规范。如果其他平台也实现了 Yeow 运行时，请适配器作者参考他们的规范进行开发。

典型适配器形态：

- **Yeow-Python**（Java Paper 插件，内置 CPython 动态库）：自行设计 Python 插件包结构、读取插件包、封装 Python 适配器后注册
- **TCP 适配器**：把插件实体映射为远端进程或网络服务
- **WASM 适配器**（Java 插件，内置 WASM 运行时，如 wasmtime）：插件以 WebAssembly 模块（`.wasm`）打包，适配器负责加载模块、建立宿主 API 桥接（`postMessage` → 导入函数调用、`submitTask` 结果回投）与 `ping()` 探测。一些典型特性：
  - **跨平台**：WASM 模块一次编译，可在任意支持 WASM 的宿主上运行，无平台和语言绑定，支持任何能编译为 WASM 的语言
  - **性能高**：接近原生执行速度（AOT/JIT 编译），无解释器开销
  - **沙箱可控性强**：WASM 线性内存与导入/导出边界天然隔离，插件无法逃逸出宿主授予的能力（无文件系统/网络访问，除非显式导入），安全模型清晰
  - **资源占用较轻**：内存上限可配置（线性内存 + 栈），无完整 VM/解释器驻留开销；一个服务器可承载大量 WASM 插件实例
  - **一些劣势**：WASM 插件开发门槛较高，短期内生态发展速度可能受限；多数游戏插件代码胶水性质强，对性能要求不高，WASM 与 JS 相比优势不大；不良好的设计造成的通信开销（如频繁序列化/反序列化）可能稀释 WASM 的性能；开发工具链需深度适配，否则开发体验受限

## 插件实体接口（PluginEntity）

运行时以 `yeow.PluginEntity` 看待每个插件。适配器实现以下方法：

| 方法                   | 契约                                                                                                                                 |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `name()`               | 插件名，**全局唯一**（同名注册被拒绝）                                                                                               |
| `source()`             | 插件包来源（路径 / 虚拟标识；可 null）                                                                                               |
| `type()`               | 插件类型标记（如 `"python"`、`"tcp"`）；官方 JS 为 `"js"`                                                                            |
| `isVirtual()`          | 虚拟插件标记（Worker 等非包实体）——性能统计/告警按此区分                                                                             |
| `postMessage(message)` | 接收运行时投递的消息——**JSON 字符串或 POJO**，适配器自行决定是否序列化（String 按消息契约消化；POJO 可序列化为自身格式或按字段处理） |
| `ping()`               | 心跳：发起一次探测，返回往返纳秒 future；**已有 in-flight ping 时返回 null**（不重复发起）                                           |
| `start()`              | 启动执行单元（注册后由运行时调用）                                                                                                   |
| `stopAndWait()`        | 停止并等待退出（超时后强制终止）——**适配器负责逻辑卸载**                                                                             |
| `reload(code)`         | 重载（不适用的实现可忽略）                                                                                                           |

### 消息契约（postMessage 的 JSON）

| 消息                                 | 语义                                                                   |
| ------------------------------------ | ---------------------------------------------------------------------- |
| `{"t":"LOAD"}`                       | 生命周期：注册完成后投递；适配器应在此启动插件逻辑                     |
| `{"t":"DISABLE"}` / `{"t":"RELOAD"}` | 生命周期：停止 / 重载前投递                                            |
| `{"t":"cb","p":"<cbId>","r":<data>}` | 回调：事件数据 / 命令执行 / 命令补全 / 异步结果                        |
| `{"t":"DEBUG","p":"ping"}`           | 心跳探测：实现应回报 pong（经适配器自身通道，完成 `ping()` 的 future） |

完成回报（事件 `event.complete`、补全 `command.tabComplete`、异步结果）经 task 通道回传运行时（SyncCallbackHelper 契约）。适配器可定义自己的内部消息格式，只要满足接口语义。

## 提交游戏任务

适配器通过运行时 API 提交游戏任务（**唯一共有的消息接口**，等价于 JS 插件的 `$_send('task', ...)`）：

```java
String result = YeowRuntime.inst().submitTask(entity, json);   // JSON 字符串
JsonObject msg = new JsonObject(); /* ... */ 
String r2 = YeowRuntime.inst().submitTask(entity, msg);        // POJO 直接使用（零序列化）
```

- `json`：`{"type":"player.get","params":{...},"cb":"<id>","priority":"high"}`
- 含 `cb` → 异步（立即返回 null），结果经 `postMessage` 回投 `{"t":"cb","p":"<cbId>","r":<data>}`；`cbId` 由适配器自行生成与管理
- 无 `cb` → 同步阻塞返回结果 JSON
- `postMessage` 与 `submitTask` 均接受 **JSON 字符串或 POJO**：POJO **直接使用**（避免序列化开销）——gson `JsonObject` 零转换直接执行，一般 POJO 由运行时一次转换

**其他通道与权限模型都是 JS 插件特有的**（`service` / `fs` / `http` / `timer` / `log` / `debug` / `lifecycle` 及声明式权限）——适配器根据自身情况处理：例如 CPython 自带庞大的标准库，无需运行时提供 log / fs 等辅助，但同时，安全模型也更依赖适配器作者的设计。

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
