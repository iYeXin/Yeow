/*
 * performance.now() / performance.timeOrigin.
 *
 * `now()` returns milliseconds as a double, measured against a monotonic clock
 * with sub-millisecond resolution (QueryPerformanceCounter on Windows,
 * clock_gettime(CLOCK_MONOTONIC) elsewhere). The zero point is captured when
 * the context installs the polyfill and is carried by the `now` function's
 * func_data, so it is per-context: a context created later still starts at ~0.
 */
#include "polyfill.h"

#include <stdint.h>
#include <time.h>

#if defined(_WIN32)
#include <windows.h>
#endif

static double monotonic_ms(void) {
#if defined(_WIN32)
    LARGE_INTEGER freq;
    LARGE_INTEGER counter;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&counter);
    return (double)counter.QuadPart * 1000.0 / (double)freq.QuadPart;
#elif defined(CLOCK_MONOTONIC)
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1000000.0;
#else
    return (double)clock() * 1000.0 / (double)CLOCKS_PER_SEC;
#endif
}

/* Epoch milliseconds — used for performance.timeOrigin. */
static double wall_ms(void) {
#if defined(_WIN32)
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | (uint64_t)ft.dwLowDateTime;
    /* FILETIME is 100ns ticks since 1601-01-01; convert to Unix epoch ms. */
    return (double)t / 10000.0 - 11644473600000.0;
#elif defined(CLOCK_REALTIME)
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1000000.0;
#else
    return (double)time(NULL) * 1000.0;
#endif
}

static JSValue js_performance_now(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                                  int magic, JSValue *func_data) {
    (void)this_val;
    (void)argc;
    (void)argv;
    (void)magic;
    double origin = JS_VALUE_GET_FLOAT64(func_data[0]);
    return JS_NewFloat64(ctx, monotonic_ms() - origin);
}

void yeow_performance_install(JSContext *ctx) {
    double origin = monotonic_ms();
    double time_origin = wall_ms();

    JSValue global = JS_GetGlobalObject(ctx);
    JSValue performance = JS_NewObject(ctx);

    JSValue origin_val = JS_NewFloat64(ctx, origin);
    JSValue now_fn = JS_NewCFunctionData(ctx, js_performance_now, 0, 0, 1, &origin_val);
    JS_FreeValue(ctx, origin_val);

    JS_SetPropertyStr(ctx, performance, "now", now_fn);
    JS_SetPropertyStr(ctx, performance, "timeOrigin", JS_NewFloat64(ctx, time_origin));

    JS_SetPropertyStr(ctx, global, "performance", performance);
    JS_FreeValue(ctx, global);
}
