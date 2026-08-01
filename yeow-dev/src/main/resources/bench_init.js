// ── Callback Registry (mirrors production init.js) ──
var _cbs = {};
var _cbSeq = 1;

globalThis._registerCallback = function (fn, opts) {
    var id = 'cb_' + (_cbSeq++);
    _cbs[id] = { h: fn, persistent: opts && opts.persistent };
    return id;
};

globalThis._unregisterCallback = function (id) {
    delete _cbs[id];
};

// ── Message Dispatcher (mirrors production $hm / _hm) ──
globalThis.$hm = function (json) {
    try {
        var msg = JSON.parse(json);
        var t = msg.t, p = msg.p, r = msg.r;

        if (t === 'cb' || t === 'CALLBACK') {
            var e = _cbs[p];
            if (e) {
                e.h(r);
                if (!e.persistent) delete _cbs[p];
            }
        }
        return null;
    } catch (_) {
        return 'null';
    }
};

// ── Legacy sync test functions ──
function testRawEcho(count) {
    for (var i = 0; i < count; i++) { $rawEcho("hello"); }
}

function testJsonEcho(count) {
    for (var i = 0; i < count; i++) {
        var raw = $jsonEcho(JSON.stringify({x: i, y: "test"}));
        var parsed = JSON.parse(raw);
    }
}

function testCallJSON(count) {
    for (var i = 0; i < count; i++) {
        var raw = $callJSON(JSON.stringify({x: i, y: "test"}));
        var parsed = JSON.parse(raw);
    }
}

function testCallRaw(count) {
    for (var i = 0; i < count; i++) {
        $callRaw("data");
    }
}

// ── Batch async (all N submitted at once) ──
function testAsyncJSON(count) {
    for (var i = 0; i < count; i++) {
        var cbId = _registerCallback(function (r) {});
        $asyncSendJSON(JSON.stringify({
            type: 'bench',
            params: { x: i, y: 'test' },
            cb: cbId
        }));
    }
}

function testAsyncRaw(count) {
    for (var i = 0; i < count; i++) {
        var cbId = _registerCallback(function (r) {});
        $asyncSendRaw(cbId);
    }
}

// ── Single-shot submit (for sequential dispatch test) ──
// Returns immediately, callback will be triggered by dispatch loop
function submitOneJSON(idx) {
    var cbId = _registerCallback(function (r) {});
    $asyncSendJSON(JSON.stringify({
        type: 'bench',
        params: { x: idx, y: 'test' },
        cb: cbId
    }));
}

function submitOneRaw(idx) {
    var cbId = _registerCallback(function (r) {});
    $asyncSendRaw(cbId);
}

// ── Heavy payload (production-size ~104B send + ~120B return) ──
// Mimics world.setBlock send + Player.getAll return with 2 entries
function submitOneHeavy(idx) {
    var cbId = _registerCallback(function (r) {});
    $asyncSendHeavy(JSON.stringify({
        type: 'world.setBlock',
        params: { world: 'world', x: idx, y: 64, z: idx, blockType: 'stone' },
        cb: cbId
    }));
}
