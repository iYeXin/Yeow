# QuickJS Wrapper for JVM

QuickJS wrapper for JVM, upgraded to [QuickJS 2025-09-13](https://bellard.org/quickjs/).

> Forked from [HarlonWang/quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper).

## Highlights

- **QuickJS 2025-09-13** — latest upstream with `FinalizationRegistry`, `WeakRef`, new BigInt, ES2023
- **Native cross-platform** — Linux / macOS x86_64 / macOS ARM64 / Windows prebuilt binaries
- **Auto-native loading** — detects platform and extracts native library from JAR automatically
- **No external dependencies** — QuickJS C source compiled to ~750 KB native library
- **ESModule support** (`import` / `export`)

## Quick Start

```java
QuickJSContext ctx = QuickJSContext.create();
ctx.evaluate("var x = 1 + 2;");

JSObject global = ctx.getGlobalObject();
global.setProperty("add", (JSCallFunction) args ->
    ((Number) args[0]).intValue() + ((Number) args[1]).intValue()
);

Object result = global.getJSFunction("add").call(3, 4); // 7
ctx.destroy();
```

## Download

### Maven (JitPack)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <!-- All platforms (1.4 MB) -->
    <dependency>
        <groupId>com.github.iYeXin</groupId>
        <artifactId>quickjs-java-wrapper</artifactId>
        <version>v3.4.0</version>
    </dependency>
</dependencies>
```

### Platform-specific JARs

Choose the JAR matching your deployment platform for smaller size:

| Platform       | Artifact                              | Size    |
| -------------- | ------------------------------------- | ------- |
| Linux x86_64   | `quickjs-java-wrapper-linux-x86_64`   | ~370 KB |
| macOS x86_64   | `quickjs-java-wrapper-macos-x86_64`   | ~380 KB |
| macOS ARM64    | `quickjs-java-wrapper-macos-arm64`    | ~370 KB |
| Windows x86_64 | `quickjs-java-wrapper-windows-x86_64` | ~390 KB |

### GitHub Releases

Prebuilt native binaries are attached to each [GitHub Release](https://github.com/iYeXin/quickjs-wrapper/releases):

- `libquickjs-java-wrapper.so` (Linux)
- `libquickjs-java-wrapper.dylib` (macOS)
- `libquickjs-java-wrapper.dll` (Windows)

## API

```java
QuickJSContext ctx = QuickJSContext.create();

// Evaluate JavaScript
ctx.evaluate("var obj = { name: 'hello', count: 42 };");

// Get global object
JSObject global = ctx.getGlobalObject();
JSObject obj = global.getJSObject("obj");

// Read properties
obj.getString("name");    // "hello"
obj.getInteger("count");  // 42

// Set properties
obj.setProperty("name", "world");
obj.setProperty("count", 100);

// Register Java callbacks
global.setProperty("javaAdd", (JSCallFunction) args ->
    ((Number) args[0]).intValue() + ((Number) args[1]).intValue()
);
ctx.evaluate("javaAdd(1, 2)"); // 3

// Console output
ctx.setConsole(new QuickJSContext.Console() {
    public void log(String s) { System.out.println(s); }
    public void info(String s) { System.out.println(s); }
    public void warn(String s) { System.err.println(s); }
    public void error(String s) { System.err.println(s); }
});

ctx.evaluate("console.log('Hello from JS!');");

// Promise microtask pump (for custom event loops)
int count = ctx.executePendingJob();  // 1 if a job ran, 0 if idle, -1 on error

// Direct JS function call by pointer
JSValue global = ctx.getGlobalObject();
Object ret = ctx.call(function, 0, 0, new Object[]{"arg1", 42});

// Manual memory management
JSFunction func = obj.getJSFunction("someMethod");
func.call(args);
func.release();  // must release after use

// Destroy context
ctx.destroy();
```

## Building from Source

### Prerequisites

- JDK 21+ with `JAVA_HOME` set
- CMake 3.23+
- C/C++ compiler (GCC, Clang, or MSVC)

### Build

```bash
git clone --recursive https://github.com/iYeXin/quickjs-wrapper.git
cd quickjs-wrapper
```

**Linux / macOS:**
```bash
cd wrapper-java
cmake -DCMAKE_BUILD_TYPE=MinSizeRel \
      -DCMAKE_C_FLAGS="-O2 -flto" \
      -DCMAKE_CXX_FLAGS="-O2 -flto" \
      -G Ninja -S src/main -B build/cmake
cmake --build build/cmake -j $(nproc)
```

**Windows (MSYS2 MinGW):**
```bash
pacman -S mingw-w64-x86_64-gcc mingw-w64-x86_64-cmake mingw-w64-x86_64-ninja

cd wrapper-java
cmake -DCMAKE_BUILD_TYPE=MinSizeRel \
      -DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++ \
      -DCMAKE_C_FLAGS="-O2 -ffunction-sections -fdata-sections" \
      -DCMAKE_CXX_FLAGS="-O2 -ffunction-sections -fdata-sections" \
      -DCMAKE_SHARED_LINKER_FLAGS="-static-libgcc -static-libstdc++ -Wl,--gc-sections -Wl,-Bstatic -l:libwinpthread.a -Wl,-Bdynamic" \
      -G "MinGW Makefiles" -S src/main -B build/cmake
cmake --build build/cmake -j $(nproc)
```

Output: `wrapper-java/build/cmake/libquickjs-java-wrapper.{so,dylib,dll}`

## Changes from Upstream

| Change                   | Reason                                                       |
| ------------------------ | ------------------------------------------------------------ |
| QuickJS → 2025-09-13     | ES2023, `FinalizationRegistry`, `WeakRef`, new BigInt        |
| Removed `CONFIG_BIGNUM`  | Bignum extension removed in QuickJS 2025-04-26               |
| Added `dtoa.c/h`         | Replaces removed `libbf`                                     |
| Auto-native loading      | `QuickJSNativeLoader` detects platform, extracts from JAR    |
| `executePendingJob()`    | Public API to pump Promise microtask queue from Java         |
| `QuickJSContext.call()`  | Public method — call any JS function directly by pointer     |
| Windows runtime bundling | `libwinpthread-1.dll` auto-extracted, no MinGW at runtime    |
| Platform-specific JARs   | Smaller deployment, ~370 KB vs 1.4 MB                        |
| Cross-platform CI        | GitHub Actions builds Linux/macOS x86_64/macOS ARM64/Windows |

## License

Apache 2.0 — inherits from [HarlonWang/quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper).

QuickJS engine is MIT licensed (Fabrice Bellard).
