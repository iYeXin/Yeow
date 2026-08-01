// Node.js replica of C++ base64 logic from quickjs_wrapper.cpp
// Goal: verify the algorithm is correct

const base64_table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// ── Build decode table (same as C++ b64_decode) ──
const b64_decode = new Int8Array(256).fill(-1);
for (let i = 0; i < 64; i++) {
    b64_decode[base64_table.charCodeAt(i)] = i;
}

// Check specific indices
console.log("'A'(65)=", b64_decode[65]);
console.log("'B'(66)=", b64_decode[66]);
console.log("'G'(71)=", b64_decode[71]);
console.log("'S'(83)=", b64_decode[83]);
console.log("'V'(86)=", b64_decode[86]);
console.log("'b'(98)=", b64_decode[98]);
console.log("'s'(115)=", b64_decode[115]);
console.log("'8'(56)=", b64_decode[56]);
console.log("'='(61)=", b64_decode[61]);
console.log("'/'(47)=", b64_decode[47]);
console.log("'+'(43)=", b64_decode[43]);
console.log("'9'(57)=", b64_decode[57]);
console.log();

// ── Encode (replica of js_uint8ArrayToBase64) ──
function encode(buf) {
    const len = buf.length;
    const outLen = Math.floor((len + 2) / 3) * 4;
    let out = "";
    for (let ip = 0; ip < len; ip += 3) {
        const n = Math.min(3, len - ip);
        let triplet = (buf[ip] << 16);
        if (n >= 2) triplet |= (buf[ip + 1] << 8);
        if (n >= 3) triplet |= buf[ip + 2];
        out += base64_table[(triplet >> 18) & 0x3F];
        out += base64_table[(triplet >> 12) & 0x3F];
        out += (n >= 2) ? base64_table[(triplet >> 6) & 0x3F] : '=';
        out += (n >= 3) ? base64_table[triplet & 0x3F] : '=';
    }
    return out;
}

// ── Decode (replica of js_base64ToUint8Array) ──
function decode(str) {
    const len = str.length;
    if (len === 0 || len % 4 !== 0) throw new Error("invalid length");

    let pad = 0;
    while (pad < 2 && len > pad && str[len - 1 - pad] === '=') pad++;
    const groupCount = len / 4;
    const outLen = groupCount * 3 - pad;
    if (outLen === 0) throw new Error("empty output");

    const out = new Uint8Array(outLen);
    let j = 0;
    for (let ip = 0; ip < len; ip += 4) {
        if (str[ip] === '=') break;
        const sa = b64_decode[str.charCodeAt(ip)];
        const sb = b64_decode[str.charCodeAt(ip + 1)];
        const sc = b64_decode[str.charCodeAt(ip + 2)];
        const sd = b64_decode[str.charCodeAt(ip + 3)];
        if (sa < 0 || sb < 0) throw new Error("invalid character at " + ip);
        let triplet = (sa << 18) | (sb << 12);
        if (sc >= 0) triplet |= (sc << 6);
        if (sd >= 0) triplet |= sd;
        if (j < outLen) out[j++] = (triplet >> 16) & 0xFF;
        if (j < outLen && sc >= 0) out[j++] = (triplet >> 8) & 0xFF;
        if (j < outLen && sd >= 0) out[j++] = triplet & 0xFF;
    }
    return out;
}

// ── Tests ──
function test(name, input) {
    const encoded = encode(input);
    const decoded = decode(encoded);
    const match = JSON.stringify(Array.from(input)) === JSON.stringify(Array.from(decoded));
    console.log(`[${match ? 'OK' : 'FAIL'}] ${name}`);
    if (!match) {
        console.log(`  Input:  ${JSON.stringify(Array.from(input))}`);
        console.log(`  Encoded: ${encoded}`);
        console.log(`  Decoded: ${JSON.stringify(Array.from(decoded))}`);
    }
}

test("Hello", new Uint8Array([72, 101, 108, 108, 111]));
test("0,1,2", new Uint8Array([0, 1, 2]));
test("255,254,253", new Uint8Array([255, 254, 253]));
test("0,1,2,255,254,253", new Uint8Array([0, 1, 2, 255, 254, 253]));
test("1 byte", new Uint8Array([1]));
test("2 bytes", new Uint8Array([1, 2]));

// Also test direct decode of known string
console.log();
console.log("Direct decode 'SGVsbG8=' :", Array.from(decode("SGVsbG8=")));
console.log("Direct decode 'AAAA'    :", Array.from(decode("AAAA")));
console.log("Direct decode 'AAAB'    :", Array.from(decode("AAAB")));
console.log("Direct decode 'AAEC'    :", Array.from(decode("AAEC")));
console.log("Direct decode '///9'    :", Array.from(decode("///9")));
console.log("Direct decode 'AAEC///9':", Array.from(decode("AAEC///9")));
