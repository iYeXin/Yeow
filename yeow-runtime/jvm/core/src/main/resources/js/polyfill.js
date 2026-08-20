// ── Yeow polyfill.js（在 init.js 之前拼接执行）─────────────────────
// 承载纯 JS 可实现的全局 Polyfill：TextEncoder / TextDecoder（utf8）与 fetch。
// 与 init.js 处于同一脚本作用域（Java 端顺序拼接 polyfill + init 后单次求值），
// 因此可直接引用 init.js 作用域内的 $_send / _tSeq / _registerCallback 等绑定。
// 规范仅要求这些全局**存在**（utf8 编解码）；内部实现（直转 vs util 通道）不构成约束。

// ── TextEncoder / TextDecoder（utf8，阈值策略）─────────────────────
// 小载荷用纯 JS 转换（零往返）；超阈值（字节/字符）走 util 通道
// （util:encode.utf8 / decode.utf8）——避免 JS 侧慢速路径与大载荷分配峰值。
const _UTF8_MAX_INLINE_BYTES = 100;   // JS 直转最大字节数
const _UTF8_MAX_INLINE_CHARS = 50;    // JS 直转最大字符数

function _encodeUtf8Inline(str) {
    const bytes = [];
    for (const ch of str) {
        const cp = ch.codePointAt(0);
        if (cp < 0x80) bytes.push(cp);
        else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
        else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
        else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    }
    return Uint8Array.from(bytes);
}

/** 非法序列替换 U+FFFD（对齐 util decode.utf8 / Java UtilCodec 语义）。 */
function _decodeUtf8Inline(bytes) {
    let out = '';
    let i = 0;
    const n = bytes.length;
    while (i < n) {
        const b0 = bytes[i];
        let cp, len;
        if (b0 < 0x80) { cp = b0; len = 1; }
        else if ((b0 & 0xe0) === 0xc0) { cp = b0 & 0x1f; len = 2; }
        else if ((b0 & 0xf0) === 0xe0) { cp = b0 & 0x0f; len = 3; }
        else if ((b0 & 0xf8) === 0xf0) { cp = b0 & 0x07; len = 4; }
        else { out += '\uFFFD'; i++; continue; }
        if (i + len > n) { out += '\uFFFD'; i++; continue; }
        let ok = true;
        for (let j = 1; j < len; j++) {
            const bj = bytes[i + j];
            if ((bj & 0xc0) !== 0x80) { ok = false; break; }
            cp = (cp << 6) | (bj & 0x3f);
        }
        if (!ok) { out += '\uFFFD'; i++; continue; }
        if ((len === 2 && cp < 0x80) || (len === 3 && cp < 0x800) || (len === 4 && cp < 0x10000)
            || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) { out += '\uFFFD'; i++; continue; }
        out += String.fromCodePoint(cp);
        i += len;
    }
    return out;
}

function _encodeUtf8ViaUtil(str) {
    const out = $_send('util', JSON.stringify({ t: 'encode.utf8', p: { data: str } }));
    return Uint8Array.fromBase64(JSON.parse(out).data);
}

function _decodeUtf8ViaUtil(bytes) {
    const out = $_send('util', JSON.stringify({ t: 'decode.utf8', p: { data: bytes.toBase64() } }));
    return JSON.parse(out).data;
}

globalThis.TextEncoder = class TextEncoder {
    constructor(encoding = 'utf-8') {
        const e = String(encoding).toLowerCase().replace('-', '');
        if (e !== 'utf8') throw new RangeError('TextEncoder currently supports utf-8 only');
    }
    get encoding() { return 'utf-8'; }
    /** utf-8 编码：≤50 字符且结果 ≤100 字节 JS 直转；否则 util 通道。 */
    encode(str = '') {
        if (str.length > _UTF8_MAX_INLINE_CHARS) return _encodeUtf8ViaUtil(str);
        const bytes = _encodeUtf8Inline(str);
        return bytes.length > _UTF8_MAX_INLINE_BYTES ? _encodeUtf8ViaUtil(str) : bytes;
    }
};

globalThis.TextDecoder = class TextDecoder {
    constructor(encoding = 'utf-8') {
        const e = String(encoding).toLowerCase().replace('-', '');
        if (e !== 'utf8') throw new RangeError('TextDecoder currently supports utf-8 only');
    }
    get encoding() { return 'utf-8'; }
    /** utf-8 解码：≤100 字节且结果 ≤50 字符 JS 直转（非法序列 → U+FFFD）；否则 util 通道。 */
    decode(bytes) {
        if (bytes.length > _UTF8_MAX_INLINE_BYTES) return _decodeUtf8ViaUtil(bytes);
        const s = _decodeUtf8Inline(bytes);
        return s.length > _UTF8_MAX_INLINE_CHARS ? _decodeUtf8ViaUtil(bytes) : s;
    }
};

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
            // arrayBuffer 本地解码为 Uint8Array；text/json 经 TextDecoder 解码
            //（小载荷 JS 直转、超阈值走 util 通道）；base64 原样返回——按需解码，零冗余拷贝。
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
