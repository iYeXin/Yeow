/*
 * Yeow binary transport codec (JS side).
 *
 * A small, allocation-free codec over the per-context resident buffer that both
 * JS (via these C functions) and Java (via the same ByteBuffer) read/write.
 *
 * Layout (little-endian, no alignment, no hashing, raw UTF-8 keys):
 *
 *   header: u32 magic | u16 version | u8 mode | u8 reserved | u32 bodyLen
 *   mode 0 (binary): u16 channelLen | channel | value
 *   mode 1 (json)  : varint len | utf8
 *   mode 2 (null)  : -
 *
 *   value := tag [payload]
 *     T_NULL/T_FALSE/T_TRUE         (no payload)
 *     T_I32   4B | T_I64 8B | T_F64 8B
 *     T_STR / T_BYTES : varint len | bytes
 *     T_OBJ  (T_KEY varint len | key bytes | value)* T_OBJ_END
 *     T_ARR  value*       T_ARR_END
 *
 * Objects/arrays use explicit start/end markers (no counts), so the writer is a
 * single forward pass and containers cost 2 bytes instead of 5. Object entries
 * carry an explicit T_KEY tag: a bare varint key length would be ambiguous with
 * the T_OBJ_END marker whenever the length's low 7 bits equal 9 (e.g. a 9-byte
 * key such as "blockType"). Lengths are
 * LEB128 varints. Encode returns false (caller falls back to JSON) on overflow
 * or an unsupported value; the peer never reads a partial buffer because the
 * fallback decision happens before the JNI call.
 */
#ifndef YEOW_BINARY_H
#define YEOW_BINARY_H

#include "quickjs.h"

#define YEOB_MAGIC 0x59454F42u
#define YEOB_VERSION 2u

#define YEOB_MODE_BIN 0u
#define YEOB_MODE_JSON 1u
#define YEOB_MODE_NULL 2u

#define YEOB_T_NULL 0u
#define YEOB_T_FALSE 1u
#define YEOB_T_TRUE 2u
#define YEOB_T_I32 3u
#define YEOB_T_I64 4u
#define YEOB_T_F64 5u
#define YEOB_T_STR 6u
#define YEOB_T_BYTES 7u
#define YEOB_T_OBJ 8u
#define YEOB_T_OBJ_END 9u
#define YEOB_T_ARR 10u
#define YEOB_T_ARR_END 11u
#define YEOB_T_KEY 12u

/* Installs __yeowWrite(channel, obj) -> boolean and __yeowRead() -> any. */
void yeow_binary_install(JSContext *ctx);

#endif /* YEOW_BINARY_H */
