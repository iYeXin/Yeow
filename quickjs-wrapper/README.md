# yeow-quickjs

Yeow 专用的 QuickJS JNI 桥。用 **C** 实现，用 **Zig 0.16** 编译与交叉编译。

不是通用包装器：Java ↔ JS 边界只保留 Yeow 运行时真正需要的部分——上下文生命周期、
`evaluate`、全局函数注册（JS → Java 上行调用）、全局函数调用（Java → JS 下行调用）、
Promise 微任务泵与中断钩子。JS 对象句柄不跨越边界。

- 引擎：QuickJS `2026-06-04`（`native/quickjs`，git submodule，[iyexin/quickjs](https://github.com/iyexin/quickjs)）
- 原生层：`native/src/*.c`，JNI 头已内置（`native/jni/`），构建**不需要 JDK**
- Java 层：`java/src/main/java/wiki/yexin/quickjs/`，包名 `wiki.yexin.quickjs`
- 一个工具链产出全部平台；Windows DLL 只依赖 UCRT/KERNEL32，无需附带 MinGW 运行库

## 构建

需要 [Zig 0.16.0](https://ziglang.org/download/0.16.0/)；打 JAR 时另需 `javac` / `jar`（JDK 21+）。

```bash
zig build                 # 本机平台的动态库 → zig-out/lib 或 zig-out/bin
zig build all             # 全部平台 → zig-out/native/<platform>/
zig build jar             # Java 类 + 全部平台原生库 → zig-out/yeow-quickjs.jar
zig build -Dtarget=x86_64-linux-gnu        # 选择目标平台
```

平台目录命名与类路径资源一致：

| 平台 | 资源路径 |
| --- | --- |
| Linux x86_64 / arm64 | `native/linux-x86_64/libyeow-quickjs.so` · `native/linux-arm64/…` |
| macOS x86_64 / arm64 | `native/macos-x86_64/libyeow-quickjs.dylib` · `native/macos-arm64/…` |
| Windows x86_64 / arm64 | `native/windows-x86_64/yeow-quickjs.dll` · `native/windows-arm64/…` |

`QuickJSNativeLoader` 先尝试 `System.loadLibrary("yeow-quickjs")`，失败后从类路径
`native/<platform>/` 提取到 `<tmp>/yeow-quickjs/` 再加载（Windows 被占用时使用
进程唯一文件名，支持同机多 JVM）。

## Java API

```java
try (QuickJSContext ctx = QuickJSContext.create()) {
    // JS -> Java
    ctx.setGlobalFunction("send", args -> {
        System.out.println(args[0] + " " + args[1]);
        return "ok";
    });

    ctx.evaluate("function greet(name){ return send('hi', name); }");
    Object result = ctx.callGlobal("greet", "yeow");   // "ok"

    // 值转换：JS number 整数 → Long，小数 → Double；string → String；
    // Array → Object[]；Object → LinkedHashMap；ArrayBuffer → byte[];
    // null/undefined → null。Java → JS 支持 String/Boolean/Integer/Long/Double/byte[]/Object[]。
}
```

完整接口见 `java/src/main/java/wiki/yexin/quickjs/QuickJSContext.java`。

热路径辅助：`long bindGlobal(name)` 绑定全局函数并返回句柄（`0` = 不存在），`Object callHandle(fnHandle, arg)` 直接调用句柄、免去每次全局属性查找；`void drainJobs()` 用**单次 JNI 调用**跑完整个微任务队列。`callGlobal` / `hasGlobalFunction` 保留用于一次性调用。

## 原生 Polyfill

`native/polyfill/` 存放**随 JS 上下文创建注入**的原生（C）全局 API——用于需要平台能力、无法用 JS 引导脚本实现的接口。QuickJS 源码不改动，polyfill 只是在上下文全局对象上做 `JS_NewCFunction` 绑定：

- `polyfill.h`：统一入口 `yeow_install_polyfills(JSContext*)` 与各 installer 声明
- `polyfill.c`：注册表，逐个调用 installer（`context.c` 创建上下文时调用一次）
- `performance.c`：`performance.now()`（单调高精度毫秒；Windows `QueryPerformanceCounter`、POSIX `clock_gettime(CLOCK_MONOTONIC)`）与 `performance.timeOrigin`
- `text_codec.c`：`TextEncoder` / `TextDecoder`（utf-8）——C 提供 `__yeowUtf8Encode` / `__yeowUtf8Decode` 原语，安装时以一小段内嵌 JS 包成两个类并从全局移除原语

新增 polyfill：实现 `void yeow_<name>_install(JSContext *ctx)` → 在 `polyfill.h` 声明、`polyfill.c` 调用、`build.zig` 的 `polyfill_sources` 登记。

## 运行冒烟测试

```bash
zig build jar
javac -d out/classes java/src/main/java/wiki/yexin/quickjs/*.java java/src/test/java/wiki/yexin/quickjs/SmokeTest.java
java -cp "zig-out/yeow-quickjs.jar;out/classes" wiki.yexin.quickjs.SmokeTest
```

## 与旧实现的关系

本目录原先镜像 [HarlonWang/quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper)，
现改为 Yeow 专用实现，因此移除了 CMake/Gradle/MinGW 构建链、通用包装器 Java API、
ESModule/字节码/二进制解析/内存诊断等未被 Yeow 使用的部分。QuickJS 引擎本身（submodule）不变。

## 许可证

本目录为 Apache-2.0（继承自原 quickjs-wrapper）；QuickJS 引擎为 MIT（Fabrice Bellard）。
