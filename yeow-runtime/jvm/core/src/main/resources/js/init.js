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
// 最终字符串——合并连续相同片段，超过 12 段保留首 6 + 尾 6。
const MAX_STACK_SEGMENTS = 12;
const KEEP_HEAD = 6;
const KEEP_TAIL = 6;

function _captureStack() {
    return globalThis.$dev ? new Error().stack : null;
}

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
    if (node && node.stack) segs.push({ stack: node.stack, marker });
}

/** 合并连续相同片段；超过上限保留首 6 + 尾 6，中间标记省略。 */
function _dedupeAndTrim(segs) {
    const out = [];
    for (const s of segs) {
        const last = out[out.length - 1];
        if (last && !last.omitted && last.stack === s.stack && last.marker === s.marker) {
            last.count = (last.count || 1) + 1;
        } else {
            out.push({ stack: s.stack, marker: s.marker, count: 1 });
        }
    }
    if (out.length <= MAX_STACK_SEGMENTS) return out;
    const head = out.slice(0, KEEP_HEAD);
    const tail = out.slice(out.length - KEEP_TAIL);
    const omitted = out.length - KEEP_HEAD - KEEP_TAIL;
    return head.concat([{ omitted: true, marker: '    ... (' + omitted + ' segments omitted) ...\n' }], tail);
}

/** 构建最终栈字符串（报错时才调用）。 */
function _buildStackText(originalStack, node, chainNodes) {
    const segs = _dedupeAndTrim(_expandSegments(node, chainNodes));
    let text = originalStack || '';
    for (const s of segs) {
        if (s.omitted) { text += '\n' + s.marker; continue; }
        text += '\n' + s.marker + s.stack + (s.count > 1 ? '\n    (×' + s.count + ')' : '');
    }
    return text;
}

/**
 * 把当前回调栈片段挂到错误对象并构建一次（报错时调用）。
 * 构建后 `__yeowStackBuilt` 标记，reportError 幂等。
 */
function _enhanceStack(ex, node) {
    if (!globalThis.$dev || !node || !ex || typeof ex !== 'object') return;
    if (ex.__yeowStackBuilt) return;
    ex.__yeowStackBuilt = true;
    if (!ex.__yeowStackNode) ex.__yeowStackNode = node;
    ex.stack = _buildStackText(ex.stack || '', ex.__yeowStackNode, ex.__yeowChainNodes || null);
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
    return JSON.parse(raw);
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
    const id = 'i' + (_tSeq++); const cb = _registerCallback(fn, { persistent: true }); _timers[id] = cb;
    $_send('timer', JSON.stringify({ type: 'interval', cb, delay: ms || 0 })); return id;
};
globalThis.clearInterval = globalThis.clearTimeout;

// ── fetch ───────────────────────────────────────────────────────────
globalThis.fetch = (url, options = {}) => {
    return new Promise((resolve, reject) => {
        const id = 'f' + (_tSeq++);
        const cb = _registerCallback((raw) => {
            const r = typeof raw === 'string' ? JSON.parse(raw) : raw;
            if (r?.error) { const e = new Error(r.error); if (globalThis.$dev) _attachCbStack(e); reject(e); return; }
            const body = r.body || '';
            const status = r.status || 200;
            const headers = r.headers || {};
            const ok = status >= 200 && status < 300;
            resolve({
                ok, status, statusText: ok ? 'OK' : 'Error',
                headers: { get: (name) => headers[name.toLowerCase()] },
                text: () => Promise.resolve(body),
                json: () => Promise.resolve(JSON.parse(body)),
            });
        });
        $_send('http', JSON.stringify({ t: 'requestAsync', p: { url, method: options.method || 'GET', headers: options.headers || {}, body: options.body || null, responseType: 'text', cb } }));
    });
};

// ── Error Reporter ────────────────────────────────────────────────
function _parseStack(stack) {
    if (!stack) return {};
    const m = stack.match(/at\s+(?:\S+\s+)?\(?([^\s(]+):(\d+):(\d+)\)?/);
    return m ? { fileName: m[1], lineNumber: parseInt(m[2]), columnNumber: parseInt(m[3]) } : {};
}

/** 幂等构建最终栈（未构建过且挂有片段时构建一次）。 */
function _finalStack(e) {
    if (!globalThis.$dev || !e || typeof e !== 'object') return e?.stack || '';
    if (!e.__yeowStackBuilt && e.__yeowStackNode) _enhanceStack(e, e.__yeowStackNode);
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
    try { $_send('debug', JSON.stringify({ t: 'reportError', p: info })); } catch (ex) { /* fallback */ }
    console.error('js', msg);
}

// ── InstanceId GC ──────────────────────────────────────────────────
// FinalizationRegistry callbacks fire as JS_PendingJob microtasks
// during ctx.executePendingJob(). They push collected ids here.
// Flushed at end of each $hm dispatch cycle.
const _gcCollected = [];
globalThis.__yeowGcQueue = _gcCollected;
function _flushGC() {
    if (_gcCollected.length > 0) {
        try {
            $_send('lifecycle', JSON.stringify({ type: 'gc-collect', ids: _gcCollected.splice(0) }));
        } catch (ex) { /* ignore */ }
    }
}

// ── Message Dispatcher ──────────────────────────────────────────────

function _runLifecycleCallbacks(cbs) {
    // Lifecycle callbacks run outside the $cb wrapper — provide a context so
    // that .then() calls made inside onLoad/onInit (and their microtask
    // continuations, which run in this message's job pump) capture the user
    // call chain. The next message clears/overwrites it, so no stale leak.
    if (globalThis.$dev) _currentCbStack = { stack: new Error().stack, outer: _currentCbStack || null };
    for (var i = 0; i < cbs.length; i++) {
        try {
            var result = cbs[i]();
            if (result && typeof result.then === 'function') {
                result.then(null, function (ex) { reportError(ex); });
            }
        } catch (ex) { reportError(ex); }
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
        _flushGC();
        return null;
    } catch (e) {
        if (e instanceof SyntaxError) return 'null';
        throw e;
    }
};

// ── Dev-only: promise chain stack tracing ─────────────────────────────
// Intercept Promise.prototype.then to record the call site of every visible
// .then() link. When a promise rejects, the recorded stack is appended to the
// error — so errors that travel through several .then() hops keep every hop's
// origin (user frames are captured via _currentCbStack inside callbacks).
// Note: `await` is NOT intercepted (engine-internal), so async-function
// intermediate frames remain unavailable — this only covers visible .then chains.
// 传播时只 push 节点（O(1)），字符串在错误构建时统一展开。
if (globalThis.$dev) {
    const _origThen = Promise.prototype.then;
    const _chainMark = Symbol('yeowChainDone');
    Promise.prototype.then = function (onFulfilled, onRejected) {
        const attachNode = globalThis._getCurrentCbStack() || { stack: new Error().stack, outer: null };
        const promise = this;
        const mark = function (reason) {
            if (reason && typeof reason === 'object' && !promise[_chainMark]) {
                promise[_chainMark] = true;
                if (!reason.__yeowChainNodes) reason.__yeowChainNodes = [];
                reason.__yeowChainNodes.push(attachNode);
            }
            return reason;
        };
        // Wrap onFulfilled too: if the handler itself throws, the resulting promise
        // rejects WITHOUT any reaction on it — the rejection would go straight to the
        // unhandled-rejection channel with a bare single frame. Catching the throw here
        // marks the error before it enters the result promise.
        const wrappedFulfilled = typeof onFulfilled === 'function'
            ? function (v) {
                try { return onFulfilled(v); }
                catch (e) { throw mark(e); }
            }
            : onFulfilled;
        const wrappedRejected = typeof onRejected === 'function'
            ? function (r) { mark(r); return onRejected(r); }
            : undefined;
        return _origThen.call(this, wrappedFulfilled, wrappedRejected);
    };
}
