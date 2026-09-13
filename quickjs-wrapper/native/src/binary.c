#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "yeow_quickjs.h"
#include "binary.h"

#define HDR 12u
#define KEY_MAX 512

/* ── buffer writer ─────────────────────────────────────────────────── */

typedef struct {
    uint8_t *p;
    size_t cap;
    size_t len;
    int overflow;
} BW;

static void bw_u8(BW *b, uint8_t v) {
    if (b->overflow) return;
    if (b->len + 1 > b->cap) { b->overflow = 1; return; }
    b->p[b->len++] = v;
}
static void bw_bytes(BW *b, const void *s, size_t n) {
    if (b->overflow) return;
    if (b->len + n > b->cap) { b->overflow = 1; return; }
    if (n) memcpy(b->p + b->len, s, n);
    b->len += n;
}
static void bw_varint(BW *b, uint64_t v) {
    while (v >= 0x80) { bw_u8(b, (uint8_t)(v | 0x80)); v >>= 7; }
    bw_u8(b, (uint8_t)v);
}

/* ── tree encoder (JS value -> buffer, tag-driven single pass) ─────── */

static void enc_value(BW *b, JSContext *ctx, JSValueConst v, int depth);

static void enc_prop(BW *b, JSContext *ctx, const char *key, size_t kl, JSValueConst v, int depth) {
    bw_u8(b, YEOB_T_KEY);
    bw_varint(b, kl);
    bw_bytes(b, key, kl);
    enc_value(b, ctx, v, depth + 1);
}

static void enc_value(BW *b, JSContext *ctx, JSValueConst v, int depth) {
    if (b->overflow) return;
    if (depth > 64) { b->overflow = 1; return; }

    switch (JS_VALUE_GET_NORM_TAG(v)) {
        case JS_TAG_NULL:
        case JS_TAG_UNDEFINED: bw_u8(b, YEOB_T_NULL); return;
        case JS_TAG_BOOL: bw_u8(b, JS_VALUE_GET_BOOL(v) ? YEOB_T_TRUE : YEOB_T_FALSE); return;
        case JS_TAG_INT: {
            bw_u8(b, YEOB_T_I32);
            int32_t x = JS_VALUE_GET_INT(v);
            bw_bytes(b, &x, 4);
            return;
        }
        case JS_TAG_FLOAT64: {
            bw_u8(b, YEOB_T_F64);
            double d = JS_VALUE_GET_FLOAT64(v);
            bw_bytes(b, &d, 8);
            return;
        }
        case JS_TAG_BIG_INT:
        case JS_TAG_SHORT_BIG_INT: {
            int64_t x = 0;
            JS_ToBigInt64(ctx, &x, v);
            bw_u8(b, YEOB_T_I64);
            bw_bytes(b, &x, 8);
            return;
        }
        case JS_TAG_STRING:
        case JS_TAG_STRING_ROPE: {
            size_t n = 0;
            const char *s = JS_ToCStringLen(ctx, &n, v);
            if (!s) { b->overflow = 1; return; }
            bw_u8(b, YEOB_T_STR);
            bw_varint(b, n);
            bw_bytes(b, s, n);
            JS_FreeCString(ctx, s);
            return;
        }
        case JS_TAG_OBJECT: {
            size_t blen = 0;
            uint8_t *ab = JS_GetArrayBuffer(ctx, &blen, v);
            if (ab) {
                bw_u8(b, YEOB_T_BYTES);
                bw_varint(b, blen);
                bw_bytes(b, ab, blen);
                return;
            }
            if (JS_IsArray(ctx, v)) {
                JSValue lv = JS_GetPropertyStr(ctx, v, "length");
                int32_t n = 0;
                JS_ToInt32(ctx, &n, lv);
                JS_FreeValue(ctx, lv);
                if (n < 0) n = 0;
                bw_u8(b, YEOB_T_ARR);
                for (int32_t i = 0; i < n; i++) {
                    JSValue e = JS_GetPropertyUint32(ctx, v, (uint32_t)i);
                    enc_value(b, ctx, e, depth + 1);
                    JS_FreeValue(ctx, e);
                    if (b->overflow) return;
                }
                bw_u8(b, YEOB_T_ARR_END);
                return;
            }
            if (JS_IsFunction(ctx, v)) { b->overflow = 1; return; } /* unsupported -> fallback */

            JSPropertyEnum *tab = NULL;
            uint32_t n = 0;
            if (JS_GetOwnPropertyNames(ctx, &tab, &n, v, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) < 0) {
                b->overflow = 1;
                return;
            }
            bw_u8(b, YEOB_T_OBJ);
            for (uint32_t i = 0; i < n; i++) {
                size_t kl = 0;
                const char *key = JS_AtomToCStringLen(ctx, &kl, tab[i].atom);
                JSValue e = JS_GetProperty(ctx, v, tab[i].atom);
                if (key) {
                    enc_prop(b, ctx, key, kl, e, depth);
                    JS_FreeCString(ctx, key);
                }
                JS_FreeValue(ctx, e);
                if (b->overflow) break;
            }
            JS_FreePropertyEnum(ctx, tab, n);
            if (!b->overflow) bw_u8(b, YEOB_T_OBJ_END);
            return;
        }
        default:
            b->overflow = 1; /* symbol / uninitialized / etc. */
    }
}

/* ── tree decoder (buffer -> JS value) ─────────────────────────────── */

typedef struct {
    const uint8_t *p;
    const uint8_t *end;
} BR;

static int br_peek(BR *b, uint8_t *out) {
    if (b->p >= b->end) return 0;
    *out = *b->p;
    return 1;
}
static int br_u8(BR *b, uint8_t *v) {
    if (b->p >= b->end) return 0;
    *v = *b->p++;
    return 1;
}
static int br_bytes(BR *b, size_t n, const uint8_t **out) {
    if ((size_t)(b->end - b->p) < n) return 0;
    *out = b->p;
    b->p += n;
    return 1;
}
static int br_varint(BR *b, uint64_t *out) {
    uint64_t v = 0;
    int shift = 0;
    for (;;) {
        uint8_t c;
        if (!br_u8(b, &c)) return 0;
        v |= (uint64_t)(c & 0x7f) << shift;
        if (!(c & 0x80)) break;
        shift += 7;
        if (shift > 63) return 0;
    }
    *out = v;
    return 1;
}

static JSValue dec_value(JSContext *ctx, BR *b, int depth) {
    if (depth > 64) return JS_EXCEPTION;
    uint8_t t;
    if (!br_u8(b, &t)) return JS_EXCEPTION;
    switch (t) {
        case YEOB_T_NULL: return JS_NULL;
        case YEOB_T_TRUE: return JS_TRUE;
        case YEOB_T_FALSE: return JS_FALSE;
        case YEOB_T_I32: { const uint8_t *p; int32_t v; if (!br_bytes(b, 4, &p)) return JS_EXCEPTION; memcpy(&v, p, 4); return JS_NewInt32(ctx, v); }
        case YEOB_T_I64: { int64_t v; const uint8_t *p; if (!br_bytes(b, 8, &p)) return JS_EXCEPTION; memcpy(&v, p, 8); if (v > 9007199254740991LL || v < -9007199254740991LL) return JS_NewBigInt64(ctx, v); return JS_NewInt64(ctx, v); }
        case YEOB_T_F64: { double v; const uint8_t *p; if (!br_bytes(b, 8, &p)) return JS_EXCEPTION; memcpy(&v, p, 8); return JS_NewFloat64(ctx, v); }
        case YEOB_T_STR: {
            uint64_t n;
            const uint8_t *p;
            if (!br_varint(b, &n) || !br_bytes(b, (size_t)n, &p)) return JS_EXCEPTION;
            return JS_NewStringLen(ctx, (const char *)p, (size_t)n);
        }
        case YEOB_T_BYTES: {
            uint64_t n;
            const uint8_t *p;
            if (!br_varint(b, &n) || !br_bytes(b, (size_t)n, &p)) return JS_EXCEPTION;
            return JS_NewArrayBufferCopy(ctx, p, (size_t)n);
        }
        case YEOB_T_OBJ: {
            JSValue o = JS_NewObject(ctx);
            for (;;) {
                uint8_t nt;
                if (!br_peek(b, &nt)) { JS_FreeValue(ctx, o); return JS_EXCEPTION; }
                if (nt == YEOB_T_OBJ_END) { br_u8(b, &nt); break; }
                if (nt != YEOB_T_KEY || !br_u8(b, &nt)) {
                    JS_FreeValue(ctx, o);
                    return JS_EXCEPTION;
                }
                uint64_t kl;
                const uint8_t *kp;
                if (!br_varint(b, &kl) || !br_bytes(b, (size_t)kl, &kp)) {
                    JS_FreeValue(ctx, o);
                    return JS_EXCEPTION;
                }
                /* Length-based atom (no NUL-terminated copy). Define (not Set) skips the
                 * [[Set]] prototype-setter walk on this freshly built object. */
                JSAtom ka = JS_NewAtomLen(ctx, (const char *)kp, (size_t)kl);
                if (ka == JS_ATOM_NULL) { JS_FreeValue(ctx, o); return JS_EXCEPTION; }
                JSValue v = dec_value(ctx, b, depth + 1);
                if (JS_IsException(v)) { JS_FreeAtom(ctx, ka); JS_FreeValue(ctx, o); return JS_EXCEPTION; }
                int r = JS_DefinePropertyValue(ctx, o, ka, v, JS_PROP_C_W_E);
                JS_FreeAtom(ctx, ka);
                if (r < 0) { JS_FreeValue(ctx, o); return JS_EXCEPTION; }
            }
            return o;
        }
        case YEOB_T_ARR: {
            JSValue a = JS_NewArray(ctx);
            uint32_t i = 0;
            for (;;) {
                uint8_t nt;
                if (!br_peek(b, &nt)) { JS_FreeValue(ctx, a); return JS_EXCEPTION; }
                if (nt == YEOB_T_ARR_END) { br_u8(b, &nt); break; }
                JSValue v = dec_value(ctx, b, depth + 1);
                if (JS_IsException(v)) { JS_FreeValue(ctx, a); return JS_EXCEPTION; }
                JS_SetPropertyUint32(ctx, a, i++, v); /* fast-array append path */
            }
            return a;
        }
        default:
            return JS_EXCEPTION;
    }
}

/* ── JS entry points ───────────────────────────────────────────────── */

static JSValue js_yeow_write(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 2) return JS_FALSE;
    YeowCtx *c = (YeowCtx *)JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    if (!c || !c->buf || c->buf_cap <= HDR) return JS_FALSE;

    size_t ch_len = 0;
    const char *channel = JS_ToCStringLen(ctx, &ch_len, argv[0]);
    if (!channel) return JS_FALSE;
    if (ch_len > 0xffff) { JS_FreeCString(ctx, channel); return JS_FALSE; }

    BW b = { .p = c->buf + HDR, .cap = c->buf_cap - HDR, .len = 0, .overflow = 0 };
    bw_u8(&b, (uint8_t)(ch_len & 0xff));
    bw_u8(&b, (uint8_t)(ch_len >> 8));
    bw_bytes(&b, channel, ch_len);
    JS_FreeCString(ctx, channel);
    enc_value(&b, ctx, argv[1], 0);
    if (b.overflow) return JS_FALSE;

    uint8_t *h = c->buf;
    uint32_t magic = YEOB_MAGIC;
    h[0] = (uint8_t)magic; h[1] = (uint8_t)(magic >> 8); h[2] = (uint8_t)(magic >> 16); h[3] = (uint8_t)(magic >> 24);
    h[4] = (uint8_t)YEOB_VERSION; h[5] = 0;
    h[6] = YEOB_MODE_BIN;
    h[7] = 0;
    uint32_t body = (uint32_t)b.len;
    h[8] = (uint8_t)body; h[9] = (uint8_t)(body >> 8); h[10] = (uint8_t)(body >> 16); h[11] = (uint8_t)(body >> 24);
    return JS_TRUE;
}

static JSValue js_yeow_read(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    (void)argc;
    (void)argv;
    YeowCtx *c = (YeowCtx *)JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    if (!c || !c->buf || c->buf_cap < HDR) return JS_NULL;

    const uint8_t *h = c->buf;
    uint32_t magic = (uint32_t)h[0] | ((uint32_t)h[1] << 8) | ((uint32_t)h[2] << 16) | ((uint32_t)h[3] << 24);
    if (magic != YEOB_MAGIC) return JS_NULL;
    uint8_t mode = h[6];
    uint32_t body = (uint32_t)h[8] | ((uint32_t)h[9] << 8) | ((uint32_t)h[10] << 16) | ((uint32_t)h[11] << 24);
    if ((size_t)body > c->buf_cap - HDR) return JS_NULL;

    BR br = { .p = c->buf + HDR, .end = c->buf + HDR + body };
    if (mode == YEOB_MODE_NULL) return JS_NULL;
    if (mode == YEOB_MODE_JSON) {
        uint64_t n;
        const uint8_t *p;
        if (!br_varint(&br, &n) || !br_bytes(&br, (size_t)n, &p)) return JS_NULL;
        if ((size_t)(HDR + (p - (c->buf + HDR)) + n) >= c->buf_cap) return JS_NULL;
        ((uint8_t *)p)[n] = '\0'; /* JS_ParseJSON needs a NUL terminator */
        return JS_ParseJSON(ctx, (const char *)p, (size_t)n, "binary.json");
    }
    if (mode == YEOB_MODE_BIN) {
        uint8_t lo, hi;
        if (!br_u8(&br, &lo) || !br_u8(&br, &hi)) return JS_NULL;
        size_t ch_len = (size_t)lo | ((size_t)hi << 8);
        const uint8_t *cp;
        if (!br_bytes(&br, ch_len, &cp)) return JS_NULL;
        return dec_value(ctx, &br, 0);
    }
    return JS_NULL;
}

void yeow_binary_install(JSContext *ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__yeowWrite",
                      JS_NewCFunction(ctx, js_yeow_write, "__yeowWrite", 2));
    JS_SetPropertyStr(ctx, global, "__yeowRead",
                      JS_NewCFunction(ctx, js_yeow_read, "__yeowRead", 0));
    JS_FreeValue(ctx, global);
}
