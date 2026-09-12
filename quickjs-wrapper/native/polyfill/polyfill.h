/*
 * Yeow native polyfills.
 *
 * C-level globals injected into every QuickJS context at creation time. Use
 * this for APIs that need native timing/platform access and therefore cannot be
 * implemented in the JS bootstrap (init.js / polyfill.js). QuickJS itself is
 * never modified: polyfills are plain `JS_NewCFunction` bindings installed on
 * the context's global object.
 *
 * To add a polyfill:
 *   1. add an installer `void yeow_<name>_install(JSContext *ctx);`
 *   2. declare it here and call it from yeow_install_polyfills()
 *   3. add the .c file to build.zig's polyfill_sources
 */
#ifndef YEOW_POLYFILL_H
#define YEOW_POLYFILL_H

#include "quickjs.h"

/* Installs all native polyfills on the global object of `ctx`. */
void yeow_install_polyfills(JSContext *ctx);

/* performance.now() / performance.timeOrigin (high-resolution time). */
void yeow_performance_install(JSContext *ctx);

/* TextEncoder / TextDecoder (utf-8). */
void yeow_text_codec_install(JSContext *ctx);

#endif /* YEOW_POLYFILL_H */
