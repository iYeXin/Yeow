// ── Callback Registry ───────────────────────────────────────────────
const _cbs = {};
let _cbSeq = 1;

function _enhanceStack(ex, regStack) {
    if (regStack && ex && typeof ex === 'object' && !ex._enhanced) {
        ex._enhanced = true;
        ex.stack = (ex.stack || '') + '    --- cb registered at ---\n' + regStack;
    }
}

let _currentCbStack = null;
globalThis._getCurrentCbStack = () => _currentCbStack;

globalThis._registerCallback = (fn, { persistent = false } = {}) => {
    const id = 'cb_' + (_cbSeq++);
    let regStack = globalThis.$dev ? new Error().stack : null;
    // 跨层链：若注册发生在另一个回调执行中（回调体内再注册异步任务），
    // 把外层回调的注册栈一并拼入，还原多层嵌套的回调来源。
    if (globalThis.$dev && regStack && _currentCbStack) {
        regStack += '    --- outer callback ---\n' + _currentCbStack;
    }
    _cbs[id] = {
        h: function $cb() {
            if (globalThis.$dev) _currentCbStack = regStack;
            try {
                const result = fn.apply(this, arguments);
                if (result && typeof result.then === 'function') {
                    // In dev the Promise.prototype.then interceptor already appends
                    // the callback registration stack ("--- promise chain ---");
                    // here we only report. In prod _enhanceStack is a no-op anyway.
                    return result.then(null, ex => { reportError(ex); });
                }
                return result;
            } catch (ex) { _enhanceStack(ex, regStack); throw ex; }
        },
        persistent,
        stack: regStack,
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
globalThis.clearTimeout = (id) => { const c = _timers[id]; if (c) _unregisterCallback(c); delete _timers[id]; };
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
            if (r?.error) { const e = new Error(r.error); const s = globalThis.$dev ? _getCurrentCbStack() : null; if (s) e.stack += '    --- cb registered at ---\n' + s; reject(e); return; }
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
        stack: e?.stack || '',
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
    if (globalThis.$dev) _currentCbStack = new Error().stack;
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
            try {
                e.h(r);
            } catch (ex) { _enhanceStack(ex, e.stack); throw ex; }
            if (!e.persistent) delete _cbs[p];
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
if (globalThis.$dev) {
    const _origThen = Promise.prototype.then;
    const _chainMark = Symbol('yeowChainDone');
    Promise.prototype.then = function (onFulfilled, onRejected) {
        const attachStack = globalThis._getCurrentCbStack() || new Error().stack;
        const promise = this;
        const mark = function (reason) {
            if (reason && typeof reason === 'object' && !promise[_chainMark]) {
                promise[_chainMark] = true;
                reason.stack = (reason.stack || '') + '    --- promise chain ---\n' + attachStack;
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
