/*
 * JNI entry points for the Yeow QuickJS bridge.
 *
 * The Java API is intentionally tiny: create/destroy, evaluate,
 * setGlobalFunction (JS -> Java upcall), callGlobal (Java -> JS downcall),
 * the pending-job pump and interrupt. No JS object handles cross the boundary.
 */
#include <stdlib.h>
#include <string.h>

#include "yeow_quickjs.h"

JavaVM *yeow_vm = NULL;

void yeow_throw_native(JNIEnv *env, const char *msg) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, "wiki/yexin/quickjs/QuickJSException");
    if (!cls) return;
    (*env)->ThrowNew(env, cls, msg ? msg : "QuickJS error");
    (*env)->DeleteLocalRef(env, cls);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    yeow_vm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    yeow_vm = NULL;
}

JNIEXPORT jlong JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeCreate(JNIEnv *env, jobject thiz) {
    YeowCtx *c = yeow_create(env, thiz);
    if (!c) {
        yeow_throw_native(env, "failed to create QuickJS context");
        return 0;
    }
    return (jlong)(intptr_t)c;
}

JNIEXPORT void JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeRegisterBuffer(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c) return;
    if (buffer == NULL) {
        c->buf = NULL;
        c->buf_cap = 0;
        return;
    }
    c->buf = (uint8_t *)(*env)->GetDirectBufferAddress(env, buffer);
    c->buf_cap = (size_t)(*env)->GetDirectBufferCapacity(env, buffer);
}

JNIEXPORT void JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeDestroy(JNIEnv *env, jobject thiz,
                                                                         jlong handle) {
    (void)thiz;
    yeow_destroy(env, (YeowCtx *)(intptr_t)handle);
}

JNIEXPORT jobject JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeEvaluate(JNIEnv *env, jobject thiz,
                                                                          jlong handle, jstring code,
                                                                          jstring file_name) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c || !code) return NULL;

    size_t code_len = 0, file_len = 0;
    char *code_utf8 = yeow_jstring_to_utf8(env, code, &code_len);
    char *file_utf8 = file_name ? yeow_jstring_to_utf8(env, file_name, &file_len) : NULL;
    if (!code_utf8) {
        free(file_utf8);
        return NULL;
    }

    JSValue r = JS_Eval(c->ctx, code_utf8, code_len, file_utf8 ? file_utf8 : "unknown.js",
                        JS_EVAL_TYPE_GLOBAL);
    free(code_utf8);
    free(file_utf8);

    if (JS_IsException(r)) {
        yeow_throw_js(env, c);
        return NULL;
    }
    if (!yeow_run_jobs(c, env)) {
        JS_FreeValue(c->ctx, r);
        return NULL;
    }

    jobject out = yeow_js_to_java(c, env, r);
    JS_FreeValue(c->ctx, r);
    return out;
}

JNIEXPORT void JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeSetGlobalFunction(
    JNIEnv *env, jobject thiz, jlong handle, jstring name, jint callback_id) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c || !name) return;

    char *name_utf8 = yeow_jstring_to_utf8(env, name, NULL);
    if (!name_utf8) return;

    JSValue global = JS_GetGlobalObject(c->ctx);
    JSValue fn = yeow_make_callback(c, (int)callback_id);
    JS_SetPropertyStr(c->ctx, global, name_utf8, fn);
    JS_FreeValue(c->ctx, global);
    free(name_utf8);
}

JNIEXPORT jboolean JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeHasGlobalFunction(
    JNIEnv *env, jobject thiz, jlong handle, jstring name) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c || !name) return JNI_FALSE;

    char *name_utf8 = yeow_jstring_to_utf8(env, name, NULL);
    if (!name_utf8) return JNI_FALSE;

    JSValue global = JS_GetGlobalObject(c->ctx);
    JSValue fn = JS_GetPropertyStr(c->ctx, global, name_utf8);
    jboolean ok = JS_IsFunction(c->ctx, fn) ? JNI_TRUE : JNI_FALSE;
    JS_FreeValue(c->ctx, fn);
    JS_FreeValue(c->ctx, global);
    free(name_utf8);
    return ok;
}

JNIEXPORT jlong JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeBindGlobal(JNIEnv *env, jobject thiz,
                                                                               jlong handle, jstring name) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c || !name) return 0;

    char *name_utf8 = yeow_jstring_to_utf8(env, name, NULL);
    if (!name_utf8) return 0;

    JSValue global = JS_GetGlobalObject(c->ctx);
    JSValue fn = JS_GetPropertyStr(c->ctx, global, name_utf8);
    JS_FreeValue(c->ctx, global);
    free(name_utf8);

    if (!JS_IsFunction(c->ctx, fn)) {
        JS_FreeValue(c->ctx, fn);
        return 0;
    }

    if (c->bound_len == c->bound_cap) {
        int cap = c->bound_cap ? c->bound_cap * 2 : 4;
        JSValue *nb = (JSValue *)realloc(c->bound, sizeof(JSValue) * (size_t)cap);
        if (!nb) {
            JS_FreeValue(c->ctx, fn);
            return 0;
        }
        c->bound = nb;
        c->bound_cap = cap;
    }
    c->bound[c->bound_len++] = fn; /* takes ownership */
    return (jlong)c->bound_len;    /* 1-based handle */
}

JNIEXPORT jobject JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeCallHandle(
    JNIEnv *env, jobject thiz, jlong handle, jlong fn_handle, jstring arg) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c || fn_handle < 1 || fn_handle > c->bound_len) return NULL;

    JSValue fn = c->bound[fn_handle - 1];

    JSValue jarg = JS_UNDEFINED;
    if (arg) {
        size_t arg_len = 0;
        char *arg_utf8 = yeow_jstring_to_utf8(env, arg, &arg_len);
        jarg = JS_NewStringLen(c->ctx, arg_utf8 ? arg_utf8 : "", arg_len);
        free(arg_utf8);
    }

    JSValue r = JS_Call(c->ctx, fn, JS_UNDEFINED, 1, &jarg);
    JS_FreeValue(c->ctx, jarg);

    if (JS_IsException(r)) {
        yeow_throw_js(env, c);
        return NULL;
    }
    if (!yeow_run_jobs(c, env)) {
        JS_FreeValue(c->ctx, r);
        return NULL;
    }
    jobject out = yeow_js_to_java(c, env, r);
    JS_FreeValue(c->ctx, r);
    return out;
}

JNIEXPORT jobject JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeCallGlobal(
    JNIEnv *env, jobject thiz, jlong handle, jstring name, jstring arg) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c || !name) return NULL;

    char *name_utf8 = yeow_jstring_to_utf8(env, name, NULL);
    if (!name_utf8) return NULL;

    JSValue global = JS_GetGlobalObject(c->ctx);
    JSValue fn = JS_GetPropertyStr(c->ctx, global, name_utf8);
    JS_FreeValue(c->ctx, global);
    free(name_utf8);

    if (!JS_IsFunction(c->ctx, fn)) {
        JS_FreeValue(c->ctx, fn);
        yeow_throw_native(env, "global function not found");
        return NULL;
    }

    JSValue jarg = JS_UNDEFINED;
    if (arg) {
        size_t arg_len = 0;
        char *arg_utf8 = yeow_jstring_to_utf8(env, arg, &arg_len);
        jarg = JS_NewStringLen(c->ctx, arg_utf8 ? arg_utf8 : "", arg_len);
        free(arg_utf8);
    }

    JSValue r = JS_Call(c->ctx, fn, JS_UNDEFINED, 1, &jarg);
    JS_FreeValue(c->ctx, jarg);
    JS_FreeValue(c->ctx, fn);

    if (JS_IsException(r)) {
        yeow_throw_js(env, c);
        return NULL;
    }
    if (!yeow_run_jobs(c, env)) {
        JS_FreeValue(c->ctx, r);
        return NULL;
    }

    jobject out = yeow_js_to_java(c, env, r);
    JS_FreeValue(c->ctx, r);
    return out;
}

JNIEXPORT void JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeDrainJobs(JNIEnv *env, jobject thiz,
                                                                             jlong handle) {
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c) return;
    /* One JNI transition for the whole microtask queue; job errors and
       unhandled rejections surface as QuickJSException. */
    yeow_run_jobs(c, env);
}

JNIEXPORT void JNICALL Java_wiki_yexin_quickjs_QuickJSContext_nativeInterrupt(JNIEnv *env, jobject thiz,
                                                                           jlong handle) {
    (void)env;
    (void)thiz;
    YeowCtx *c = (YeowCtx *)(intptr_t)handle;
    if (!c) return;
    atomic_store(&c->interrupted, 1);
    /* Sticky: from now on the JS->Java upcall boundary aborts uncatchably, so a plugin
       that keeps calling $_send cannot keep producing side effects during termination. */
    atomic_store(&c->terminating, 1);
}
