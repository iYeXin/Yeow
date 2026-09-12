// ── Yeow polyfill.js（在 init.js 之前拼接执行）─────────────────────
// 承载纯 JS 可实现的全局 Polyfill：fetch。
// （TextEncoder / TextDecoder 由原生桥在上下文创建时注入，见 quickjs-wrapper 的 polyfill）
// 与 init.js 处于同一脚本作用域（Java 端顺序拼接 polyfill + init 后单次求值），
// 因此可直接引用 init.js 作用域内的 $_send / _tSeq / _registerCallback 等绑定。

/** base64 → 完整 ArrayBuffer（fromBase64 返回的 Uint8Array 若带偏移则复制，保证 buffer 为独立全长）。 */
function _b64ToArrayBuffer(b64) {
    const u8 = Uint8Array.fromBase64(b64);
    if (u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength) return u8.buffer;
    return u8.slice().buffer;
}

// ── fetch ───────────────────────────────────────────────────────────
globalThis.fetch = (url, options = {}) => {
    return new Promise((resolve, reject) => {
        const id = 'f' + (_tSeq++);
        const cb = _registerCallback((raw) => {
            const r = typeof raw === 'string' ? JSON.parse(raw) : raw;
            if (r?.err || r?.error) { const e = new Error(r.err || r.error); if (globalThis.$dev) _attachCbStack(e); reject(e); return; }
            // 底层始终以 base64 缓存原始响应字节（responseType: 'base64'）；
            // text/json 经 TextDecoder 解码（原生 UTF-8）；base64 原样返回——按需解码，零冗余拷贝。
            const b64 = r.body || '';
            const status = r.status || 200;
            const headers = r.headers || {};
            const ok = status >= 200 && status < 300;
            const decoder = new TextDecoder();
            resolve({
                ok, status, statusText: ok ? 'OK' : 'Error',
                headers: { get: (name) => headers[name.toLowerCase()] },
                base64: () => Promise.resolve(b64),
                bytes: () => Promise.resolve(Uint8Array.fromBase64(b64)),
                arrayBuffer: () => Promise.resolve(_b64ToArrayBuffer(b64)),
                text: () => Promise.resolve(decoder.decode(Uint8Array.fromBase64(b64))),
                json: () => Promise.resolve().then(() => JSON.parse(decoder.decode(Uint8Array.fromBase64(b64)))),
            });
        });
        let ret = null;
        try {
            const p = { url, method: options.method || 'GET', headers: options.headers || {}, body: null, responseType: 'base64', cb };
            // 请求体与 fs.writeFile 同语义：Uint8Array 直接二进制（base64 承载）；
            // 字符串按 encoding——缺省 UTF-8 文本，'base64' 视为 base64 二进制
            if (options.body instanceof Uint8Array) { p.body = options.body.toBase64(); p.encoding = 'base64'; }
            else if (options.body != null) { p.body = options.body; if (options.encoding === 'base64') p.encoding = 'base64'; }
            if (options.timeout !== undefined) p.timeout = options.timeout;
            ret = $_send('http', JSON.stringify({ t: 'requestAsync', p }));
        } catch (ex) {
            // 桥调用失败：Java 不会投递回调——注销回调并拒绝，防 _cbs 泄漏与 Promise 悬挂
            _unregisterCallback(cb);
            reject(ex);
            return;
        }
        // 同步返回 err（如插件卸载中 io 线程池已关闭）：同样不会投递回调
        if (ret != null) {
            let r = null;
            try { r = JSON.parse(ret); } catch (ex) { /* 非 JSON 返回，忽略 */ }
            if (r && typeof r === 'object' && (r.err || r.error)) {
                _unregisterCallback(cb);
                reject(new Error(r.err || r.error));
            }
        }
    });
};
