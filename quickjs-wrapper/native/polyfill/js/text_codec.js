(() => {
  const _enc = globalThis.__yeowUtf8Encode;
  const _dec = globalThis.__yeowUtf8Decode;
  try { delete globalThis.__yeowUtf8Encode; } catch (e) {}
  try { delete globalThis.__yeowUtf8Decode; } catch (e) {}
  const isUtf8 = (label) => String(label).toLowerCase().replace(/[-_]/g, '') === 'utf8';
  class TextEncoder {
    constructor(encoding = 'utf-8') {
      if (!isUtf8(encoding)) throw new RangeError('TextEncoder supports utf-8 only');
    }
    get encoding() { return 'utf-8'; }
    encode(str = '') { return new Uint8Array(_enc(String(str))); }
  }
  class TextDecoder {
    constructor(encoding = 'utf-8') {
      if (!isUtf8(encoding)) throw new RangeError('TextDecoder supports utf-8 only');
    }
    get encoding() { return 'utf-8'; }
    decode(input) {
      if (input === undefined) return '';
      if (!(input instanceof ArrayBuffer) && !ArrayBuffer.isView(input)) {
        throw new TypeError('TextDecoder.decode expects an ArrayBuffer or ArrayBufferView');
      }
      return _dec(input);
    }
  }
  globalThis.TextEncoder = TextEncoder;
  globalThis.TextDecoder = TextDecoder;
})();
