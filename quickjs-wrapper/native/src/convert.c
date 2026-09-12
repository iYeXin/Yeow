/*
 * Java <-> JavaScript value conversion for the Yeow QuickJS bridge.
 *
 * Ownership rule: yeow_js_to_java() never frees its input; the caller owns the
 * JSValue. yeow_java_to_js() returns a value owned by the caller.
 */
#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "yeow_quickjs.h"

JNIEnv *yeow_env(YeowCtx *c) {
    JNIEnv *env = NULL;
    if (!c || !c->vm) return NULL;
    jint rc = (*c->vm)->GetEnv(c->vm, (void **)&env, JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        if ((*c->vm)->AttachCurrentThread(c->vm, (void **)&env, NULL) != JNI_OK) return NULL;
    }
    return env;
}

/*
 * Java String -> standard UTF-8 (not JNI modified UTF-8). Surrogate pairs are
 * combined into a 4-byte sequence so non-BMP characters survive the round trip.
 * Returns a malloc'd, NUL-terminated buffer (caller frees), or NULL.
 */
char *yeow_jstring_to_utf8(JNIEnv *env, jstring s, size_t *out_len) {
    if (!s) {
        if (out_len) *out_len = 0;
        return NULL;
    }
    jsize n = (*env)->GetStringLength(env, s);
    const jchar *u = (*env)->GetStringChars(env, s, NULL);
    if (!u) {
        if (out_len) *out_len = 0;
        return NULL;
    }

    size_t cap = (size_t)n * 3 + 1;
    char *buf = (char *)malloc(cap);
    if (!buf) {
        (*env)->ReleaseStringChars(env, s, u);
        if (out_len) *out_len = 0;
        return NULL;
    }

    size_t o = 0;
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
            uint32_t lo = u[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        if (cp < 0x80) {
            buf[o++] = (char)cp;
        } else if (cp < 0x800) {
            buf[o++] = (char)(0xC0 | (cp >> 6));
            buf[o++] = (char)(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            buf[o++] = (char)(0xE0 | (cp >> 12));
            buf[o++] = (char)(0x80 | ((cp >> 6) & 0x3F));
            buf[o++] = (char)(0x80 | (cp & 0x3F));
        } else {
            buf[o++] = (char)(0xF0 | (cp >> 18));
            buf[o++] = (char)(0x80 | ((cp >> 12) & 0x3F));
            buf[o++] = (char)(0x80 | ((cp >> 6) & 0x3F));
            buf[o++] = (char)(0x80 | (cp & 0x3F));
        }
    }
    buf[o] = '\0';

    (*env)->ReleaseStringChars(env, s, u);
    if (out_len) *out_len = o;
    return buf;
}

/* Standard UTF-8 -> Java String. Fast path uses NewStringUTF, valid whenever the
   bytes contain no NUL and no supplementary (4-byte) sequence, because JNI
   modified UTF-8 matches standard UTF-8 for ASCII/BMP without NUL. */
jstring yeow_new_jstring(JNIEnv *env, YeowCtx *c, const char *utf8, size_t len) {
    int modified_utf8_compatible = 1;
    for (size_t i = 0; i < len; i++) {
        unsigned char ch = (unsigned char)utf8[i];
        if (ch == 0 || ch >= 0xF0) {
            modified_utf8_compatible = 0;
            break;
        }
    }
    if (modified_utf8_compatible) {
        return (*env)->NewStringUTF(env, utf8);
    }

    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)len);
    if (!bytes) return NULL;
    if (len > 0) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)len, (const jbyte *)utf8);
    }
    jstring result = (jstring)(*env)->NewObject(env, c->clsString, c->mStringCtor, bytes, c->charsetUtf8);
    (*env)->DeleteLocalRef(env, bytes);
    return result;
}

static jobject js_string_to_java(YeowCtx *c, JNIEnv *env, JSValueConst v) {
    size_t len = 0;
    const char *s = JS_ToCStringLen(c->ctx, &len, v);
    if (!s) return NULL;
    jstring r = yeow_new_jstring(env, c, s, len);
    JS_FreeCString(c->ctx, s);
    return r;
}

static jobject js_array_to_java(YeowCtx *c, JNIEnv *env, JSValueConst v) {
    JSValue lenv = JS_GetPropertyStr(c->ctx, v, "length");
    int32_t n = 0;
    JS_ToInt32(c->ctx, &n, lenv);
    JS_FreeValue(c->ctx, lenv);
    if (n < 0) n = 0;

    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)n, c->clsObject, NULL);
    if (!arr) return NULL;

    for (int32_t i = 0; i < n; i++) {
        JSValue e = JS_GetPropertyUint32(c->ctx, v, (uint32_t)i);
        jobject je = yeow_js_to_java(c, env, e);
        JS_FreeValue(c->ctx, e);
        if (je) {
            (*env)->SetObjectArrayElement(env, arr, (jsize)i, je);
            (*env)->DeleteLocalRef(env, je);
        }
    }
    return arr;
}

static jobject js_object_to_java(YeowCtx *c, JNIEnv *env, JSValueConst v) {
    JSPropertyEnum *tab = NULL;
    uint32_t n = 0;
    if (JS_GetOwnPropertyNames(c->ctx, &tab, &n, v, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) < 0) {
        return NULL;
    }

    jobject map = (*env)->NewObject(env, c->clsMap, c->mMapInit);
    if (!map) {
        JS_FreePropertyEnum(c->ctx, tab, n);
        return NULL;
    }

    for (uint32_t i = 0; i < n; i++) {
        JSAtom atom = tab[i].atom;
        JSValue val = JS_GetProperty(c->ctx, v, atom);
        const char *key = JS_AtomToCString(c->ctx, atom);
        jobject jv = yeow_js_to_java(c, env, val);
        if (key) {
            jstring jk = yeow_new_jstring(env, c, key, strlen(key));
            if (jk) {
                (*env)->CallObjectMethod(env, map, c->mMapPut, jk, jv);
                (*env)->DeleteLocalRef(env, jk);
            }
            JS_FreeCString(c->ctx, key);
        }
        if (jv) (*env)->DeleteLocalRef(env, jv);
        JS_FreeValue(c->ctx, val);
    }

    JS_FreePropertyEnum(c->ctx, tab, n);
    return map;
}

jobject yeow_js_to_java(YeowCtx *c, JNIEnv *env, JSValueConst v) {
    switch (JS_VALUE_GET_NORM_TAG(v)) {
        case JS_TAG_EXCEPTION:
        case JS_TAG_NULL:
        case JS_TAG_UNDEFINED:
            return NULL;

        case JS_TAG_BOOL:
            return (*env)->CallStaticObjectMethod(env, c->clsBoolean, c->mBooleanValueOf,
                                                  JS_VALUE_GET_BOOL(v) ? JNI_TRUE : JNI_FALSE);

        case JS_TAG_INT:
            return (*env)->CallStaticObjectMethod(env, c->clsLong, c->mLongValueOf,
                                                  (jlong)JS_VALUE_GET_INT(v));

        case JS_TAG_FLOAT64: {
            double d = JS_VALUE_GET_FLOAT64(v);
            if (isfinite(d) && d == floor(d) && d >= -9007199254740992.0 && d <= 9007199254740992.0) {
                return (*env)->CallStaticObjectMethod(env, c->clsLong, c->mLongValueOf, (jlong)d);
            }
            return (*env)->CallStaticObjectMethod(env, c->clsDouble, c->mDoubleValueOf, (jdouble)d);
        }

        case JS_TAG_BIG_INT:
        case JS_TAG_SHORT_BIG_INT: {
            int64_t out = 0;
            JS_ToBigInt64(c->ctx, &out, v);
            return (*env)->CallStaticObjectMethod(env, c->clsLong, c->mLongValueOf, (jlong)out);
        }

        case JS_TAG_STRING:
        case JS_TAG_STRING_ROPE:
            return js_string_to_java(c, env, v);

        case JS_TAG_OBJECT: {
            /* ArrayBuffer / TypedArray backing store -> byte[] */
            size_t blen = 0;
            uint8_t *buf = JS_GetArrayBuffer(c->ctx, &blen, v);
            if (buf) {
                jbyteArray a = (*env)->NewByteArray(env, (jsize)blen);
                if (a && blen > 0) {
                    (*env)->SetByteArrayRegion(env, a, 0, (jsize)blen, (const jbyte *)buf);
                }
                return a;
            }
            if (JS_IsArray(c->ctx, v)) return js_array_to_java(c, env, v);
            if (JS_IsFunction(c->ctx, v)) return NULL;
            return js_object_to_java(c, env, v);
        }

        default:
            return NULL;
    }
}

JSValue yeow_java_to_js(YeowCtx *c, JNIEnv *env, jobject obj) {
    if (!obj) return JS_NULL;

    if ((*env)->IsInstanceOf(env, obj, c->clsString)) {
        size_t len = 0;
        char *s = yeow_jstring_to_utf8(env, (jstring)obj, &len);
        JSValue r = JS_NewStringLen(c->ctx, s ? s : "", len);
        free(s);
        return r;
    }
    if ((*env)->IsInstanceOf(env, obj, c->clsBoolean)) {
        return JS_NewBool(c->ctx, (*env)->CallBooleanMethod(env, obj, c->mBooleanValue) ? 1 : 0);
    }
    if ((*env)->IsInstanceOf(env, obj, c->clsInteger)) {
        return JS_NewInt32(c->ctx, (*env)->CallIntMethod(env, obj, c->mIntegerValue));
    }
    if ((*env)->IsInstanceOf(env, obj, c->clsLong)) {
        int64_t l = (int64_t)(*env)->CallLongMethod(env, obj, c->mLongValue);
        if (l > YEOW_MAX_SAFE_INTEGER || l < -YEOW_MAX_SAFE_INTEGER) {
            return JS_NewBigInt64(c->ctx, l);
        }
        return JS_NewInt64(c->ctx, l);
    }
    if ((*env)->IsInstanceOf(env, obj, c->clsDouble)) {
        return JS_NewFloat64(c->ctx, (*env)->CallDoubleMethod(env, obj, c->mDoubleValue));
    }
    if ((*env)->IsInstanceOf(env, obj, c->clsByteArray)) {
        jbyteArray bytes = (jbyteArray)obj;
        jsize len = (*env)->GetArrayLength(env, bytes);
        jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
        JSValue r = JS_NewArrayBufferCopy(c->ctx, (const uint8_t *)(data ? data : (jbyte *)""), (size_t)len);
        if (data) (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
        return r;
    }
    if ((*env)->IsInstanceOf(env, obj, c->clsObjectArray)) {
        jsize len = (*env)->GetArrayLength(env, (jarray)obj);
        JSValue arr = JS_NewArray(c->ctx);
        for (jsize i = 0; i < len; i++) {
            jobject el = (*env)->GetObjectArrayElement(env, (jobjectArray)obj, i);
            JSValue je = yeow_java_to_js(c, env, el);
            if (el) (*env)->DeleteLocalRef(env, el);
            JS_SetPropertyUint32(c->ctx, arr, (uint32_t)i, je);
        }
        return arr;
    }

    return JS_ThrowInternalError(c->ctx, "unsupported Java value type passed to JS");
}
