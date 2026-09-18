/*
 * TextEncoder / TextDecoder (utf-8).
 *
 * QuickJS offers both directions out of the box: JS_ToCStringLen() yields UTF-8
 * bytes for a JS string, and JS_NewStringLen() decodes UTF-8 with U+FFFD
 * replacement for malformed input (see cutils.c unicode_from_utf8). This file
 * exposes those as two internal primitives and wraps them in the standard
 * classes with a small JS bootstrap, then removes the primitives from the
 * global object so plugin code can only reach the classes.
 */
#include <string.h>

#include "polyfill.h"
#include "js.generated.h" /* TEXT_CODEC_JS — generated from native/polyfill/js/text_codec.js */

/* string -> ArrayBuffer (UTF-8 bytes). */
static JSValue js_utf8_encode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    const char *s = "";
    size_t len = 0;
    if (argc >= 1) {
        s = JS_ToCStringLen(ctx, &len, argv[0]);
        if (!s) return JS_EXCEPTION;
    }
    JSValue out = JS_NewArrayBufferCopy(ctx, (const uint8_t *)s, len);
    if (argc >= 1) JS_FreeCString(ctx, s);
    return out;
}

/* ArrayBuffer | ArrayBufferView -> string (UTF-8, malformed -> U+FFFD). */
static JSValue js_utf8_decode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    const uint8_t *bytes = NULL;
    size_t len = 0;

    if (argc >= 1 && JS_IsObject(argv[0])) {
        size_t ab_len = 0;
        uint8_t *ab = JS_GetArrayBuffer(ctx, &ab_len, argv[0]);
        if (ab) {
            bytes = ab;
            len = ab_len;
        } else {
            /* JS_GetArrayBuffer only throws for a detached buffer; clear anything
               pending before trying the typed-array path. */
            JS_FreeValue(ctx, JS_GetException(ctx));

            size_t byte_offset = 0, byte_length = 0, bytes_per_element = 0;
            JSValue buffer = JS_GetTypedArrayBuffer(ctx, argv[0], &byte_offset, &byte_length,
                                                    &bytes_per_element);
            if (JS_IsException(buffer)) {
                JS_FreeValue(ctx, buffer);
                JS_FreeValue(ctx, JS_GetException(ctx)); /* clear "not a TypedArray" */
            } else {
                size_t buf_len = 0;
                uint8_t *base = JS_GetArrayBuffer(ctx, &buf_len, buffer);
                if (base && byte_offset + byte_length <= buf_len) {
                    bytes = base + byte_offset;
                    len = byte_length;
                }
                JS_FreeValue(ctx, buffer);
            }
        }
    }

    if (!bytes) bytes = (const uint8_t *)"";
    return JS_NewStringLen(ctx, (const char *)bytes, len);
}

/*
 * The wrapping classes live in native/polyfill/js/text_codec.js and are embedded
 * by scripts/gen-polyfill.mjs into js.generated.h (TEXT_CODEC_JS): class/getter
 * syntax and instanceof are far cleaner there than via JS_NewClass.
 */

void yeow_text_codec_install(JSContext *ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__yeowUtf8Encode",
                      JS_NewCFunction(ctx, js_utf8_encode, "__yeowUtf8Encode", 1));
    JS_SetPropertyStr(ctx, global, "__yeowUtf8Decode",
                      JS_NewCFunction(ctx, js_utf8_decode, "__yeowUtf8Decode", 1));
    JS_FreeValue(ctx, global);

    JS_FreeValue(ctx, JS_Eval(ctx, TEXT_CODEC_JS, strlen(TEXT_CODEC_JS), "text_codec.js",
                              JS_EVAL_TYPE_GLOBAL));
}
