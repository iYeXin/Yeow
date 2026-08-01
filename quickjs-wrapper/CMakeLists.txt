set(QUICKJS_PATH "../../../native/quickjs")

file(STRINGS "${QUICKJS_PATH}/VERSION" CONFIG_VERSION)

add_definitions(-DCONFIG_VERSION=\"${CONFIG_VERSION}\")

# QuickJS engine core sources only.
# NOTE: quickjs-libc.c (std.* / os.* modules) is intentionally excluded —
# Yeow runs inside JVM and provides all I/O through Java, so those modules
# are not needed. Adding them would bloat the binary and introduce
# unnecessary platform-specific dependencies.
file(GLOB wrapper_src
        "../../../native/cpp/*.cpp"
        "../../../native/cpp/*.h"
        )

file(GLOB quickjs_src
        "${QUICKJS_PATH}/cutils.c"
        "${QUICKJS_PATH}/cutils.h"
        "${QUICKJS_PATH}/dtoa.c"
        "${QUICKJS_PATH}/dtoa.h"
        "${QUICKJS_PATH}/libregexp-opcode.h"
        "${QUICKJS_PATH}/libregexp.c"
        "${QUICKJS_PATH}/libregexp.h"
        "${QUICKJS_PATH}/libunicode-table.h"
        "${QUICKJS_PATH}/libunicode.c"
        "${QUICKJS_PATH}/libunicode.h"
        "${QUICKJS_PATH}/list.h"
        "${QUICKJS_PATH}/quickjs-atom.h"
        "${QUICKJS_PATH}/quickjs-opcode.h"
        "${QUICKJS_PATH}/quickjs.c"
        "${QUICKJS_PATH}/quickjs.h"
        )