# 权限（Permission）

Yeow 提供自己的权限节点 API——**兼容 Paper 生态**（permissions.yml / LuckPerms 等权限管理插件），同时为 **Yeow 生态**提供更高优先级的权限检查钩子（`permissionCheck` 事件，跨平台、只依赖 Yeow 规范）。

```js
import { registerPermission } from 'yeow-api';

const perm = registerPermission({ node: 'myplugin.home', default: 'all' });
// → { node: 'myplugin.home', default: 'all' }
```

## registerPermission

```ts
registerPermission({ node: string, default?: 'all' | 'op' | 'none' }): Permission
```

注册权限节点（幂等），返回 `Permission` 对象（`{ node, default }`）。`default` 默认 **`'op'`**。

| `default` | 含义 |
|-----------|------|
| `'all'` | 所有人**默认拥有**（普通玩家可用；服主可经权限插件撤销） |
| `'op'` | 仅 op 默认拥有——**默认值** |
| `'none'` | 默认无（需经权限插件/ permissions.yml 授权） |

- 节点**注册进 Paper 系权限系统**（`PermissionDefault` 映射）——Paper 平台可通过 `permissions.yml` 静态声明，或 LuckPerms 等权限管理插件管理
- 粒度较粗：只声明默认值；精细管理交给权限插件（Paper 系）或 `permissionCheck`（Yeow 生态）

## 命令权限

`registerCommand` 的 `permission` 可传：字符串（JS 侧包装为 `{ node, default: 'op' }`）、权限节点对象（`{ node, default }`）或 `registerPermission` 返回值：

```js
import { registerPermission, registerCommand } from 'yeow-api';

const homePerm = registerPermission({ node: 'myplugin.home', default: 'all' });

registerCommand('home', {
    permission: homePerm,              // 或 { node: 'myplugin.home', default: 'all' }，或 'myplugin.home'
    executor: (p) => { /* ... */ },
});
```

- 节点注册进 Paper 系权限系统（权限插件可管理）
- **执行时检查**（命令不设 Paper 系 setPermission 拦截）：`permissionCheck` 事件结果优先，无处理时回退 Paper 系 `hasPermission`；无权限玩家提示 "No permission."；补全不做权限过滤

## 检查权限

插件可在任何地方使用 `player.hasPermission`：

```js
const ok = await player.hasPermission('myplugin.home');   // 字符串
const ok2 = await player.hasPermission(homePerm);         // 权限节点对象
```

检查流程（**仅 Yeow 生态触发** `permissionCheck`；其他 Java 插件的 hasPermission / 命令执行不经过）：

```
player.hasPermission / Yeow 命令执行检查
  → permissionCheck 事件（有 Yeow 插件处理？）
      → 返回 { allowed }  → 采用（覆盖 Paper 系）
      → 不返回            → 回退 Paper 系 hasPermission
```

## permissionCheck（Yeow 生态权限检查）

专为 **Yeow 生态权限管理插件**设计（跨平台、只依赖 Yeow 规范），在 Yeow 生态中**优先级高于 Paper 系权限系统**：

```js
import { eventOn } from 'yeow-api';

eventOn('permissionCheck', (e) => {
    const { target, node, permission } = e;   // permission: { node, default }
    if (node === 'myplugin.home' && isVip(target)) {
        return { allowed: true };   // 覆盖 Paper 系 结果
    }
    // 不返回 → 回退 Paper 系 hasPermission
});
```

- **触发范围（仅限 Yeow 生态）**：`player.hasPermission` 任务 + Yeow 插件注册命令的执行检查——其他 Java 插件的权限检查不经过
- **优先级**：有返回时覆盖 Paper 系；多 handler 返回冲突以**最后一个返回的为准**（不保证执行顺序）；超时（遵循普通事件超时配置 `profile.callback-timeout-event-ms`，默认 5s）视为未处理
- 事件数据：`{ target, node, permission }`——`permission` 为权限对象（含节点默认值）
- **⚠ 普通插件不建议监听**（每次权限检查都触发，影响性能）——为 Yeow 生态权限管理插件保留
- **⚠ 无限循环**：`permissionCheck` handler 中调用 `hasPermission` 会再次触发本检查——**可能导致无限循环**，请避免（如需查询请直接读取自己的数据）

## 权限管理

| 方式 | 适用 | 说明 |
|------|------|------|
| Paper 系权限插件（LuckPerms 等）/ permissions.yml | 服主（Paper 系生态） | 节点已注册进 Paper 系权限系统，可精细授予/撤销 |
| `permissionCheck` 事件 | Yeow 生态权限管理插件 | 任意逻辑（白名单/限时/VIP 等），跨平台，优先级更高 |
