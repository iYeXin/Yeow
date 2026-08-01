#include <jni.h>
#include <stdint.h>
#include <cstring>
#include <cstdlib>
#include "quickjs_wrapper.h"

#define TAG_NULL     0x00
#define TAG_TRUE     0x01
#define TAG_FALSE    0x02
#define TAG_INT32    0x03
#define TAG_FLOAT64  0x04
#define TAG_STRING   0x05
#define TAG_ARRAY    0x06
#define TAG_MAP      0x07
#define TAG_LONGSTR  0x15

static inline uint16_t read_u16(const uint8_t** ptr) {
    uint16_t v = (*ptr)[0] | ((*ptr)[1] << 8); *ptr += 2; return v;
}
static inline uint32_t read_u32(const uint8_t** ptr) {
    uint32_t v = (*ptr)[0] | ((*ptr)[1] << 8) | ((*ptr)[2] << 16) | ((*ptr)[3] << 24); *ptr += 4; return v;
}
static inline int32_t read_i32(const uint8_t** ptr) { return (int32_t)read_u32(ptr); }
static inline double read_f64(const uint8_t** ptr) {
    union { uint64_t i; double d; } u;
    u.i = (uint64_t)read_u32(ptr) | ((uint64_t)read_u32(ptr) << 32);
    return u.d;
}

static JSValue read_value(JSContext* ctx, const uint8_t** ptr, const uint8_t* end) {
    if (*ptr >= end) return JS_NULL;
    uint8_t tag = *(*ptr)++;

    switch (tag) {
        case TAG_NULL:   return JS_NULL;
        case TAG_TRUE:   return JS_TRUE;
        case TAG_FALSE:  return JS_FALSE;
        case TAG_INT32:  return JS_NewInt32(ctx, read_i32(ptr));
        case TAG_FLOAT64:return JS_NewFloat64(ctx, read_f64(ptr));

        case TAG_STRING: {
            uint16_t len = read_u16(ptr);
            JSValue s = JS_NewStringLen(ctx, (const char*)*ptr, len);
            *ptr += len;
            return s;
        }
        case TAG_LONGSTR: {
            uint32_t len = read_u32(ptr);
            JSValue s = JS_NewStringLen(ctx, (const char*)*ptr, len);
            *ptr += len;
            return s;
        }

        case TAG_ARRAY: {
            uint16_t count = read_u16(ptr);
            JSValue arr = JS_NewArray(ctx);
            for (int i = 0; i < (int)count; i++) {
                JSValue elem = read_value(ctx, ptr, end);
                JS_SetPropertyUint32(ctx, arr, (uint32_t)i, elem);
            }
            return arr;
        }

        case TAG_MAP: {
            uint16_t count = read_u16(ptr);
            JSValue obj = JS_NewObject(ctx);
            for (int i = 0; i < (int)count; i++) {
                uint8_t kt = *(*ptr)++;
                uint32_t key_len = (kt == TAG_LONGSTR) ? read_u32(ptr) : read_u16(ptr);

                char stack_buf[128];
                char* k = (key_len < sizeof(stack_buf)) ? stack_buf : (char*)malloc(key_len + 1);
                memcpy(k, *ptr, key_len);
                k[key_len] = '\0';
                *ptr += key_len;

                JSValue val = read_value(ctx, ptr, end);
                JS_SetPropertyStr(ctx, obj, k, val);
                if (k != stack_buf) free(k);
            }
            return obj;
        }

        default:
            return JS_UNDEFINED;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_whl_quickjs_wrapper_QuickJSContext_parseBuffer(
    JNIEnv* env, jobject thiz, jlong context_ptr, jobject buffer) {

    auto wrapper = reinterpret_cast<QuickJSWrapper*>(context_ptr);
    JSContext* ctx = wrapper->context;

    uint8_t* data = (uint8_t*)env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!data || capacity < 5) return nullptr;

    uint32_t dataSize = ((uint32_t)data[0]) | ((uint32_t)data[1] << 8)
                      | ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24);
    if (dataSize > (uint32_t)(capacity - 4)) return nullptr;

    const uint8_t* ptr = data + 4;
    const uint8_t* end = ptr + dataSize;

    JSValue val = read_value(ctx, &ptr, end);
    return wrapper->toJavaObject(env, thiz, JS_UNDEFINED, val);
}
