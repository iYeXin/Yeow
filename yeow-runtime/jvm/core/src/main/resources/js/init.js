// ── 底层桥闭包（内部实现）──────────────────────────────────────────
// $_send 是 JS→Java 的原始桥接函数，属内部实现：init.js 以闭包持有后
// 立即从全局对象移除——插件代码（含依赖包）只能使用封装后的 $send。
// （Java 侧在 evaluate(init.js) 之前注入该全局属性，见 PluginThread.inject）
const $_send = globalThis.$_send;
try {
    if (!delete globalThis.$_send) {
        // 属性不可删除（异常宿主）：覆盖为抛错桩，同样阻断外部直连
        globalThis.$_send = () => { throw new Error('$_send is internal; use $send(channel, payload)'); };
    }
} catch (ex) { /* 宿主禁止任何改写：保持原样（罕见） */ }

// ── Callback Registry ───────────────────────────────────────────────
const _cbs = {};
// 每上下文随机基址：热重载/重载创建新上下文后回调 id 不会与旧代重叠——
// 旧代消息（旧定时器/在途分发/迟到异步结果）携带的旧 cbId 在新上下文查无此回调，
// 被静默丢弃，杜绝"同序号不同用途"的跨代串扰（曾导致事件 handler 被错误数据调用，
// 如 playerDeath handler 收到 entityDeath/定时器载荷 → e.player 不存在）。
let _cbSeq = Math.floor(Math.random() * 0x3fffffff) + 1;

// ── 栈片段（StackNode）───────────────────────────────────────────
// 注册/传播时只存引用链（不拼接字符串）：每个片段只捕获一次
// `new Error().stack`，外层回调通过 outer 引用连接。报错时才构建
// 最终字符串——先合并连续相同片段，再截取（输出 6+6）。
// 链段存储有界：合并后最多 MAX_STORED_SEGMENTS 段——保留栈帧起始，
// 结尾 STORED_TAIL 段作为滑动窗口持续更新（窗口最旧段丢弃计入省略）。
const MAX_STACK_SEGMENTS = 12;   // 输出上限（KEEP_HEAD + KEEP_TAIL）
const KEEP_HEAD = 6;             // 输出保留首段数
const KEEP_TAIL = 6;             // 输出保留尾段数
const MAX_STORED_SEGMENTS = 50;  // 链段存储上限（合并后）
const STORED_TAIL = 10;          // 存储滑动窗口段数（结尾最新 10 段）

/** 展开节点链 → 段列表（cb registered + outer 链 + promise chain 段）。 */
function _expandSegments(node, chainNodes) {
    const segs = [];
    if (chainNodes) {
        for (const n of chainNodes) _pushSeg(segs, n, '    --- promise chain ---\n');
    }
    let cur = node;
    let first = true;
    while (cur) {
        _pushSeg(segs, cur, first ? '    --- cb registered at ---\n' : '    --- outer callback ---\n');
        first = false;
        cur = cur.outer;
    }
    return segs;
}

function _pushSeg(segs, node, marker) {
    if (node && node.stack) segs.push({ stack: node.stack, marker, count: node.count || 1 });
}

/** 合并连续相同片段（保留计数）；超过输出上限保留首 6 + 尾 6，中间标记省略（含存储层省略数）。 */
function _dedupeAndTrim(segs, baseOmitted) {
    const out = [];
    for (const s of segs) {
        const last = out[out.length - 1];
        if (last && !last.omitted && last.stack === s.stack && last.marker === s.marker) {
            last.count = (last.count || 1) + 1;
        } else {
            out.push({ stack: s.stack, marker: s.marker, count: s.count || 1 });
        }
    }
    if (out.length <= MAX_STACK_SEGMENTS) return baseOmitted > 0
        ? [{ omitted: true, marker: '    ... (' + baseOmitted + ' segments omitted) ...\n' }].concat(out)
        : out;
    const head = out.slice(0, KEEP_HEAD);
    const tail = out.slice(out.length - KEEP_TAIL);
    const omitted = baseOmitted + out.length - KEEP_HEAD - KEEP_TAIL;
    return head.concat([{ omitted: true, marker: '    ... (' + omitted + ' segments omitted) ...\n' }], tail);
}

/** 构建最终栈字符串（报错时才调用）。chainOmitted：存储层已丢弃的链段数。 */
function _buildStackText(originalStack, node, chainNodes, chainOmitted) {
    const segs = _dedupeAndTrim(_expandSegments(node, chainNodes), chainOmitted || 0);
    let text = originalStack || '';
    for (const s of segs) {
        if (s.omitted) { text += '\n' + s.marker; continue; }
        text += '\n' + s.marker + s.stack + (s.count > 1 ? '\n    (×' + s.count + ')' : '');
    }
    return text;
}

/**
 * 链段存储维护（每错误对象一份）：先合并连续相同片段（计数），再截取——
 * 合并后最多 MAX_STORED_SEGMENTS 段：保留栈帧起始（前 keepHead 段固定），
 * 结尾 STORED_TAIL 段为滑动窗口——新段到达时窗口最旧段丢弃并计入省略数。
 */
function _pushChainSeg(ex, node) {
    let segs = ex.__yeowChainNodes;
    if (!segs) {
        segs = ex.__yeowChainNodes = [];
        ex.__yeowChainOmitted = 0;
        ex.__yeowChainSliding = false;
    }
    const last = segs[segs.length - 1];
    if (last && last.stack === node.stack) {          // ① 先合并（链段 marker 固定相同）
        last.count = (last.count || 1) + 1;
        return;
    }
    segs.push({ stack: node.stack, count: 1 });
    // ② 再截取：超存储上限 → 保留起始，结尾 STORED_TAIL 段滑动更新
    if (segs.length > MAX_STORED_SEGMENTS) {
        const keepHead = MAX_STORED_SEGMENTS - STORED_TAIL;
        if (!ex.__yeowChainSliding) {
            ex.__yeowChainOmitted += segs.length - keepHead - STORED_TAIL;
            segs.splice(keepHead, segs.length - keepHead - STORED_TAIL);
            ex.__yeowChainSliding = true;
        } else {
            ex.__yeowChainOmitted++;                  // 窗口最旧段丢弃
            segs.splice(keepHead, 1);
        }
    }
}

/**
 * 栈增强：原始栈只快照一次（__yeowRawStack），此后每次节点变化都从原始栈
 * 幂等重建——报错路径与 native 逃逸路径（未处理 rejection / job 抛错，
 * 快照的是错误当前的 stack 字符串）拿到的都是完整栈。
 */
function _rebuildStack(ex) {
    if (!globalThis.$dev || !ex || typeof ex !== 'object') return;
    try {
        if (ex.__yeowRawStack == null && typeof ex.stack === 'string') ex.__yeowRawStack = ex.stack;
        if (typeof ex.__yeowRawStack !== 'string') return; // 非 Error（无原始栈）不加工
        ex.stack = _buildStackText(ex.__yeowRawStack, ex.__yeowStackNode || null, ex.__yeowChainNodes || null, ex.__yeowChainOmitted || 0);
    } catch (err) { /* 栈加工失败不影响错误传播 */ }
}

/** 把当前回调栈片段挂到错误对象（节点变化后重建，幂等）。 */
function _enhanceStack(ex, node) {
    if (!globalThis.$dev || !node || !ex || typeof ex !== 'object') return;
    if (!ex.__yeowStackNode) ex.__yeowStackNode = node;
    _rebuildStack(ex);
}

let _currentCbStack = null; // 当前回调的 StackNode（或 null）
globalThis._getCurrentCbStack = () => _currentCbStack;
globalThis._attachCbStack = (err) => { if (err && typeof err === 'object') _enhanceStack(err, _currentCbStack); };

globalThis._registerCallback = (fn, { persistent = false } = {}) => {
    const id = 'cb_' + (_cbSeq++);
    const regNode = globalThis.$dev ? { stack: new Error().stack, outer: _currentCbStack || null } : null;
    // 片段链只存引用——不在此处拼接字符串（嵌套注册 O(1)）。
    _cbs[id] = {
        h: function $cb() {
            if (globalThis.$dev && regNode) _currentCbStack = regNode;
            try {
                const result = fn.apply(this, arguments);
                if (result && typeof result.then === 'function') {
                    // In dev the Promise.prototype.then interceptor already appends
                    // the callback registration stack ("--- promise chain ---");
                    // here we only report. In prod _enhanceStack is a no-op anyway.
                    return result.then(null, ex => { reportError(ex); });
                }
                return result;
            } catch (ex) { _enhanceStack(ex, regNode); throw ex; }
        },
        persistent,
        stack: regNode,
    };
    return id;
};
globalThis._unregisterCallback = (id) => { delete _cbs[id]; };

// ── Console ─────────────────────────────────────────────────────────
const _s = (v) => { try { return String(v); } catch { return '?'; } };
const _prefix = () => { try { return globalThis.__plugin ? '[' + globalThis.__plugin.name + '] ' : ''; } catch { return ''; } };
const _log = (level, ...args) => { try { $_send('log', JSON.stringify({ level, message: _prefix() + args.map(_s).join(' ') })); } catch (ex) { } };
globalThis.console = {
    log: (...a) => _log('INFO', ...a),
    warn: (...a) => _log('WARN', ...a),
    error: (...a) => _log('ERROR', ...a),
    info: (...a) => _log('INFO', ...a),
};

// ── $send (high-level bridge) ─────────────────────────────────────
// $_send(channel, jsonString) is the low-level Java bridge.
// $send(channel, payload) wraps it with JSON conversion.
globalThis.$send = (channel, payload) => {

    const raw = $_send(channel, JSON.stringify(payload));
    if (raw == null) return null;
    // 防御：非 JSON 返回（如错误文本）原样返回，避免 JSON.parse 抛 SyntaxError 掩盖真实结果
    try { return JSON.parse(raw); } catch (ex) { return raw; }
};

// ── Timers ──────────────────────────────────────────────────────────
const _timers = {};
let _tSeq = 1;
globalThis.setTimeout = (fn, ms) => {
    const id = 't' + (_tSeq++); const cb = _registerCallback(() => { delete _timers[id]; return fn(); }); _timers[id] = cb;
    $_send('timer', JSON.stringify({ type: 'timeout', cb, delay: ms || 0 })); return id;
};
globalThis.clearTimeout = (id) => { const c = _timers[id]; if (c) { _unregisterCallback(c); try { $_send('timer', JSON.stringify({ type: 'clear', cb: c })); } catch (ex) { /* ignore */ } } delete _timers[id]; };
globalThis.setInterval = (fn, ms) => {
    const id = 'i' + (_tSeq++);
    // 丢弃 Java 投递的载荷实参（interval 回调投递 r=true）：标准语义下 interval 回调无参
    //（setTimeout 已由外层包装忽略实参，此处同样包装保持一致）。
    const cb = _registerCallback(() => fn(), { persistent: true });
    _timers[id] = cb;
    $_send('timer', JSON.stringify({ type: 'interval', cb, delay: ms || 0 })); return id;
};
globalThis.clearInterval = globalThis.clearTimeout;

// ── Error Reporter ────────────────────────────────────────────────
function _parseStack(stack) {
    if (!stack) return {};
    const m = stack.match(/at\s+(?:\S+\s+)?\(?([^\s(]+):(\d+):(\d+)\)?/);
    return m ? { fileName: m[1], lineNumber: parseInt(m[2]), columnNumber: parseInt(m[3]) } : {};
}

/** 构建最终栈：节点或链任一存在即重建（幂等；原始栈快照保证不嵌套累积）。 */
function _finalStack(e) {
    if (!globalThis.$dev || !e || typeof e !== 'object') return e?.stack || '';
    if (e.__yeowStackNode || e.__yeowChainNodes) _rebuildStack(e);
    return e?.stack || '';
}

function reportError(e) {
    var msg = e?.message || String(e);
    // UnhandledPromiseRejectionException often has a truncated message;
    // try to extract a better one from stack or the detail property
    if (!msg || msg === 'not a function' || msg === 'undefined' || msg.length < 5) {
        if (e?.stack) {
            var firstLine = e.stack.split('\n')[0].trim();
            if (firstLine && firstLine.length > msg.length) msg = firstLine;
        }
    }
    const info = {
        message: msg,
        stack: _finalStack(e),
        fileName: e?.fileName || 'main.js',
        lineNumber: e?.lineNumber || 0,
        columnNumber: e?.columnNumber || 0,
    };
    if (!info.fileName || info.fileName === 'main.js' || !info.lineNumber) {
        const parsed = _parseStack(e?.stack);
        if (parsed.fileName) info.fileName = parsed.fileName;
        if (parsed.lineNumber) info.lineNumber = parsed.lineNumber;
        if (parsed.columnNumber) info.columnNumber = parsed.columnNumber;
    }
    try { $_send('debug', JSON.stringify({ t: 'reportError', p: info })); }
    catch (ex) { console.error('js', msg); } // debug 通道失败时兜底到 console（log 通道）——正常路径由 Java 侧统一打印，避免双份日志
}

// ── InstanceId GC ──────────────────────────────────────────────────
// FinalizationRegistry callbacks fire as JS_PendingJob microtasks
// during ctx.executePendingJob(). They push collected ids here.
// Flushed at end of each $hm dispatch cycle.
const _gcCollected = [];
globalThis.__yeowGcQueue = _gcCollected;
function _flushGC() {
    if (_gcCollected.length > 0) {
        const ids = _gcCollected;
        try {
            $_send('lifecycle', JSON.stringify({ type: 'gc-collect', ids }));
            // 发送成功后才清空（同数组原地置空，__yeowGcQueue 引用不变）：
            // 失败时保留 id，待下一次消息分发再冲刷，防止句柄回收上报丢失
            ids.length = 0;
        } catch (ex) { /* 保留 ids，下次再试 */ }
    }
}

// ── Message Dispatcher ──────────────────────────────────────────────

function _runLifecycleCallbacks(cbs) {
    for (var i = 0; i < cbs.length; i++) {
        var cb = cbs[i];
        var node = null;
        if (globalThis.$dev) {
            // 优先使用 yeow-api 在注册点捕获的用户调用栈（__yeowNode，P6）；
            // 旧版 yeow-api 副本无该属性时退回落差（分发点上下文），
            // 保证钩子内 .then() 链仍有根节点。下一条消息清除/覆盖，无残留。
            node = (cb && cb.__yeowNode) ? cb.__yeowNode
                : { stack: new Error().stack, outer: _currentCbStack || null };
            _currentCbStack = node;
        }
        try {
            var result = cb();
            if (result && typeof result.then === 'function') {
                result.then(null, function (ex) { reportError(ex); });
            }
        } catch (ex) {
            if (node) _enhanceStack(ex, node);
            reportError(ex);
        }
    }
}

function _hm(msg) {
    const t = msg.t, p = msg.p, r = msg.r;

    // Non-callback messages (INIT/LOAD/DISABLE/RELOAD/DEBUG) must NOT carry the
    // previous callback's context — clear it so top-level / lifecycle async work
    // is never misattributed to an unrelated earlier callback.
    if (t !== 'cb' && t !== 'CALLBACK') {
        if (globalThis.$dev) _currentCbStack = null;
    }

    if (t === 'INIT') {
        _runLifecycleCallbacks(globalThis.__yeowInitCbs || []);
        return;
    }

    if (t === 'LOAD') {
        _runLifecycleCallbacks(globalThis.__yeowLoadCbs || []);
        return;
    }

    if (t === 'DISABLE') {
        _runLifecycleCallbacks(globalThis.__yeowUnloadCbs || []);
        globalThis.$send('lifecycle', { type: 'unloadDone' });
        return;
    }

    if (t === 'RELOAD') {
        _runLifecycleCallbacks(globalThis.__yeowUnloadCbs || []);
        globalThis.$send('lifecycle', { type: 'unloadDone' });
        return;
    }

    if (t === 'DEBUG') {
        if (p === 'ping') {
            globalThis.$send('debug', { t: 'pong' });
        }
        return;
    }

    // CALLBACK — all callbacks go here: events, tab complete, post results
    if (t === 'cb' || t === 'CALLBACK') {
        const e = _cbs[p];
        if (e) {
            // Keep _currentCbStack as the "most recent callback context": it is
            // overwritten by the next callback dispatch and deliberately NOT
            // cleared here — microtasks scheduled by this callback (e.g. an
            // await continuation) run after it returns, and their .then() calls
            // still need this context to reconstruct the user call chain.
            if (globalThis.$dev) _currentCbStack = e.stack;
            // 一次性回调先注销再调用：handler 同步抛错时（$cb 包装会重新抛出）
            // 不能因 delete 在调用之后而泄漏注册表项。
            if (!e.persistent) delete _cbs[p];
            try {
                e.h(r);
            } catch (ex) { _enhanceStack(ex, e.stack); throw ex; }
        }
        return;
    }
}

globalThis.$hm = (json) => {
    try {
        _hm(JSON.parse(json));
        return null;
    } catch (e) {
        if (e instanceof SyntaxError) return null;
        throw e;
    } finally {
        // 消息分发异常（如用户回调抛错）时也必须冲刷 GC 队列，防止句柄回收上报延迟/丢失
        _flushGC();
    }
};

// ── Dev-only: promise chain stack tracing ─────────────────────────────
// Intercept Promise.prototype.then to record the call site of every visible
// .then() link. When a promise rejects, the recorded stack is appended to the
// error — so errors that travel through several .then() hops keep every hop's
// origin (user frames are captured via _currentCbStack inside callbacks).
// Note: `await` is NOT intercepted (engine-internal), so async-function
// intermediate frames remain unavailable — this only covers visible .then chains.
// 每次打标即重建栈字符串（_rebuildStack）：未处理 rejection / job 抛错经
// native wrapper 逃逸时快照的是错误当前的 stack——逃逸口无法再挂 JS 钩子，
// 必须保证逃逸前链已展开。链段存储先合并、上限 50（保留起始 40 段 + 结尾
// 10 段滑动更新）；输出构建时再合并并 6+6 截取。
if (globalThis.$dev) {
    const _origThen = Promise.prototype.then;
    Promise.prototype.then = function (onFulfilled, onRejected) {
        const attachNode = globalThis._getCurrentCbStack() || { stack: new Error().stack, outer: null };
        const mark = function (reason) {
            if (reason && typeof reason === 'object') {
                _pushChainSeg(reason, attachNode);
                _rebuildStack(reason);
            }
            return reason;
        };
        // Wrap onFulfilled: if the handler itself throws, the resulting promise
        // rejects WITHOUT any reaction on it — the rejection would go straight to the
        // unhandled-rejection channel with a bare single frame. Catching the throw here
        // marks the error before it enters the result promise.
        const wrappedFulfilled = typeof onFulfilled === 'function'
            ? function (v) {
                try { return onFulfilled(v); }
                catch (e) { throw mark(e); }
            }
            : onFulfilled;
        // Wrap onRejected symmetrically: 其自身抛出的新错误同样打标；
        // 无 handler 的透传 hop 也必须重抛 reason（等价规范内建 Thrower——
        // 若 return reason 会把 rejection 吞成 fulfillment，下游 catch 永不触发）。
        const wrappedRejected = typeof onRejected === 'function'
            ? function (r) {
                mark(r);
                try { return onRejected(r); }
                catch (e) { throw mark(e); }
            }
            : function (r) { throw mark(r); };
        return _origThen.call(this, wrappedFulfilled, wrappedRejected);
    };
}
