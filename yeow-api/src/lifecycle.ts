// 生命周期钩子注册表（共享全局数组）。
//
// 多 yeow-api 副本共存（npm 语义化版本规则：peer 范围不重叠时依赖包自带
// 独立副本）时，每个副本的模块级数组必须指向**同一个**全局数组——
// 若各副本直接 `globalThis.__yeowInitCbs = <自身数组>`，后加载的副本会
// 覆盖先加载副本注册的回调（表现为 onLoad 不执行）。读已有 / 否则创建：
// 首个加载的副本创建数组，后续副本复用，运行时（init.js）读取同一数组。
const _global = globalThis as any;
const _initCbs: (() => void)[] = _global.__yeowInitCbs || (_global.__yeowInitCbs = []);
const _loadCbs: (() => void)[] = _global.__yeowLoadCbs || (_global.__yeowLoadCbs = []);
const _unloadCbs: (() => void)[] = _global.__yeowUnloadCbs || (_global.__yeowUnloadCbs = []);

// dev 模式栈追踪：在注册点捕获调用栈挂到回调函数上（__yeowNode）。
// 运行时（init.js）分发钩子时优先使用它作为该钩子的栈上下文——比 init.js
// 分发点的内部帧更能还原用户调用链（外层回调经 _getCurrentCbStack 连接）。
// 仅 $dev 时捕获（QuickJS 的 Error.stack 为懒构建 getter，无访问即无成本）。
function _attachNode(cb: () => void): void {
    try {
        if (!_global.$dev) return;
        const getCb = _global._getCurrentCbStack;
        (cb as any).__yeowNode = {
            stack: new Error().stack,
            outer: typeof getCb === 'function' ? getCb() : null,
        };
    } catch { /* 注册点捕获失败不影响钩子注册 */ }
}

export function onInit(cb: () => void): void { _attachNode(cb); _initCbs.push(cb); }
export function onLoad(cb: () => void): void { _attachNode(cb); _loadCbs.push(cb); }
export function onUnload(cb: () => void): void { _attachNode(cb); _unloadCbs.push(cb); }
