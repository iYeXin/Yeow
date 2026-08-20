# Worker API（虚拟插件）

Worker 是**虚拟插件**——独立 QuickJS 上下文 + 独立线程，与主插件共享数据目录与权限，用于 CPU 密集计算、并行批量任务、与主插件线程隔离的长任务。

```js
import { createWorker, onMessage, postMessage } from 'yeow-api';
```

## 主插件侧：createWorker

```js
const worker = createWorker({
    name: 'web-worker',                        // 必填；不允许 'main'；同主插件内唯一（全局可重复）
    entry: getAssetsPath('worker/web-worker.js'),  // 资源路径（经 getAssetsPath）；与 code 互斥
    // code: '...',                             // 代码字符串；与 entry 互斥
});
// createWorker 仅注册到注册表并返回句柄——worker.load() 才真正启动

await worker.load();            // 启动：init.js → worker-inject.js → Worker 代码 → INIT → LOAD（已加载为 no-op）
worker.onMessage((msg) => {     // 接收 Worker 发来的消息
    console.log('from worker:', msg);
});
await worker.postMessage({ task: 'compute', data: [...] });   // 发送给 Worker
await worker.reload();          // 重载代码（需已 load；旧上下文销毁、重新加载）
await worker.unload();          // 卸载（物理销毁 JS 上下文并清理其事件/命令/服务/任务）
await worker.load();            // 卸载后句柄保留——可重新加载
```

校验：`entry` 与 `code` 不可同时传递（抛错）；`name` 必填、非 `'main'`、同主插件内重复创建抛错。

## Worker 侧

```js
import { onMessage, postMessage, onLoad } from 'yeow-api';

onLoad(() => {                  // 生命周期（LOAD 触发，与插件一致）
    console.log('worker ready');
});

onMessage((msg) => {            // 接收主插件消息（worker.postMessage 触发）
    // 处理任务…
    postMessage({ result: 42 });   // 发回主插件（主插件侧 worker.onMessage 接收）
});
```

Worker 与普通插件开发类似：可调用全部 yeow-api（task/fs/http/assets/service/timer 等）。

## 语义与限制

| 项       | 行为                                                                                                                                    |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| 独立实体 | 事件/命令/服务以独立实体注册（`<主插件>.<worker>`）；调度器任务独立统计/清理                                                            |
| 数据目录 | **共享主插件数据目录**（fs 的 plugin 级 = `plugins/<主插件>/`）                                                                         |
| 资源     | **共享主插件资源**（assets 通道同一命名空间）                                                                                           |
| 权限     | **共享主插件权限**（无独立声明）                                                                                                        |
| 嵌套     | **不能创建新的 Worker**（worker 通道被拒绝）                                                                                            |
| 生命周期 | 主插件卸载/热重载时**连带卸载**全部 Worker；**Worker 创建后无法销毁，只能卸载**（`unload` 物理销毁 JS 上下文，句柄保留，可重新 `load`） |
| 管理命令 | `/yeow` 管理命令**不覆盖** Worker                                                                                                       |
| 性能监控 | profiler 统计 Worker（标记 `(worker of <主插件>)`），告警同样检测                                                                       |
| 错误回传 | Worker 的 JS 错误与主插件同样回传（dev 模式 source-map 定位，显示 `JS Error in Worker <name>`）                                         |

> **注册名**：Worker 以 `<主插件>.<worker名>` 注册（保证全局唯一）；`__plugin.name` 为该注册名。

## 开发方式（构建链）

`yeow.config.json` 的 `dev.worker` 配置 worker 打包：

```json
{
    "dev": {
        "worker": [
            { "name": "web-worker", "entry": "worker/web-worker/index.ts", "dist": "assets/worker/web-worker.js" }
        ]
    }
}
```

- 构建时**先打包各 worker**（esbuild，输出到资源目录 `assets/<id>/worker/<name>.js`），再打包主插件——主插件经 `getAssetsPath(dist)` 读取
- 热重载自动检测 `entry` 所在目录变化（重建 + 主插件热重载，Worker 随之重建）
- dev 模式 worker 打包带 sourcemap——Worker 错误反解到源码（`JS Error in Worker <name>` + 代码上下文）
- Worker 与主插件共用 `yeow-api` 依赖

**依赖包作者**：在真实项目中调试，测试成功后自行将打包后的 worker 文件放入资源目录。
