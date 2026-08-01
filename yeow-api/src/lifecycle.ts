const _initCbs: (() => void)[] = [];
const _loadCbs: (() => void)[] = [];
const _unloadCbs: (() => void)[] = [];

export function onInit(cb: () => void): void { _initCbs.push(cb); }
export function onLoad(cb: () => void): void { _loadCbs.push(cb); }
export function onUnload(cb: () => void): void { _unloadCbs.push(cb); }

(globalThis as any).__yeowInitCbs = _initCbs;
(globalThis as any).__yeowLoadCbs = _loadCbs;
(globalThis as any).__yeowUnloadCbs = _unloadCbs;
