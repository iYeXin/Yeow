/*
 * Polyfill registry: installs every native-backed global. Called once per
 * context from yeow_create().
 */
#include "polyfill.h"

void yeow_install_polyfills(JSContext *ctx) {
    yeow_performance_install(ctx);
    yeow_text_codec_install(ctx);
}
