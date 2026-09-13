/*
 * QuickJS context lifecycle, error marshalling, promise rejection tracking,
 * pending-job pump, interrupt hook and the JS -> Java callback dispatcher.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "yeow_quickjs.h"
#include "polyfill.h"
#include "binary.h"

/* ── tiny growable buffer ──────────────────────────────────────────── */

typedef struct YeowBuf {
    char *p;
    size_t len;
    size_t cap;
} YeowBuf;

static int buf_reserve(YeowBuf *b, size_t extra) {
    if (b->len + extra + 1 <= b->cap) return 1;
    size_t cap = b->cap ? b->cap * 2 : 256;
    while (cap < b->len + extra + 1) cap *= 2;
    char *np = (char *)realloc(b->p, cap);
    if (!np) return 0;
    b->p = np;
    b->cap = cap;
    return 1;
}

static void buf_putn(YeowBuf *b, const char *s, size_t n) {
    if (!buf_reserve(b, n)) return;
    memcpy(b->p + b->len, s, n);
    b->len += n;
    b->p[b->len] = '\0';
}

static void buf_puts(YeowBuf *b, const char *s) { buf_putn(b, s, strlen(s)); }
static void buf_putc(YeowBuf *b, char ch) { buf_putn(b, &ch, 1); }

static void json_escape(YeowBuf *b, const char *s, size_t n) {
    buf_putc(b, '"');
    for (size_t i = 0; i < n; i++) {
        unsigned char ch = (unsigned char)s[i];
        switch (ch) {
            case '\\': buf_puts(b, "\\\\"); break;
            case '"':  buf_puts(b, "\\\""); break;
            case '\n': buf_puts(b, "\\n"); break;
            case '\r': buf_puts(b, "\\r"); break;
            case '\t': buf_puts(b, "\\t"); break;
            case '\b': buf_puts(b, "\\b"); break;
            case '\f': buf_puts(b, "\\f"); break;
            default:
                if (ch < 0x20) {
                    char u[7];
                    snprintf(u, sizeof(u), "\\u%04x", ch);
                    buf_puts(b, u);
                } else {
                    buf_putc(b, (char)ch);
                }
        }
    }
    buf_putc(b, '"');
}

/* Serialise a JS error to a JSON object, matching the historical Yeow shape. */
static char *error_json(YeowCtx *c, JSValueConst err) {
    YeowBuf b = {0};
    buf_putc(&b, '{');

    if (JS_IsError(c->ctx, err)) {
        JSValue v = JS_GetPropertyStr(c->ctx, err, "message");
        if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
            const char *s = JS_ToCString(c->ctx, v);
            if (s) {
                buf_puts(&b, "\"message\":");
                json_escape(&b, s, strlen(s));
                buf_putc(&b, ',');
                JS_FreeCString(c->ctx, s);
            }
        }
        JS_FreeValue(c->ctx, v);

        v = JS_GetPropertyStr(c->ctx, err, "fileName");
        if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
            const char *s = JS_ToCString(c->ctx, v);
            if (s) {
                buf_puts(&b, "\"fileName\":");
                json_escape(&b, s, strlen(s));
                buf_putc(&b, ',');
                JS_FreeCString(c->ctx, s);
            }
        }
        JS_FreeValue(c->ctx, v);

        v = JS_GetPropertyStr(c->ctx, err, "lineNumber");
        if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
            double d = 0;
            JS_ToFloat64(c->ctx, &d, v);
            char num[32];
            snprintf(num, sizeof(num), "\"lineNumber\":%d,", (int)d);
            buf_puts(&b, num);
        }
        JS_FreeValue(c->ctx, v);

        v = JS_GetPropertyStr(c->ctx, err, "columnNumber");
        if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
            double d = 0;
            JS_ToFloat64(c->ctx, &d, v);
            char num[32];
            snprintf(num, sizeof(num), "\"columnNumber\":%d,", (int)d);
            buf_puts(&b, num);
        }
        JS_FreeValue(c->ctx, v);

        v = JS_GetPropertyStr(c->ctx, err, "stack");
        if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
            const char *s = JS_ToCString(c->ctx, v);
            if (s) {
                buf_puts(&b, "\"stack\":");
                json_escape(&b, s, strlen(s));
                buf_putc(&b, ',');
                JS_FreeCString(c->ctx, s);
            }
        }
        JS_FreeValue(c->ctx, v);

        buf_puts(&b, "\"jsError\":true");
    } else {
        const char *s = JS_ToCString(c->ctx, err);
        buf_puts(&b, "\"message\":");
        json_escape(&b, s ? s : "", s ? strlen(s) : 0);
        buf_puts(&b, ",\"jsError\":false");
        if (s) JS_FreeCString(c->ctx, s);
    }

    buf_putc(&b, '}');
    return b.p ? b.p : strdup("{}");
}

/* ── runtime hooks ─────────────────────────────────────────────────── */

static void on_rejection(JSContext *ctx, JSValueConst promise, JSValueConst reason,
                         JS_BOOL is_handled, void *opaque) {
    YeowCtx *c = (YeowCtx *)opaque;

    if (!is_handled) {
        if (c->reject_len == c->reject_cap) {
            int cap = c->reject_cap ? c->reject_cap * 2 : 4;
            YeowRejection *nr = (YeowRejection *)realloc(c->rejects, sizeof(YeowRejection) * (size_t)cap);
            if (!nr) return;
            c->rejects = nr;
            c->reject_cap = cap;
        }
        c->rejects[c->reject_len].promise = JS_DupValue(ctx, promise);
        c->rejects[c->reject_len].reason = JS_DupValue(ctx, reason);
        c->reject_len++;
        return;
    }

    /* handled: pair by promise identity, not queue position. */
    for (int i = 0; i < c->reject_len; i++) {
        if (JS_StrictEq(ctx, c->rejects[i].promise, promise)) {
            JS_FreeValue(ctx, c->rejects[i].promise);
            JS_FreeValue(ctx, c->rejects[i].reason);
            c->rejects[i] = c->rejects[c->reject_len - 1];
            c->reject_len--;
            return;
        }
    }
}

static int on_interrupt(JSRuntime *rt, void *opaque) {
    (void)rt;
    YeowCtx *c = (YeowCtx *)opaque;
    return atomic_exchange(&c->interrupted, 0) ? 1 : 0;
}

/* ── pending jobs / rejections ─────────────────────────────────────── */

int yeow_run_jobs(YeowCtx *c, JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) return 0;

    JSContext *ctx1 = NULL;
    int have_error = 0;
    char *job_error = NULL;

    for (;;) {
        int err = JS_ExecutePendingJob(c->rt, &ctx1);
        if (err == 0) break;
        if (err < 0) {
            /* Capture the first job error but keep draining the queue so JS-side
               rejection handlers still run; clear the rest. */
            if (!have_error) {
                JSValue e = JS_GetException(c->ctx);
                job_error = error_json(c, e);
                JS_FreeValue(c->ctx, e);
                have_error = 1;
            } else {
                JSValue e = JS_GetException(c->ctx);
                JS_FreeValue(c->ctx, e);
            }
        }
    }

    if (!have_error && c->reject_len > 0) {
        YeowBuf b = {0};
        buf_puts(&b, "UnhandledPromiseRejectionException: ");
        for (int i = 0; i < c->reject_len; i++) {
            char *j = error_json(c, c->rejects[i].reason);
            if (j) {
                buf_puts(&b, j);
                free(j);
            }
            buf_putc(&b, '\n');
            JS_FreeValue(c->ctx, c->rejects[i].promise);
            JS_FreeValue(c->ctx, c->rejects[i].reason);
        }
        c->reject_len = 0;
        have_error = 1;
        job_error = b.p;
    }

    if (have_error) {
        yeow_throw_native(env, job_error ? job_error : "JS error");
        free(job_error);
        return 0;
    }
    return 1;
}

void yeow_throw_js(JNIEnv *env, YeowCtx *c) {
    JSValue err = JS_GetException(c->ctx);
    char *json = error_json(c, err);
    JS_FreeValue(c->ctx, err);
    yeow_throw_native(env, json ? json : "JS error");
    free(json);
}

/* ── JS -> Java upcall ─────────────────────────────────────────────── */

static JSValue java_exception_to_js(YeowCtx *c, JNIEnv *env) {
    jthrowable ex = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (!ex) return JS_EXCEPTION;

    jclass cls = (*env)->GetObjectClass(env, ex);
    char *msg = NULL;
    if (cls) {
        jmethodID mid = (*env)->GetMethodID(env, cls, "getMessage", "()Ljava/lang/String;");
        if (mid) {
            jstring s = (jstring)(*env)->CallObjectMethod(env, ex, mid);
            if (s) {
                msg = yeow_jstring_to_utf8(env, s, NULL);
                (*env)->DeleteLocalRef(env, s);
            }
        }
    }

    JSValue r = JS_ThrowInternalError(c->ctx, "%s", msg ? msg : "Java exception in callback");
    free(msg);
    if (cls) (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, ex);
    return r;
}

/* Raise an uncatchable abort (termination requested). JS catch/finally cannot intercept it. */
static JSValue throw_terminated(YeowCtx *c) {
    JS_FreeValue(c->ctx, JS_GetException(c->ctx)); /* drop any pending (catchable) exception */
    JSValue err = JS_ThrowInternalError(c->ctx, "terminated");
    JS_SetUncatchableException(c->ctx, 1);
    return err;
}

static JSValue js_dispatch(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                           int magic, JSValue *func_data) {
    (void)this_val;
    (void)func_data;
    YeowCtx *c = (YeowCtx *)JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    if (atomic_load(&c->terminating)) return throw_terminated(c);
    JNIEnv *env = yeow_env(c);
    if (!env) return JS_ThrowInternalError(ctx, "JNI env unavailable");

    jobjectArray jargs = (*env)->NewObjectArray(env, argc, c->clsObject, NULL);
    if (!jargs) return JS_ThrowInternalError(ctx, "out of memory building callback args");

    for (int i = 0; i < argc; i++) {
        jobject a = yeow_js_to_java(c, env, argv[i]);
        if (a) {
            (*env)->SetObjectArrayElement(env, jargs, i, a);
            (*env)->DeleteLocalRef(env, a);
        }
    }

    jobject res = (*env)->CallObjectMethod(env, c->self, c->mInvokeCallback, (jint)magic, jargs);
    (*env)->DeleteLocalRef(env, jargs);

    if (atomic_load(&c->terminating)) {
        if (res) (*env)->DeleteLocalRef(env, res);
        return throw_terminated(c);
    }
    if ((*env)->ExceptionCheck(env)) return java_exception_to_js(c, env);

    JSValue out = yeow_java_to_js(c, env, res);
    if (res) (*env)->DeleteLocalRef(env, res);
    return out;
}

JSValue yeow_make_callback(YeowCtx *c, int cb_id) {
    return JS_NewCFunctionData(c->ctx, js_dispatch, 1, cb_id, 0, NULL);
}

/* ── lifecycle ─────────────────────────────────────────────────────── */

static jclass global_class(JNIEnv *env, const char *name) {
    jclass local = (*env)->FindClass(env, name);
    if (!local) return NULL;
    jclass g = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return g;
}

YeowCtx *yeow_create(JNIEnv *env, jobject thiz) {
    YeowCtx *c = (YeowCtx *)calloc(1, sizeof(YeowCtx));
    if (!c) return NULL;

    c->vm = yeow_vm;
    c->rt = JS_NewRuntime();
    if (!c->rt) goto fail;
    c->ctx = JS_NewContext(c->rt);
    if (!c->ctx) goto fail;

    JS_SetRuntimeOpaque(c->rt, c);
    JS_SetHostPromiseRejectionTracker(c->rt, on_rejection, c);
    JS_SetInterruptHandler(c->rt, on_interrupt, c);
    atomic_init(&c->interrupted, 0);
    atomic_init(&c->terminating, 0);

    /* Native-backed globals (performance.now(), ...). */
    yeow_install_polyfills(c->ctx);
    /* Binary transport primitives (__yeowWrite / __yeowRead). */
    yeow_binary_install(c->ctx);

    c->self = (*env)->NewGlobalRef(env, thiz);
    if (!c->self) goto fail;

    c->clsObject = global_class(env, "java/lang/Object");
    c->clsString = global_class(env, "java/lang/String");
    c->clsBoolean = global_class(env, "java/lang/Boolean");
    c->clsInteger = global_class(env, "java/lang/Integer");
    c->clsLong = global_class(env, "java/lang/Long");
    c->clsDouble = global_class(env, "java/lang/Double");
    c->clsMap = global_class(env, "java/util/LinkedHashMap");
    c->clsByteArray = global_class(env, "[B");
    c->clsObjectArray = global_class(env, "[Ljava/lang/Object;");
    jclass ctxCls = global_class(env, "wiki/yexin/quickjs/QuickJSContext");
    jclass charsetCls = global_class(env, "java/nio/charset/StandardCharsets");

    if (!c->clsObject || !c->clsString || !c->clsBoolean || !c->clsInteger || !c->clsLong ||
        !c->clsDouble || !c->clsMap || !c->clsByteArray || !c->clsObjectArray || !ctxCls || !charsetCls) {
        if (ctxCls) (*env)->DeleteGlobalRef(env, ctxCls);
        if (charsetCls) (*env)->DeleteGlobalRef(env, charsetCls);
        goto fail;
    }

    {
        jfieldID fid = (*env)->GetStaticFieldID(env, charsetCls, "UTF_8", "Ljava/nio/charset/Charset;");
        jobject u8 = fid ? (*env)->GetStaticObjectField(env, charsetCls, fid) : NULL;
        if (!u8) {
            (*env)->DeleteGlobalRef(env, ctxCls);
            (*env)->DeleteGlobalRef(env, charsetCls);
            goto fail;
        }
        c->charsetUtf8 = (*env)->NewGlobalRef(env, u8);
        (*env)->DeleteLocalRef(env, u8);
    }
    (*env)->DeleteGlobalRef(env, charsetCls);

    c->mBooleanValueOf = (*env)->GetStaticMethodID(env, c->clsBoolean, "valueOf", "(Z)Ljava/lang/Boolean;");
    c->mIntegerValueOf = (*env)->GetStaticMethodID(env, c->clsInteger, "valueOf", "(I)Ljava/lang/Integer;");
    c->mLongValueOf = (*env)->GetStaticMethodID(env, c->clsLong, "valueOf", "(J)Ljava/lang/Long;");
    c->mDoubleValueOf = (*env)->GetStaticMethodID(env, c->clsDouble, "valueOf", "(D)Ljava/lang/Double;");
    c->mBooleanValue = (*env)->GetMethodID(env, c->clsBoolean, "booleanValue", "()Z");
    c->mIntegerValue = (*env)->GetMethodID(env, c->clsInteger, "intValue", "()I");
    c->mLongValue = (*env)->GetMethodID(env, c->clsLong, "longValue", "()J");
    c->mDoubleValue = (*env)->GetMethodID(env, c->clsDouble, "doubleValue", "()D");
    c->mStringCtor = (*env)->GetMethodID(env, c->clsString, "<init>", "([BLjava/nio/charset/Charset;)V");
    c->mMapInit = (*env)->GetMethodID(env, c->clsMap, "<init>", "()V");
    c->mMapPut = (*env)->GetMethodID(env, c->clsMap, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    c->mInvokeCallback = (*env)->GetMethodID(env, ctxCls, "invokeCallback", "(I[Ljava/lang/Object;)Ljava/lang/Object;");
    (*env)->DeleteGlobalRef(env, ctxCls);

    if ((*env)->ExceptionCheck(env)) goto fail;

    return c;

fail:
    yeow_destroy(env, c);
    return NULL;
}

void yeow_destroy(JNIEnv *env, YeowCtx *c) {
    if (!c) return;

    for (int i = 0; i < c->reject_len; i++) {
        if (c->ctx) {
            JS_FreeValue(c->ctx, c->rejects[i].promise);
            JS_FreeValue(c->ctx, c->rejects[i].reason);
        }
    }
    free(c->rejects);

    for (int i = 0; i < c->bound_len; i++) {
        if (c->ctx) JS_FreeValue(c->ctx, c->bound[i]);
    }
    free(c->bound);

    if (c->ctx) JS_FreeContext(c->ctx);
    if (c->rt) JS_FreeRuntime(c->rt);

    if (c->self) (*env)->DeleteGlobalRef(env, c->self);
    if (c->charsetUtf8) (*env)->DeleteGlobalRef(env, c->charsetUtf8);
    if (c->clsObject) (*env)->DeleteGlobalRef(env, c->clsObject);
    if (c->clsString) (*env)->DeleteGlobalRef(env, c->clsString);
    if (c->clsBoolean) (*env)->DeleteGlobalRef(env, c->clsBoolean);
    if (c->clsInteger) (*env)->DeleteGlobalRef(env, c->clsInteger);
    if (c->clsLong) (*env)->DeleteGlobalRef(env, c->clsLong);
    if (c->clsDouble) (*env)->DeleteGlobalRef(env, c->clsDouble);
    if (c->clsMap) (*env)->DeleteGlobalRef(env, c->clsMap);
    if (c->clsByteArray) (*env)->DeleteGlobalRef(env, c->clsByteArray);
    if (c->clsObjectArray) (*env)->DeleteGlobalRef(env, c->clsObjectArray);

    free(c);
}
