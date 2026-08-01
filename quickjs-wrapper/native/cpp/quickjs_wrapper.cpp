//
// Created by yonglan.whl on 2021/7/14.
//
#include "quickjs_wrapper.h"
#include "../quickjs/cutils.h"
#include "quickjs_extend_libraries.h"
#include <cstdint>
#include <cstring>
#include <cmath>

#define MAX_SAFE_INTEGER (((int64_t)1 << 53) - 1)

// util
static string getJavaName(JNIEnv *env, jobject javaClass)
{
    auto classType = env->GetObjectClass(javaClass);
    const auto method = env->GetMethodID(classType, "getName", "()Ljava/lang/String;");
    auto javaString = (jstring)(env->CallObjectMethod(javaClass, method));
    const auto s = env->GetStringUTFChars(javaString, nullptr);

    std::string str(s);
    env->ReleaseStringUTFChars(javaString, s);
    env->DeleteLocalRef(javaString);
    env->DeleteLocalRef(classType);
    return str;
}

// quickjs 没有提供 JS_IsArrayBuffer 方法，这里通过取巧的方式来实现，后续可以替换掉
static bool JS_IsArrayBuffer(JSValue value)
{
    // quickjs 里的 ArrayBuffer 对应的类型枚举值
    int8_t JS_CLASS_ARRAY_BUFFER = 19;
    return JS_GetClassID(value) == JS_CLASS_ARRAY_BUFFER;
}

// ── Base64 helpers ──────────────────────────────────────────────────
static const char base64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// Compile-time constant decode table
static const signed char b64_decode[256] = {
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
    52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
    -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
    15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
    -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
    41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1
};

static JSValue js_uint8ArrayToBase64(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    if (argc < 1 || !JS_IsArrayBuffer(argv[0])) {
        return JS_ThrowTypeError(ctx, "uint8ArrayToBase64 expects an ArrayBuffer");
    }
    size_t len;
    uint8_t *buf = JS_GetArrayBuffer(ctx, &len, argv[0]);
    if (!buf) return JS_ThrowTypeError(ctx, "uint8ArrayToBase64: failed to read buffer");

    size_t outLen = ((len + 2) / 3) * 4;
    char *out = (char *)js_malloc(ctx, outLen + 1);
    if (!out) return JS_ThrowOutOfMemory(ctx);

    size_t j = 0;
    for (size_t ip = 0; ip < len; ip += 3) {
        int n = (len - ip) >= 3 ? 3 : (int)(len - ip); // bytes read this triplet (1, 2, or 3)
        uint32_t triplet = (uint32_t)buf[ip] << 16;
        if (n >= 2) triplet |= (uint32_t)buf[ip + 1] << 8;
        if (n >= 3) triplet |= (uint32_t)buf[ip + 2];
        out[j++] = base64_table[(triplet >> 18) & 0x3F];
        out[j++] = base64_table[(triplet >> 12) & 0x3F];
        out[j++] = (n >= 2) ? base64_table[(triplet >> 6) & 0x3F] : '=';
        out[j++] = (n >= 3) ? base64_table[triplet & 0x3F] : '=';
    }
    out[outLen] = '\0';

    JSValue result = JS_NewString(ctx, out);
    js_free(ctx, out);
    return result;
}

static JSValue js_base64ToUint8Array(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    if (argc < 1 || !JS_IsString(argv[0])) {
        return JS_ThrowTypeError(ctx, "base64ToUint8Array expects a string");
    }
    const char *str = JS_ToCString(ctx, argv[0]);
    if (!str) return JS_ThrowOutOfMemory(ctx);

    size_t len = strlen(str);
    if (len == 0 || len % 4 != 0) {
        JS_FreeCString(ctx, str);
        return JS_ThrowTypeError(ctx, "base64ToUint8Array: invalid input length");
    }

    size_t pad = 0;
    while (pad < 2 && len > pad && str[len - 1 - pad] == '=') pad++;
    size_t groupCount = len / 4;
    size_t outLen = groupCount * 3 - pad;
    if (outLen == 0) {
        JS_FreeCString(ctx, str);
        return JS_ThrowTypeError(ctx, "base64ToUint8Array: empty output");
    }

    uint8_t *out = (uint8_t *)js_malloc(ctx, outLen);
    if (!out) { JS_FreeCString(ctx, str); return JS_ThrowOutOfMemory(ctx); }

    // Build decode table at runtime — see no static const array
    unsigned char d[256];
    memset(d, 0xFF, 256);
    for (int i = 0; i < 64; i++) d[(unsigned char)base64_table[i]] = (unsigned char)i;

    size_t j = 0;
    for (size_t ip = 0; ip < len; ip += 4) {
        if (str[ip] == '=') break;
        unsigned char sa = d[(unsigned char)str[ip]];
        unsigned char sb = d[(unsigned char)str[ip + 1]];
        unsigned char sc = d[(unsigned char)str[ip + 2]];
        unsigned char sd = d[(unsigned char)str[ip + 3]];
        if (sa == 0xFF || sb == 0xFF) {
            js_free(ctx, out); JS_FreeCString(ctx, str);
            return JS_ThrowTypeError(ctx, "base64ToUint8Array: invalid character");
        }
        uint32_t triplet = ((uint32_t)sa << 18) | ((uint32_t)sb << 12);
        if (sc != 0xFF) triplet |= ((uint32_t)sc << 6);
        if (sd != 0xFF) triplet |= (uint32_t)sd;
        if (j < outLen) out[j++] = (uint8_t)(triplet >> 16);
        if (j < outLen && sc != 0xFF) out[j++] = (uint8_t)(triplet >> 8);
        if (j < outLen && sd != 0xFF) out[j++] = (uint8_t)triplet;
    }

    JS_FreeCString(ctx, str);
    JSValue result = JS_NewArrayBufferCopy(ctx, out, outLen);
    js_free(ctx, out);
    return result;
}

static void injectBase64Functions(JSContext *ctx)
{
    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "uint8ArrayToBase64",
        JS_NewCFunction(ctx, js_uint8ArrayToBase64, "uint8ArrayToBase64", 1));
    JS_SetPropertyStr(ctx, global, "base64ToUint8Array",
        JS_NewCFunction(ctx, js_base64ToUint8Array, "base64ToUint8Array", 1));
    JS_FreeValue(ctx, global);
}

static void tryToTriggerOnError(JSContext *ctx, JSValueConst *error)
{
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue onerror = JS_GetPropertyStr(ctx, global, "onError");
    if (JS_IsNull(onerror))
    {
        // may be lowercase
        onerror = JS_GetPropertyStr(ctx, global, "onerror");
    }

    if (JS_IsNull(onerror))
    {
        // do nothing
        return;
    }

    JS_Call(ctx, onerror, global, 1, error);
    JS_FreeValue(ctx, onerror);
    JS_FreeValue(ctx, global);
}

static string jsonEscape(const char *s) {
    if (!s) return "\"\"";
    string r;
    for (const char *p = s; *p; p++) {
        switch (*p) {
            case '\\': r += "\\\\"; break;
            case '"':  r += "\\\""; break;
            case '\n': r += "\\n"; break;
            case '\r': r += "\\r"; break;
            case '\t': r += "\\t"; break;
            default:   r += *p;
        }
    }
    return "\"" + r + "\"";
}

string QuickJSWrapper::getJSErrorStr(JSContext *ctx, JSValueConst error)
{
    JSValue val;
    bool is_error;
    is_error = JS_IsError(ctx, error);
    string result = "{";

    if (is_error)
    {
        tryToTriggerOnError(ctx, &error);

        // message
        val = JS_GetPropertyStr(ctx, error, "message");
        if (!JS_IsUndefined(val)) {
            const char *s = JS_ToCString(ctx, val);
            result += "\"message\":" + jsonEscape(s) + ",";
            JS_FreeCString(ctx, s);
        }
        JS_FreeValue(ctx, val);

        // fileName
        val = JS_GetPropertyStr(ctx, error, "fileName");
        if (!JS_IsUndefined(val)) {
            const char *s = JS_ToCString(ctx, val);
            result += "\"fileName\":" + jsonEscape(s) + ",";
            JS_FreeCString(ctx, s);
        }
        JS_FreeValue(ctx, val);

        // lineNumber
        val = JS_GetPropertyStr(ctx, error, "lineNumber");
        if (!JS_IsUndefined(val)) {
            double n; JS_ToFloat64(ctx, &n, val);
            result += "\"lineNumber\":" + std::to_string((int)n) + ",";
        }
        JS_FreeValue(ctx, val);

        // columnNumber
        val = JS_GetPropertyStr(ctx, error, "columnNumber");
        if (!JS_IsUndefined(val)) {
            double n; JS_ToFloat64(ctx, &n, val);
            result += "\"columnNumber\":" + std::to_string((int)n) + ",";
        }
        JS_FreeValue(ctx, val);

        // stack
        val = JS_GetPropertyStr(ctx, error, "stack");
        if (!JS_IsUndefined(val)) {
            const char *s = JS_ToCString(ctx, val);
            result += "\"stack\":" + jsonEscape(s) + ",";
            JS_FreeCString(ctx, s);
        }
        JS_FreeValue(ctx, val);

        result += "\"jsError\":true";
    }
    else
    {
        const char *s = JS_ToCString(ctx, error);
        result += "\"message\":" + jsonEscape(s) + ",\"jsError\":false";
        JS_FreeCString(ctx, s);
    }

    result += "}";
    return result;
}

string QuickJSWrapper::getJSErrorStr(JSContext *ctx)
{
    JSValue error = JS_GetException(ctx);
    string error_str = QuickJSWrapper::getJSErrorStr(ctx, error);
    JS_FreeValue(ctx, error);
    return error_str;
}

static void throwJavaException(JNIEnv *env, const char *exceptionClass, const char *fmt, ...)
{
    char msg[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(msg, sizeof(msg), fmt, args);
    va_end(args);
    jclass e = env->FindClass(exceptionClass);
    env->ThrowNew(e, msg);
    env->DeleteLocalRef(e);
}

void QuickJSWrapper::throwJSException(JNIEnv *env, const char *msg)
{
    if (env->ExceptionCheck())
    {
        return;
    }

    jclass e = env->FindClass("com/whl/quickjs/wrapper/QuickJSException");
    jmethodID init = env->GetMethodID(e, "<init>", "(Ljava/lang/String;Z)V");
    jstring ret = env->NewStringUTF(msg);
    auto t = (jthrowable)env->NewObject(e, init, ret, JNI_TRUE);
    env->Throw(t);
    env->DeleteLocalRef(e);
}

void QuickJSWrapper::throwJSException(JNIEnv *env, JSContext *ctx)
{
    string error = QuickJSWrapper::getJSErrorStr(ctx);
    QuickJSWrapper::throwJSException(env, error.c_str());
}

// js function callback
static JSClassID js_func_callback_class_id;

static void jsFuncCallbackFinalizer(JSRuntime *rt, JSValue val)
{
    auto wrapper = reinterpret_cast<const QuickJSWrapper *>(JS_GetRuntimeOpaque(rt));
    if (wrapper)
    {
        int *callbackId = (int *)(JS_GetOpaque2(wrapper->context, val, js_func_callback_class_id));
        wrapper->removeCallFunction(*callbackId);
        delete callbackId;
    }
}

static JSClassDef js_func_callback_class = {
    "JSFuncCallback",
    .finalizer = jsFuncCallbackFinalizer,
};

static JSValue jsFnCallback(JSContext *ctx,
                            JSValueConst this_obj,
                            int argc, JSValueConst *argv,
                            int magic, JSValue *func_data)
{

    int callbackId = *((int *)JS_GetOpaque2(ctx, func_data[0], js_func_callback_class_id));
    auto wrapper = reinterpret_cast<QuickJSWrapper *>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
    JSValue value = wrapper->jsFuncCall(callbackId, this_obj, argc, argv);
    return value;
}

static void initJSFuncCallback(JSContext *ctx)
{
    // JSFuncCallback class
    JS_NewClassID(&js_func_callback_class_id);
    JS_NewClass(JS_GetRuntime(ctx), js_func_callback_class_id, &js_func_callback_class);
}

// js module
static char *jsModuleNormalizeFunc(JSContext *ctx, const char *module_base_name,
                                   const char *module_name, void *opaque)
{
    auto wrapper = reinterpret_cast<const QuickJSWrapper *>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
    auto env = wrapper->jniEnv;

    // module loader handle.
    jobject moduleLoader = env->CallObjectMethod(wrapper->jniThiz, env->GetMethodID(wrapper->quickjsContextClass, "getModuleLoader", "()Lcom/whl/quickjs/wrapper/ModuleLoader;"));
    if (moduleLoader == nullptr)
    {
        JS_ThrowInternalError(ctx, "Failed to load module, the ModuleLoader can not be null!");
        return nullptr;
    }
    jmethodID moduleNormalizeName = env->GetMethodID(wrapper->moduleLoaderClass, "moduleNormalizeName", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

    jstring j_module_base_name = env->NewStringUTF(module_base_name);
    jstring j_module_name = env->NewStringUTF(module_name);
    auto result = env->CallObjectMethod(moduleLoader, moduleNormalizeName, j_module_base_name, j_module_name);
    if (result == nullptr)
    {
        QuickJSWrapper::throwJSException(env, "Failed to load module, cause moduleName was null!");
        return nullptr;
    }

    env->DeleteLocalRef(j_module_base_name);
    env->DeleteLocalRef(j_module_name);
    env->DeleteLocalRef(moduleLoader);

    // todo 这里作为返回值，没有调用 ReleaseStringUTFChars，quickjs.c 里面会对 char* 进行释放，需�?check 下是否有释放�?
    auto ret = (char *)env->GetStringUTFChars((jstring)result, nullptr);
    env->DeleteLocalRef(result);
    return ret;
}

static JSModuleDef *
jsModuleLoaderFunc(JSContext *ctx, const char *module_name, void *opaque)
{
    auto wrapper = reinterpret_cast<const QuickJSWrapper *>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
    auto env = wrapper->jniEnv;
    auto arg = env->NewStringUTF(module_name);

    // module loader handle.
    jobject moduleLoader = env->CallObjectMethod(wrapper->jniThiz, env->GetMethodID(wrapper->quickjsContextClass, "getModuleLoader", "()Lcom/whl/quickjs/wrapper/ModuleLoader;"));
    if (moduleLoader == nullptr)
    {
        JS_ThrowInternalError(ctx, "Failed to load module, the ModuleLoader can not be null!");
        return (JSModuleDef *)JS_VALUE_GET_PTR(JS_EXCEPTION);
    }

    bool isBytecodeModule = env->CallBooleanMethod(moduleLoader, env->GetMethodID(wrapper->moduleLoaderClass, "isBytecodeMode", "()Z"));

    void *m;
    if (isBytecodeModule)
    {
        jmethodID getModuleBytecode = env->GetMethodID(wrapper->moduleLoaderClass, "getModuleBytecode", "(Ljava/lang/String;)[B");

        auto bytecode = (jbyteArray)(env->CallObjectMethod(moduleLoader, getModuleBytecode, arg));
        if (bytecode == nullptr)
        {
            QuickJSWrapper::throwJSException(env, "Failed to load module, cause bytecode was null!");
            return nullptr;
        }

        const auto buffer = env->GetByteArrayElements(bytecode, nullptr);
        const auto bufferLength = env->GetArrayLength(bytecode);
        const auto flags = JS_READ_OBJ_BYTECODE | JS_READ_OBJ_REFERENCE;
        auto obj = JS_ReadObject(ctx, reinterpret_cast<const uint8_t *>(buffer), bufferLength, flags);
        env->ReleaseByteArrayElements(bytecode, buffer, JNI_ABORT);

        if (JS_IsException(obj))
        {
            QuickJSWrapper::throwJSException(env, ctx);
            return (JSModuleDef *)JS_VALUE_GET_PTR(JS_EXCEPTION);
        }

        if (JS_ResolveModule(ctx, obj))
        {
            QuickJSWrapper::throwJSException(env, "Failed to resolve JS module");
            return nullptr;
        }

        m = JS_VALUE_GET_PTR(obj);
        JS_FreeValue(ctx, obj);
        env->DeleteLocalRef(bytecode);
    }
    else
    {
        jmethodID getModuleStringCode = env->GetMethodID(wrapper->moduleLoaderClass, "getModuleStringCode", "(Ljava/lang/String;)Ljava/lang/String;");

        auto result = env->CallObjectMethod(moduleLoader, getModuleStringCode, arg);
        if (result == nullptr)
        {
            QuickJSWrapper::throwJSException(env, "Failed to load module, cause string code was null!");
            return nullptr;
        }

        const auto script = env->GetStringUTFChars((jstring)(result), JNI_FALSE);
        int scriptLen = env->GetStringUTFLength((jstring)result);
        JSValue func_val = JS_Eval(ctx, script, scriptLen, module_name,
                                   JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
        env->ReleaseStringUTFChars((jstring)(result), script);
        if (JS_IsException(func_val))
        {
            JS_FreeValue(ctx, func_val);
            QuickJSWrapper::throwJSException(env, ctx);
            return (JSModuleDef *)JS_VALUE_GET_PTR(JS_EXCEPTION);
        }

        m = JS_VALUE_GET_PTR(func_val);
        JS_FreeValue(ctx, func_val);
    }

    env->DeleteLocalRef(arg);
    env->DeleteLocalRef(moduleLoader);
    return (JSModuleDef *)m;
}

static bool throwIfUnhandledRejections(QuickJSWrapper *wrapper, JSContext *ctx)
{
    string error;
    auto &q = wrapper->unhandledRejections;
    while (!q.empty())
    {
        RejectionEntry e = q.front();
        q.pop();
        error += QuickJSWrapper::getJSErrorStr(ctx, e.reason);
        error += "\n";
        JS_FreeValue(ctx, e.promise);
        JS_FreeValue(ctx, e.reason);
    }

    bool is_error = !error.empty();
    if (is_error)
    {
        error = "UnhandledPromiseRejectionException: " + error;
        QuickJSWrapper::throwJSException(wrapper->jniEnv, error.c_str());
    }
    return is_error;
}

/**
 * Runs all pending jobs. When a job throws, the exception is captured and cleared,
 * and the loop KEEPS RUNNING so that promise rejection handlers attached by JS
 * (e.g. .then(null, onRejected) fallbacks) still get a chance to execute and
 * report the error through the JS error channel with full context.
 *
 * The FIRST job error is rethrown at the end; if no job error occurred, remaining
 * unhandled rejections are thrown.
 */
static bool executePendingJobLoop(JNIEnv *env, JSRuntime *rt, JSContext *ctx)
{
    if (env->ExceptionCheck())
    {
        return false;
    }

    JSContext *ctx1;
    bool success = true;
    string jobError;
    int err;
    /* execute the pending jobs */
    for (;;)
    {
        err = JS_ExecutePendingJob(rt, &ctx1);
        if (err == 0)
        {
            break; // no more jobs
        }
        if (err < 0)
        {
            // A job threw. Capture the first error, clear the exception, and
            // keep processing the remaining jobs.
            if (jobError.empty())
            {
                jobError = QuickJSWrapper::getJSErrorStr(ctx);
            }
            else
            {
                QuickJSWrapper::getJSErrorStr(ctx); // clear, keep the first error
            }
            success = false;
            continue;
        }
        /* err > 0: one job executed */
    }

    if (success && throwIfUnhandledRejections(reinterpret_cast<QuickJSWrapper *>(JS_GetRuntimeOpaque(rt)), ctx))
    {
        success = false;
    }

    if (!jobError.empty())
    {
        QuickJSWrapper::throwJSException(env, jobError.c_str());
        success = false;
    }

    return success;
}

static void promiseRejectionTracker(JSContext *ctx, JSValueConst promise,
                                    JSValueConst reason, BOOL is_handled, void *opaque)
{
    auto unhandledRejections = static_cast<queue<RejectionEntry> *>(opaque);
    if (!is_handled)
    {
        unhandledRejections->push(RejectionEntry{JS_DupValue(ctx, promise), JS_DupValue(ctx, reason)});
        return;
    }

    // handled: remove ONLY the entry that belongs to THIS promise (identity match).
    // Previously the queue front was popped unconditionally, which mis-pairs when
    // several promises reject (an unrelated entry could be dropped and a still
    // unhandled rejection could be lost or falsely reported).
    queue<RejectionEntry> kept;
    while (!unhandledRejections->empty())
    {
        RejectionEntry e = unhandledRejections->front();
        unhandledRejections->pop();
        if (JS_StrictEq(ctx, e.promise, promise))
        {
            JS_FreeValue(ctx, e.promise);
            JS_FreeValue(ctx, e.reason);
        }
        else
        {
            kept.push(e);
        }
    }
    while (!kept.empty())
    {
        unhandledRejections->push(kept.front());
        kept.pop();
    }
}

QuickJSWrapper::QuickJSWrapper(JNIEnv *env, jobject thiz, JSRuntime *rt)
{
    jniEnv = env;
    runtime = rt;
    jniThiz = jniEnv->NewGlobalRef(thiz);

    // init ES6Module
    JS_SetModuleLoaderFunc(runtime, jsModuleNormalizeFunc, jsModuleLoaderFunc, nullptr);

    JS_SetHostPromiseRejectionTracker(runtime, promiseRejectionTracker, &unhandledRejections);

        context = JS_NewContext(runtime);

        JS_SetRuntimeOpaque(runtime, this);
        initJSFuncCallback(context);
        loadExtendLibraries(context);

        // Inject base64 helpers
        {
            JSValue g = JS_GetGlobalObject(context);
            JS_SetPropertyStr(context, g, "uint8ArrayToBase64",
                JS_NewCFunction(context, js_uint8ArrayToBase64, "uint8ArrayToBase64", 1));
            JS_SetPropertyStr(context, g, "base64ToUint8Array",
                JS_NewCFunction(context, js_base64ToUint8Array, "base64ToUint8Array", 1));
            JS_FreeValue(context, g);
        }

    const char *getOwnPropertyNames = "Object.getOwnPropertyNames";
    ownPropertyNames = JS_Eval(context, getOwnPropertyNames, strlen(getOwnPropertyNames), getOwnPropertyNames, JS_EVAL_TYPE_GLOBAL);

    objectClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("java/lang/Object")));
    booleanClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("java/lang/Boolean")));
    integerClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("java/lang/Integer")));
    longClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("java/lang/Long")));
    doubleClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("java/lang/Double")));
    stringClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("java/lang/String")));
    jsObjectClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("com/whl/quickjs/wrapper/JSObject")));
    jsArrayClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("com/whl/quickjs/wrapper/JSArray")));
    jsFunctionClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("com/whl/quickjs/wrapper/JSFunction")));
    jsCallFunctionClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("com/whl/quickjs/wrapper/JSCallFunction")));
    quickjsContextClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("com/whl/quickjs/wrapper/QuickJSContext")));
    moduleLoaderClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("com/whl/quickjs/wrapper/ModuleLoader")));
    creatorClass = (jclass)(jniEnv->NewGlobalRef(jniEnv->FindClass("com/whl/quickjs/wrapper/JSObjectCreator")));
    byteArrayClass = (jclass)jniEnv->NewGlobalRef(env->FindClass("[B"));

    booleanValueOf = jniEnv->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    integerValueOf = jniEnv->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    longValueOf = jniEnv->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    doubleValueOf = jniEnv->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");

    booleanGetValue = jniEnv->GetMethodID(booleanClass, "booleanValue", "()Z");
    integerGetValue = jniEnv->GetMethodID(integerClass, "intValue", "()I");
    longGetValue = jniEnv->GetMethodID(longClass, "longValue", "()J");
    doubleGetValue = jniEnv->GetMethodID(doubleClass, "doubleValue", "()D");
    jsObjectGetValue = jniEnv->GetMethodID(jsObjectClass, "getPointer", "()J");

    callFunctionBackM = jniEnv->GetMethodID(quickjsContextClass, "callFunctionBack", "(I[Ljava/lang/Object;)Ljava/lang/Object;");
    removeCallFunctionM = jniEnv->GetMethodID(quickjsContextClass, "removeCallFunction", "(I)V");
    callFunctionHashCodeM = jniEnv->GetMethodID(objectClass, "hashCode", "()I");
    creatorM = jniEnv->GetMethodID(quickjsContextClass, "getCreator", "()Lcom/whl/quickjs/wrapper/JSObjectCreator;");
    newObjectM = jniEnv->GetMethodID(creatorClass, "newObject",
                                     "(Lcom/whl/quickjs/wrapper/QuickJSContext;J)Lcom/whl/quickjs/wrapper/JSObject;");
    newArrayM = jniEnv->GetMethodID(creatorClass, "newArray",
                                    "(Lcom/whl/quickjs/wrapper/QuickJSContext;J)Lcom/whl/quickjs/wrapper/JSArray;");
    newFunctionM = jniEnv->GetMethodID(creatorClass, "newFunction",
                                       "(Lcom/whl/quickjs/wrapper/QuickJSContext;JJI)Lcom/whl/quickjs/wrapper/JSFunction;");
}

QuickJSWrapper::~QuickJSWrapper()
{
    JS_FreeValue(context, ownPropertyNames);
    JS_FreeContext(context);
    JS_FreeRuntime(runtime);

    jniEnv->DeleteGlobalRef(jniThiz);
    jniEnv->DeleteGlobalRef(objectClass);
    jniEnv->DeleteGlobalRef(doubleClass);
    jniEnv->DeleteGlobalRef(integerClass);
    jniEnv->DeleteGlobalRef(longClass);
    jniEnv->DeleteGlobalRef(booleanClass);
    jniEnv->DeleteGlobalRef(stringClass);
    jniEnv->DeleteGlobalRef(jsObjectClass);
    jniEnv->DeleteGlobalRef(jsArrayClass);
    jniEnv->DeleteGlobalRef(jsFunctionClass);
    jniEnv->DeleteGlobalRef(jsCallFunctionClass);
    jniEnv->DeleteGlobalRef(moduleLoaderClass);
    jniEnv->DeleteGlobalRef(quickjsContextClass);
    jniEnv->DeleteGlobalRef(creatorClass);
    jniEnv->DeleteGlobalRef(byteArrayClass);
}

jobject QuickJSWrapper::toJavaObject(JNIEnv *env, jobject thiz, JSValueConst this_obj, JSValueConst value) const
{
    jobject result;
    switch (JS_VALUE_GET_NORM_TAG(value))
    {
    case JS_TAG_EXCEPTION:
    {
        result = nullptr;
        break;
    }

    case JS_TAG_STRING:
    {
        result = toJavaString(env, value);
        break;
    }

    case JS_TAG_BOOL:
    {
        jvalue v;
        v.z = static_cast<jboolean>(JS_VALUE_GET_BOOL(value));
        result = env->CallStaticObjectMethodA(booleanClass, booleanValueOf, &v);
        break;
    }

    case JS_TAG_INT:
    {
        jvalue v;
        v.j = static_cast<jint>(JS_VALUE_GET_INT(value));
        result = env->CallStaticObjectMethodA(integerClass, integerValueOf, &v);
        break;
    }

    case JS_TAG_BIG_INT:
    {
        int64_t e;
        JS_ToBigInt64(context, &e, value);
        jvalue v;
        v.j = e;
        result = env->CallStaticObjectMethodA(longClass, longValueOf, &v);
        break;
    }

    case JS_TAG_FLOAT64:
    {
        jvalue v;
        double d = JS_VALUE_GET_FLOAT64(value);
        bool isInteger = floor(d) == d;
        if (isInteger)
        {
            v.j = static_cast<jlong>(d);
            result = env->CallStaticObjectMethodA(longClass, longValueOf, &v);
        }
        else
        {
            v.d = static_cast<jdouble>(d);
            result = env->CallStaticObjectMethodA(doubleClass, doubleValueOf, &v);
        }
        break;
    }

    case JS_TAG_OBJECT:
    {
        auto value_ptr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR(value));
        jobject creatorObj = env->CallObjectMethod(thiz, creatorM);
        if (JS_IsFunction(context, value))
        {
            auto obj_ptr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR(this_obj));
            result = env->CallObjectMethod(creatorObj, newFunctionM, thiz, value_ptr, obj_ptr, JS_VALUE_GET_TAG(this_obj));
        }
        else if (JS_IsArray(context, value))
        {
            result = env->CallObjectMethod(creatorObj, newArrayM, thiz, value_ptr);
        }
        else if (JS_IsArrayBuffer(value))
        {
            size_t byteLength = 0;
            uint8_t *buffer = JS_GetArrayBuffer(context, &byteLength, value);
            jbyteArray byteArray = env->NewByteArray(byteLength);
            void *elementsPtr = env->GetPrimitiveArrayCritical(byteArray, nullptr);
            jbyte *elements = reinterpret_cast<jbyte *>(elementsPtr);
            memcpy(elements, buffer, byteLength);
            result = byteArray;
            JS_FreeValue(context, value);
            env->ReleasePrimitiveArrayCritical(byteArray, elements, 0);
        }
        else
        {
            result = env->CallObjectMethod(creatorObj, newObjectM, thiz, value_ptr);
        }
        env->DeleteLocalRef(creatorObj);
        break;
    }

    default:
        result = nullptr;
        break;
    }

    return result;
}

jobject QuickJSWrapper::evaluate(JNIEnv *env, jobject thiz, jstring script, jstring file_name)
{
    const char *c_script = env->GetStringUTFChars(script, JNI_FALSE);
    const char *c_file_name = env->GetStringUTFChars(file_name, JNI_FALSE);

    JSValue result = JS_Eval(context, c_script, strlen(c_script), c_file_name, JS_EVAL_TYPE_GLOBAL);
    env->ReleaseStringUTFChars(script, c_script);
    env->ReleaseStringUTFChars(file_name, c_file_name);
    if (JS_IsException(result))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    if (!executePendingJobLoop(env, runtime, context))
    {
        JS_FreeValue(context, result);
        return nullptr;
    }

    return toJavaObject(env, thiz, JS_UNDEFINED, result);
}

jobject QuickJSWrapper::getGlobalObject(JNIEnv *env, jobject thiz) const
{
    JSValue value = JS_GetGlobalObject(context);

    auto value_ptr = reinterpret_cast<jlong>(JS_VALUE_GET_PTR(value));
    jobject result = env->CallObjectMethod(env->CallObjectMethod(thiz, creatorM), newObjectM, thiz, value_ptr);

    JS_FreeValue(context, value);
    return result;
}

jobject QuickJSWrapper::getProperty(JNIEnv *env, jobject thiz, jlong value, jstring name)
{
    JSValue jsObject = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));

    const char *propsName = env->GetStringUTFChars(name, JNI_FALSE);
    JSValue propsValue = JS_GetPropertyStr(context, jsObject, propsName);
    env->ReleaseStringUTFChars(name, propsName);
    if (JS_IsException(propsValue))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    return toJavaObject(env, thiz, jsObject, propsValue);
}

jobject QuickJSWrapper::call(JNIEnv *env, jobject thiz, jlong func, jlong this_obj,
                             jint this_obj_tag, jobjectArray args)
{
    int argc = env->GetArrayLength(args);
    vector<JSValue> arguments;
    vector<JSValue> freeArguments;
    for (int numArgs = 0; numArgs < argc && !env->ExceptionCheck(); numArgs++)
    {
        jobject arg = env->GetObjectArrayElement(args, numArgs);
        auto jsArg = toJSValue(env, thiz, arg);
        if (JS_IsException(jsArg))
        {
            return nullptr;
        }

        // 基础类型(例如 string )�?Java callback 类型需要使用完 free.
        if (env->IsInstanceOf(arg, stringClass) || env->IsInstanceOf(arg, doubleClass) ||
            env->IsInstanceOf(arg, integerClass) || env->IsInstanceOf(arg, longClass) ||
            env->IsInstanceOf(arg, booleanClass) || env->IsInstanceOf(arg, jsCallFunctionClass) || env->IsInstanceOf(arg, byteArrayClass))
        {
            freeArguments.push_back(jsArg);
        }

        env->DeleteLocalRef(arg);

        arguments.push_back(jsArg);
    }

    JSValue jsObj = JS_MKPTR(this_obj_tag, reinterpret_cast<void *>(this_obj));
    JSValue jsFunc = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(func));

    JSValue ret = JS_Call(context, jsFunc, jsObj, arguments.size(), arguments.data());
    if (JS_IsException(ret))
    {
        JS_FreeValue(context, ret);
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    for (JSValue argument : freeArguments)
    {
        JS_FreeValue(context, argument);
    }

    // release vector by swap.
    vector<JSValue>().swap(arguments);
    vector<JSValue>().swap(freeArguments);

    if (!executePendingJobLoop(env, runtime, context))
    {
        JS_FreeValue(context, ret);
        return nullptr;
    }

    return toJavaObject(env, thiz, jsObj, ret);
}

jstring QuickJSWrapper::jsonStringify(JNIEnv *env, jlong value) const
{
    JSValue obj = JS_JSONStringify(context, JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value)), JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(obj))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    return toJavaString(env, obj);
}

jint QuickJSWrapper::length(JNIEnv *env, jlong value) const
{
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));

    JSValue length = JS_GetPropertyStr(context, jsObj, "length");
    if (JS_IsException(length))
    {
        QuickJSWrapper::throwJSException(env, context);
        return -1;
    }

    JS_FreeValue(context, length);

    return JS_VALUE_GET_INT(length);
}

jobject QuickJSWrapper::get(JNIEnv *env, jobject thiz, jlong value, jint index)
{
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JSValue child = JS_GetPropertyUint32(context, jsObj, index);

    return toJavaObject(env, thiz, jsObj, child);
}

void QuickJSWrapper::set(JNIEnv *env, jobject thiz, jlong this_obj, jobject value, jint index)
{
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(this_obj));
    JSValue child = toJSValue(env, thiz, value);
    if (JS_IsString(child))
    {
        // JSString 类型不需�?JS_DupValue
        JS_SetPropertyUint32(context, jsObj, index, child);
    }
    else
    {
        JS_SetPropertyUint32(context, jsObj, index, JS_DupValue(context, child));
    }
}

void QuickJSWrapper::setProperty(JNIEnv *env, jobject thiz, jlong this_obj, jstring name, jobject value) const
{
    const char *propName = env->GetStringUTFChars(name, JNI_FALSE);
    JSValue propValue = toJSValue(env, thiz, value);
    if (env->IsInstanceOf(value, jsObjectClass))
    {
        // 这里需要手动增加引用计数，不然 QuickJS 垃圾回收会报 assertion "p->ref_count > 0" 的错误�?
        JS_DupValue(context, propValue);
    }
    else if (env->IsInstanceOf(value, jsCallFunctionClass))
    {
        // 通过 JS_NewCFunctionData 创建�?fn 对象�?name 属性值被定义�?Empty 了，
        // 这里需要额外定义下，不�?js 层拿到的 fn.name 的值为�?
        JSAtom name_atom = JS_NewAtom(context, propName);
        JSAtom name_atom_key = JS_NewAtom(context, "name");
        JS_DefinePropertyValue(context, propValue, name_atom_key,
                               JS_AtomToString(context, name_atom), JS_PROP_CONFIGURABLE);
        JS_FreeAtom(context, name_atom);
        JS_FreeAtom(context, name_atom_key);
    }

    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(this_obj));
    JS_SetPropertyStr(context, jsObj, propName, propValue);

    env->ReleaseStringUTFChars(name, propName);
}

JSValue QuickJSWrapper::jsFuncCall(int callback_id, JSValueConst this_val, int argc, JSValueConst *argv)
{
    if (jniEnv->ExceptionCheck())
    {
        return JS_EXCEPTION;
    }

    jobjectArray javaArgs = jniEnv->NewObjectArray((jsize)argc, objectClass, nullptr);

    for (int i = 0; i < argc; i++)
    {
        JSValue v = JS_DupValue(context, argv[i]);
        auto java_arg = toJavaObject(jniEnv, jniThiz, this_val, v);
        jniEnv->SetObjectArrayElement(javaArgs, (jsize)i, java_arg);
        jniEnv->DeleteLocalRef(java_arg);
    }

    auto result = jniEnv->CallObjectMethod(jniThiz, callFunctionBackM, callback_id, javaArgs);

    jniEnv->DeleteLocalRef(javaArgs);

    JSValue jsValue = toJSValue(jniEnv, jniThiz, result);

    jniEnv->DeleteLocalRef(result);
    return jsValue;
}

void QuickJSWrapper::removeCallFunction(int callback_id) const
{
    if (jniEnv->ExceptionCheck())
    {
        return;
    }

    jniEnv->CallVoidMethod(jniThiz, removeCallFunctionM, callback_id);
}

JSValue QuickJSWrapper::toJSValue(JNIEnv *env, jobject thiz, jobject value) const
{
    if (value == nullptr)
    {
        return JS_NULL;
    }

    JSValue result;
    if (env->IsInstanceOf(value, stringClass))
    {
        const auto s = env->GetStringUTFChars((jstring)(value), JNI_FALSE);
        result = JS_NewString(context, s);
        env->ReleaseStringUTFChars((jstring)(value), s);
    }
    else if (env->IsInstanceOf(value, doubleClass))
    {
        result = JS_NewFloat64(context, env->CallDoubleMethod(value, doubleGetValue));
    }
    else if (env->IsInstanceOf(value, integerClass))
    {
        result = JS_NewInt32(context, env->CallIntMethod(value, integerGetValue));
    }
    else if (env->IsInstanceOf(value, longClass))
    {
        jlong l_val = env->CallLongMethod(value, longGetValue);
        if (l_val > MAX_SAFE_INTEGER || l_val < -MAX_SAFE_INTEGER)
        {
            result = JS_NewBigInt64(context, l_val);
        }
        else
        {
            result = JS_NewInt64(context, l_val);
        }
    }
    else if (env->IsInstanceOf(value, booleanClass))
    {
        result = JS_NewBool(context, env->CallBooleanMethod(value, booleanGetValue));
    }
    else if (env->IsInstanceOf(value, byteArrayClass))
    {
        jbyteArray bytes = static_cast<jbyteArray>(value);
        jbyte *byteData = env->GetByteArrayElements(bytes, nullptr);
        jsize length = env->GetArrayLength(bytes);
        result = JS_NewArrayBufferCopy(context, reinterpret_cast<uint8_t *>(byteData), length);
        env->ReleaseByteArrayElements(bytes, byteData, JNI_ABORT);
    }
    else if (env->IsInstanceOf(value, jsObjectClass))
    {
        result = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(env->CallLongMethod(value, jsObjectGetValue)));
    }
    else if (env->IsInstanceOf(value, jsCallFunctionClass))
    {
        // 这里�?obj 是用来获�?JSFuncCallback 对象�?
        JSValue obj = JS_NewObjectClass(context, js_func_callback_class_id);
        result = JS_NewCFunctionData(context, jsFnCallback, 1, 0, 1, &obj);
        // JS_NewCFunctionData �?dupValue obj，这里需要对 obj 计数减一，保持计数平�?
        JS_FreeValue(context, obj);

        int *callbackId = new int(jniEnv->CallIntMethod(value, callFunctionHashCodeM));
        JS_SetOpaque(obj, callbackId);
    }
    else
    {
        auto classType = env->GetObjectClass(value);
        const auto typeName = getJavaName(env, classType);
        env->DeleteLocalRef(classType);
        // Throw an exception for unsupported argument type.
        throwJavaException(env, "java/lang/IllegalArgumentException", "Unsupported Java type %s",
                           typeName.c_str());
        result = JS_EXCEPTION;
    }

    return result;
}

void QuickJSWrapper::freeValue(jlong value) const
{
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JS_FreeValue(context, jsObj);
}

void QuickJSWrapper::dupValue(jlong value) const
{
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JS_DupValue(context, jsObj);
}

/**
 * @deprecated
 * See {@link freeValue(String)}
 * @param value
 */
void QuickJSWrapper::freeDupValue(jlong value) const
{
    JSValue jsObj = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(value));
    JS_FreeValue(context, jsObj);
}

jobject QuickJSWrapper::parseJSON(JNIEnv *env, jobject thiz, jstring json)
{
    const char *c_json = env->GetStringUTFChars(json, JNI_FALSE);
    auto jsonObj = JS_ParseJSON(context, c_json, strlen(c_json), "parseJSON.js");
    if (JS_IsException(jsonObj))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    JSValue jsObj = JS_UNDEFINED;
    jobject result = toJavaObject(env, thiz, jsObj, jsonObj);
    env->ReleaseStringUTFChars(json, c_json);
    return result;
}

jbyteArray QuickJSWrapper::compile(JNIEnv *env, jstring source, jstring file_name, jboolean isModule) const
{
    const auto sourceCode = env->GetStringUTFChars(source, JNI_FALSE);
    const auto fileName = env->GetStringUTFChars(file_name, JNI_FALSE);
    auto eval_flags = JS_EVAL_FLAG_COMPILE_ONLY;
    if (isModule)
    {
        eval_flags = JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY;
    }
    auto compiled = JS_Eval(context, sourceCode, strlen(sourceCode), fileName, eval_flags);
    env->ReleaseStringUTFChars(source, sourceCode);
    env->ReleaseStringUTFChars(file_name, fileName);

    if (JS_IsException(compiled))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    size_t bufferLength = 0;
    auto buffer = JS_WriteObject(context, &bufferLength, compiled, JS_WRITE_OBJ_BYTECODE | JS_WRITE_OBJ_REFERENCE);

    auto result = buffer && bufferLength > 0 ? env->NewByteArray(bufferLength) : nullptr;
    if (result)
    {
        env->SetByteArrayRegion(result, 0, bufferLength, reinterpret_cast<const jbyte *>(buffer));
    }
    else
    {
        QuickJSWrapper::throwJSException(env, context);
    }

    JS_FreeValue(context, compiled);
    js_free(context, buffer);

    return result;
}

jobject QuickJSWrapper::execute(JNIEnv *env, jobject thiz, jbyteArray bytecode)
{
    if (bytecode == nullptr)
    {
        QuickJSWrapper::throwJSException(env, "bytecode can not be null");
        return nullptr;
    }

    const auto buffer = env->GetByteArrayElements(bytecode, nullptr);
    const auto bufferLength = env->GetArrayLength(bytecode);
    const auto flags = JS_READ_OBJ_BYTECODE | JS_READ_OBJ_REFERENCE;
    auto obj = JS_ReadObject(context, reinterpret_cast<const uint8_t *>(buffer), bufferLength, flags);
    env->ReleaseByteArrayElements(bytecode, buffer, JNI_ABORT);

    if (JS_IsException(obj))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    if (JS_ResolveModule(context, obj))
    {
        // TODO throwJsExceptionFmt(env, this, "Failed to resolve JS module");
        return nullptr;
    }

    auto val = JS_EvalFunction(context, obj);

    if (!executePendingJobLoop(env, runtime, context))
    {
        JS_FreeValue(context, val);
        return nullptr;
    }

    jobject result;
    if (!JS_IsException(val))
    {
        result = toJavaObject(env, thiz, JS_UNDEFINED, val);
    }
    else
    {
        result = nullptr;
        QuickJSWrapper::throwJSException(env, context);
    }

    return result;
}

jobject
QuickJSWrapper::evaluateModule(JNIEnv *env, jobject thiz, jstring script, jstring file_name)
{
    const char *c_script = env->GetStringUTFChars(script, JNI_FALSE);
    const char *c_file_name = env->GetStringUTFChars(file_name, JNI_FALSE);

    JSValue result = JS_Eval(context, c_script, strlen(c_script), c_file_name, JS_EVAL_TYPE_MODULE);
    env->ReleaseStringUTFChars(script, c_script);
    env->ReleaseStringUTFChars(file_name, c_file_name);
    if (JS_IsException(result))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    if (!executePendingJobLoop(env, runtime, context))
    {
        JS_FreeValue(context, result);
        return nullptr;
    }

    JSValue global = JS_GetGlobalObject(context);
    jobject jsObj = toJavaObject(env, thiz, global, result);
    JS_FreeValue(context, global);
    return jsObj;
}

jobject QuickJSWrapper::getOwnPropertyNames(JNIEnv *env, jobject thiz, jlong obj)
{
    if (JS_IsException(ownPropertyNames))
    {
        QuickJSWrapper::throwJSException(env, context);
        return nullptr;
    }

    JSValue jsObject = JS_MKPTR(JS_TAG_OBJECT, reinterpret_cast<void *>(obj));
    JSValue ret = JS_Call(context, ownPropertyNames, JS_NULL, 1, &jsObject);
    if (JS_IsException(ret))
    {
        QuickJSWrapper::throwJSException(env, context);
        JS_FreeValue(context, ret);
        return nullptr;
    }

    return toJavaObject(env, thiz, JS_UNDEFINED, ret);
}

jstring QuickJSWrapper::toJavaString(JNIEnv *env, JSValue value) const
{
    jstring result;
#ifdef IS_ANDROID
    const char *string = JS_ToCString(context, value);
    result = env->NewStringUTF(string);
    JS_FreeCString(context, string);
    // JSString 类型�?JSValue 需要手动释放掉，不然会泄漏
    JS_FreeValue(context, value);
#else
    // 这里需要注意，JVM 平台�?NewStringUTF 方法对部�?unicode 的转换有问题，会出现乱码，换了另一种方式解决�?
    const char *str;
    size_t len;
    str = JS_ToCStringLen(context, &len, value);

    jbyteArray jba = env->NewByteArray(len);
    env->SetByteArrayRegion(jba, 0, len, reinterpret_cast<const jbyte *>(str));

    result = static_cast<jstring>(env->NewObject(stringClass,
                                                 env->GetMethodID(stringClass, "<init>", "([B)V"),
                                                 jba));

    JS_FreeCString(context, str);
    env->DeleteLocalRef(jba);
    // JSString 类型�?JSValue 需要手动释放掉，不然会泄漏
    JS_FreeValue(context, value);
#endif

    return result;
}
