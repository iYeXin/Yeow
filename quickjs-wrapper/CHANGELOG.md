# Change Log

## 3.8.0 *(2026-08-01)*

### Breaking Changes
- **Engine upgrade: QuickJS 2026-06-04** — submodule now points to [iyexin/quickjs](https://github.com/iyexin/quickjs) (upstream [bellard/quickjs](https://github.com/bellard/quickjs) fork) at the 2026-06-04 snapshot. Highlights:
  - custom malloc for small blocks (11% faster on bench-v8)
  - micro optimizations (30% faster on bench-v8)
  - resizable array buffers / `ArrayBuffer.prototype.transfer`
  - `Iterator` object and methods
  - set methods, `Atomics.pause`, `Map`/`WeakMap` upsert
  - `Math.sumPrecise()`, regexp duplicate named groups
  - base64/hex encodings for `Uint8Array`
  - misc bug fixes

### Bug Fixes
- None — wrapper code is API-compatible with the new engine (verified: all C sources and wrapper C++ compile & link cleanly).

---

## 3.7.0 *(2026-08-01)*

### Features
- **Multi-server native loading** — `QuickJSNativeLoader` now copies a fresh **per-process unique file** into `%TEMP%/yeow-quickjs/` when the canonical extracted library is locked by another JVM (Windows loads DLLs exclusively). Multiple server processes can now run the Yeow runtime side by side on one machine; the same fallback applies to companion libs (`libwinpthread-1.dll` etc.).

### Bug Fixes
- **Promise rejection tracking mis-pairing** — the host rejection tracker now pairs the "handled" transition with its promise by **identity** (`JS_IsStrictEqual`) instead of unconditionally popping the queue front. Previously, when several promises rejected, the wrong entry could be dropped — losing a genuinely unhandled rejection or reporting a handled one.
- **Job errors killed remaining microtasks** — `executePendingJobLoop` no longer aborts on the first job error: it captures the first error, clears the exception and keeps executing the remaining pending jobs so JS-side rejection handlers (e.g. `promise.then(null, onRejected)` fallbacks in the embedding code) still run and can report errors with their full context. The first job error is rethrown at the end; otherwise remaining unhandled rejections are thrown.

---

## 3.6.15 *(2026-07-29)*

### Breaking Changes
- Rename base64 globals to camelCase: `uint8ArrayToBase64`, `base64ToUint8Array`

### Bug Fixes
- Fix publish workflow writing native libs to wrong directory (repo root vs `wrapper-java/`)
- Inline base64 injection into constructor to prevent linker `--gc-sections` stripping

---

## 3.6.11 *(2026-07-29)*

### Infrastructure
- Remove CHANGELOG-less release guard — publish always creates release


## 3.6.10 *(2026-07-29)*

### Bug Fixes
- Add explicit `#include <cstdint>` for MinGW cross-compilation type safety


## 3.6.9 *(2026-07-29)*

### Bug Fixes
- Use `JS_NewArrayBufferCopy` instead of `JS_NewArrayBuffer(NULL)`


## 3.6.8 *(2026-07-29)*

### Bug Fixes
- **Use `JS_NewArrayBufferCopy`** — replaced `JS_NewArrayBuffer(NULL)` with explicit copy API.
  Fixes Base64 decode producing garbled output on Windows (MinGW cross-compiled QuickJS).

### Infrastructure
- **Fix publish workflow paths** — native resource `cp` commands now target `wrapper-java/src/main/resources/`
  instead of repo root, ensuring CI-built DLLs are actually embedded in the JAR.

---

## 3.6.7 *(2026-07-29)*

### Bug Fixes
- Fix `JS_NewArrayBuffer` allocator mismatch — use NULL free_func to let QuickJS copy data
- Fix Base64 decode table `int8_t` initializer compatibility on MinGW

---

## 3.6.6 *(2026-07-29)*

### Bug Fixes
- **Inline base64 injection into constructor** — `injectBase64Functions` was compiled as a `static` function
  and stripped by `-Wl,--gc-sections` on Windows, leaving `Uint8ArrayToBase64`/`Base64ToUint8Array` undefined.
  The setup code is now inlined directly into the QuickJSWrapper constructor.

---

## 3.6.5 *(2026-07-29)*

### Bug Fixes
- Fix Base64 encode padding logic — track actual triplet bytes read instead of using offset comparisons
- Fix Base64 decode — validate input length, handle `=` padding and invalid characters, stop buffer overrun
- Use compile-time constant decode table instead of runtime-init static (thread safety)

### New Features
- **`Uint8ArrayToBase64(arr)`** — converts ArrayBuffer/Uint8Array to base64 string. Injected globally via C binding.
- **`Base64ToUint8Array(str)`** — converts base64 string to ArrayBuffer. Injected globally via C binding.
- Base64 decode returns ArrayBuffer with zero-copy transfer (ownership passed via free callback)

---

## 3.6.4 *(2026-07-29)*

### New Features
- **`Uint8ArrayToBase64(arr)`** — converts ArrayBuffer/Uint8Array to base64 string. Injected globally via C binding.
- **`Base64ToUint8Array(str)`** — converts base64 string to ArrayBuffer. Injected globally via C binding.

---

## 3.6.3 *(2026-07-28)*

### Bug Fixes
- **Fix Windows DLL dependency loading** — `extractAndLoadDeps` incorrectly relied on Windows DLL search path to find `libwinpthread-1.dll`. All platforms now explicitly `System.load()` dependency DLLs before loading the main library, ensuring they are registered in the process address space regardless of OS search behavior.
- **Use fixed extraction directory** — replaced `Files.createTempDirectory(random)` with a fixed path `%TEMP%/yeow-quickjs`, avoiding directory accumulation from repeated plugin reloads and ensuring dependency DLLs persist across restarts.
- **Remove unused dependency entries** — dropped `libgcc_s_seh-1.dll`, `libstdc++-6.dll`, and 32-bit fallback names from Windows `knownDeps`; only `libwinpthread-1.dll` is needed.

---

## 3.6.2 *(2026-07-22)*

### Fix loaders

---

## 3.5.1 *(2026-07-13)*

### Bug Fixes
- **Re-added libwinpthread-1.dll extraction** — 3.5.0 removed `libwinpthread-1.dll` bundling claiming fully static linkage, but `-static` does not cover MinGW's pthread/winthread layer. `QuickJSNativeLoader` now copies `libwinpthread-1.dll` alongside the main DLL before loading, restoring cross-platform compatibility.
- **Include actual error cause in native load failure** — error message now shows whether the failure is due to missing classpath resource, `UnsatisfiedLinkError` (with detail), extraction error, or unsupported platform.
- **Copy matching MinGW runtime DLLs at CI time** — publish workflow now copies `libwinpthread-1.dll` (and `libgcc_s_seh-1.dll`, `libstdc++-6.dll` if present) from the cross-compiler sysroot into the JAR resources, ensuring the bundled runtime DLLs match the compiled main DLL.

### Infrastructure
- `copyDependencies()` helper in `QuickJSNativeLoader` for bundling platform-specific dependency DLLs at load time

---

## 3.5.0 *(2026-06-19)*

### Breaking Changes
- Removed extra `libwinpthread-1.dll` bundling — Windows native DLL is now fully statically linked, no runtime dependency needed
- `QuickJSNativeLoader` no longer extracts `libwinpthread-1.dll` from JAR resources

### Infrastructure
- **Windows cross-compiled from Linux** via MinGW-w64 — replaces MSYS2/MinGW on `windows-2025` runner, reducing CI time from ~10min to ~3min
- **Fully static Windows DLL** — `-static -static-libgcc -static-libstdc++` eliminates all external DLL dependencies
- **macOS linker** switched from `-Wl,--gc-sections` (GNU ld) to `-Wl,-dead_strip` (Apple ld64) for compatibility
- **CMake Threads** — `find_package(Threads REQUIRED)` for portable pthread linkage across all platforms
- **publish.yml** — native libs are now embedded into platform-specific JARs before Gradle assembly, enabling auto-loading from classpath
- **JNI cross-compilation** — `JAVA_PLATFORM_INCLUDE` env var for supplying platform-specific `jni_md.h` when cross-compiling

### Documentation
- `CMakeLists.txt` documents that `quickjs-libc.c` (std.* / os.* modules) is intentionally excluded — Yeow runs inside JVM and provides all I/O through Java

---

## 3.4.0 *(2026-05-01)*

### Breaking Changes
- Removed all Android content: `wrapper-android` module, Android Gradle Plugin, Google Maven repos, NDK dependency
- **QuickJS 2025-09-13** — upgraded from 2024-02-14. Enables `FinalizationRegistry`, `WeakRef`, symbols as weakrefs, new BigInt implementation, ES2023
- Removed `CONFIG_BIGNUM` — bignum extension removed in QuickJS 2025-04-26, replaced by built-in BigInt. `libbf.c/h` → `dtoa.c/h`
- Group ID changed: `wang.harlon.quickjs` → `com.github.iYeXin`

### New Features
- **`executePendingJob()`** — public API to pump Promise microtask queue from Java event loop. Returns positive count of jobs executed, 0 if idle, throws QuickJSException on error
- **`QuickJSContext.call()`** is now `public` — call any JS function by pointer from Java, enabling custom event loop integration
- **Windows runtime bundling** — `libwinpthread-1.dll` automatically extracted alongside main DLL at load time, no external MinGW runtime needed
- **Cross-platform native builds** — prebuilt native libraries for Linux/macOS x86_64/macOS ARM64/Windows via GitHub Actions CI
- **Platform-specific JARs** — smaller deployment (~370 KB) with only the native lib for the target platform
- **Auto-native loading** — `QuickJSNativeLoader` detects platform and extracts native library from JAR automatically

### Infrastructure
- Windows CI switched to MinGW with static pthread linking (`-Wl,-Bstatic -l:libwinpthread.a`)
- macOS and Linux CI build with `-O2` (replaced `-Os` for stability on GCC 15)
- GitHub Releases automatically attach native binaries (.so, .dylib, .dll) and Windows runtime dependency
- Cleaned up repository: removed `app/`, `wrapper-android/`, `images/`, `remarks.md`

---

## 3.2.3 *(2025-07-01)*
- refactor: 优化 JS 空返回类型

## 3.2.0 *(2025-05-15)*
- feature: support 16 KB page sizes.

## 3.1.0 *(2025-03-17)*
- Align internal and external version numbers to 3.1.0, no functional changes – codebase remains identical to 2.4.5.

## 2.4.5 *(2025-03-15)*
- fix: not correctly released when an exception occurs during function execution

## 2.4.4 *(2025-02-12)*
- 优化 toMap 的循环引用处理逻辑
- quickjs 增加判空处理，解决 OOM 场景里的异常崩溃问题

## 2.4.3 *(2025-01-21)*
- 添加了在将 JSObjects 转换为 Java Maps 时支持自定义映射创建。
- 添加了 setGCThreshold 方法，用于控制垃圾回收阈值。

## 2.4.2 *(2025-01-10)*
- 修复：源码执行模式下的字符串泄漏问题

## 2.4.1 *(2024-12-04)*
- 修复：异步函数执行导致的崩溃问题
- 优化：objectRecords 中的引用泄漏问题

## 2.4.0 *(2024-11-14)*
- 新增方法: 获取使用内存的大小信息（getMemoryUsedSize）
- 支持 ArrayBuffer 转为 Byte 数组（深拷贝，对性能有一些影响）

## 2.2.1 *(2024-09-29)*
- JSObject 增加 toMap 方法，支持转 HashMap 类型

## 2.1.0 *(2024-09-20)*
- 升级 QuickJS 至 2024-0214 版本
- 优化不同平台的 string 转换

## 2.0.0 *(2024-08-01)*
> :warning: 请注意，这是一次比较大的改动，如果是从老版本升级到该版本，需要你自己验证并回归自己的场景！
- JSObject 相关类接口化改造，方便扩展
- 稳定性提升
  - 修复了一些引用计数带来的崩溃异常
  - 优化了函数执行状态判断，避免一些复杂场景下的时机问题
  - 去处部分主动释放引用的逻辑，交由使用方自行释放
  - 其他可能会影响稳定的逻辑优化
- 增加一些内存泄漏的排查能力
- 一些历史 bugfix

## 1.0.0 *(2023-09-28)*
- 修复：模块重复加载问题
- 特性：支持模块字节码编译
- 优化：console 日志模块
- 优化：异常检测逻辑

## 0.21.1 *(2023-08-01)*
- 修复：字节码执行中没有调用 `executePendingJobLoop`

## 0.21.0 *(2023-07-28)*
- 优化：DumpObjects 日志输出到指定文件中方便查看
- 优化：JSObject.toString 和 JavaScript 保持一致
- 文档：修证书写错误

## 0.20.2 *(2023-06-30)*
- 修复: cmake 构建提示 floor 方法的头文件缺失问题

## 0.20.1 *(2023-06-14)*
- 特性: 优化 Long 和 Number、 BigInt 类型的互转逻辑

## 0.20.0 *(2023-06-07)*
- 修复: JSString 对象没有释放引起的泄漏问题
- 优化: 移除 js 层对 Array.at 的支持，由 c 层实现

## 0.19.3 *(2023-06-07)*

- 修复：JNI 层 hashCode 无法获取问题

## 0.19.2 *(2023-06-06)*

- 特性：支持 `Long` 类型数据在 JavaScript 中的传递

## 0.19.1 *(2023-06-05)*

- 修复 `Attempt to remove non-JNI local reference` 的错误警告
- 修复 `QuickJSWrapper` 析构函数执行时可能会出现的 `use deleted global reference` 异常
- 重构 `JSCallFunction` 的绑定逻辑，避免使用 NewGlobalRef 带来的 global reference table overflow (max=51200) 异常
- 集成 GitHub Action 提升版本发布效率