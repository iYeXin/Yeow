# 服务机制

> 服务机制（机制视角）：Plugin Service（插件间通信）与 Native Service（原生能力扩展）。**API 用法**见 [Service API](/api/service)；**依赖包中封装服务**见 [封装 Service 的依赖包](/package-service)；**子进程 TCP 协议**见 [Native Service 规范](/specifications/native-service/index)。

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
- **可信性与批准**：原生二进制支持 SHA-256 可信性声明；声明了原生服务的插件默认需要控制台批准才能加载——见 [权限与原生服务可信性](/permissions)
- **协议**：见 [Native Service 规范](/specifications/native-service/index)

API 用法见 [Service API](/api/service)。
