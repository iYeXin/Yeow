// ── Worker 注入（在 init.js 之后 evaluate）────────────────────────────
// Worker 是虚拟插件：独立 QuickJS 上下文，共享主插件数据目录与权限。
globalThis.__workerId = "__WORKER_ID__";
globalThis.__workerMain = "__MAIN__";

const _msgCbs = [];
globalThis._workerOnMessage = (msg) => {
    for (const cb of [..._msgCbs]) {
        try { cb(msg); } catch (ex) { reportError(ex); }
    }
};

// 主插件 → worker 消息：Java 投递 {"t":"cb","p":"<该 id>","r":msg}
const _mid = _registerCallback((msg) => { _workerOnMessage(msg); }, { persistent: true });
globalThis.__workerMessageCbId = _mid;
