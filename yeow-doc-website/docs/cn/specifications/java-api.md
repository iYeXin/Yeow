# Java 插件调用 Yeow 插件的服务（实验性）

Yeow 运行时（Paper 插件）对**其他 Java 插件**公开集成接口：调用 Yeow 插件注册的服务、订阅服务事件，以及提交游戏任务（适配器入口）。

> 适用：Java 插件 `plugin.yml` 声明 `depend: [Yeow]`，经 `Bukkit.getPluginManager().getPlugin("Yeow")` 获取 `YeowRuntime` 实例。

## 调用服务（请求-响应）

```java
import yeow.YeowRuntime;
import com.google.gson.JsonObject;

var rt = (YeowRuntime) Bukkit.getPluginManager().getPlugin("Yeow");

JsonObject body = new JsonObject();
body.addProperty("cmd", "status");

rt.requestService("my-plugin.svc.v1", "/status", body, result -> {
    // result：gson 解析对象（Map/List/String/Number/Boolean）
    // 服务不存在 / 请求失败时收到 {"err": <message>}
});
```

- `serviceId`：Yeow 插件 `registerService` 注册的服务 ID
- `path`：服务内路径（JS 侧 `onRequest(path, body)` 接收）
- 回调在**运行时线程**调用（非主线程）；如需主线程操作请 `Bukkit.getScheduler().runTask(...)` 转发
- 内部实现：`ServiceManager.requestJava`——请求经与 JS 调用方相同的链路路由到服务所有者，响应回调直达

## 订阅服务事件

```java
AutoCloseable sub = rt.subscribeService("my-plugin.svc.v1", "status", payload -> {
    // payload：{ "serviceId": "...", "eventPath": "...", "body": {...} }
});

sub.close();   // 取消订阅
```

- `eventPath`：服务方 `publish(token, eventPath, body)` 发布的事件路径（精确匹配）
- 回调载荷为事件数据对象（`serviceId` / `eventPath` / `body`）
- 同一回调引用可重复订阅不同服务/路径；`close()` 按回调引用移除

## 提交游戏任务

适配器与 Java 插件均可提交游戏任务（`$_send('task', ...)` 的等价入口）：

```java
String result = YeowRuntime.inst().submitTask(entity, json);   // 同步（无 cb）/ 异步（含 cb）
```

- 同步：阻塞返回结果 JSON；异步：立即返回 null，结果经 `entity.postMessage` 回投 `{"t":"cb","p":"<cbId>","r":<data>}`
- `entity`：插件实体（JS 插件为 `PluginThread`；Java 插件集成通常不需要提交任务——需要时构造自定义 `PluginEntity` 见[适配器规范](adapter/index.md)）

## 约束

- 需确保 Yeow 运行时实例存在时
- 回调为运行时线程调用——**不要**在回调中直接调用 Paper 系主线程 API（除线程安全的部分）；必要时转发主线程
- 请求/订阅生命周期不随插件卸载自动清理（Java 插件自行管理 `AutoCloseable`）
