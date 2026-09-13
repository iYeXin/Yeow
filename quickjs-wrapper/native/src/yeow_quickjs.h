/*
 * Yeow QuickJS bridge - internal header.
 *
 * Yeow-specific JNI bridge around QuickJS. Not a general-purpose wrapper:
 * the Java <-> JS boundary only needs context lifecycle, evaluate, global
 * function registration (JS -> Java upcall) and global function invocation
 * (Java -> JS downcall), plus the pending-job pump and the interrupt hook.
 */
#ifndef YEOW_QUICKJS_INTERNAL_H
#define YEOW_QUICKJS_INTERNAL_H

#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>

#include "quickjs.h"

#define YEOW_MAX_SAFE_INTEGER (((int64_t)1 << 53) - 1)

typedef struct YeowRejection {
    JSValue promise;
    JSValue reason;
} YeowRejection;

typedef struct YeowCtx {
    JavaVM *vm;
    jobject self; /* global ref to wiki.yexin.quickjs.QuickJSContext */

    JSRuntime *rt;
    JSContext *ctx;
    atomic_int interrupted;
    /** Sticky: set once termination is requested. The JS->Java upcall boundary
        (`$_send`) raises an uncatchable abort while this is set. */
    atomic_int terminating;

    YeowRejection *rejects;
    int reject_len;
    int reject_cap;

    /* Bound global functions (JSValue, owned). Handle = index + 1. */
    JSValue *bound;
    int bound_len;
    int bound_cap;

    /* Resident transport buffer (Java-allocated DirectByteBuffer). */
    uint8_t *buf;
    size_t buf_cap;

    /* cached Java classes (global refs) */
    jclass clsObject;
    jclass clsString;
    jclass clsBoolean;
    jclass clsInteger;
    jclass clsLong;
    jclass clsDouble;
    jclass clsMap;
    jclass clsByteArray;
    jclass clsObjectArray;
    jobject charsetUtf8;

    /* cached method ids */
    jmethodID mBooleanValueOf;
    jmethodID mIntegerValueOf;
    jmethodID mLongValueOf;
    jmethodID mDoubleValueOf;
    jmethodID mBooleanValue;
    jmethodID mIntegerValue;
    jmethodID mLongValue;
    jmethodID mDoubleValue;
    jmethodID mStringCtor; /* (byte[], Charset) */
    jmethodID mMapInit;
    jmethodID mMapPut;
    jmethodID mInvokeCallback; /* (I[Ljava/lang/Object;)Ljava/lang/Object; on self */
} YeowCtx;

extern JavaVM *yeow_vm;

/* JNI helpers (convert.c) */
JNIEnv *yeow_env(YeowCtx *c);
char *yeow_jstring_to_utf8(JNIEnv *env, jstring s, size_t *out_len);
jstring yeow_new_jstring(JNIEnv *env, YeowCtx *c, const char *utf8, size_t len);
jobject yeow_js_to_java(YeowCtx *c, JNIEnv *env, JSValueConst v);
JSValue yeow_java_to_js(YeowCtx *c, JNIEnv *env, jobject obj);

/* context.c */
YeowCtx *yeow_create(JNIEnv *env, jobject thiz);
void yeow_destroy(JNIEnv *env, YeowCtx *c);
void yeow_throw_js(JNIEnv *env, YeowCtx *c);
int yeow_run_jobs(YeowCtx *c, JNIEnv *env);
JSValue yeow_make_callback(YeowCtx *c, int cb_id);

/* bridge.c */
void yeow_throw_native(JNIEnv *env, const char *msg);

#endif /* YEOW_QUICKJS_INTERNAL_H */
