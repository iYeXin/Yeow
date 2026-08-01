# QuickJS Wrapper — Java

JVM bindings for QuickJS 2025-09-13.

## Building

### Prerequisites

- JDK 21+ (`JAVA_HOME` must be set and point to a JDK with `include/` headers)
- CMake 3.23+
- Build tool: Ninja or Make

### Linux

```bash
sudo apt install cmake ninja-build
cd wrapper-java

cmake -DCMAKE_BUILD_TYPE=MinSizeRel \
      -DCMAKE_C_FLAGS="-Os -flto -ffunction-sections -fdata-sections" \
      -DCMAKE_CXX_FLAGS="-Os -flto -ffunction-sections -fdata-sections" \
      -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--gc-sections -Wl,--strip-all" \
      -G Ninja -S src/main -B build/cmake

cmake --build build/cmake -j $(nproc)
```

### macOS

```bash
brew install cmake ninja
cd wrapper-java

cmake -DCMAKE_BUILD_TYPE=MinSizeRel \
      -DCMAKE_C_FLAGS="-Os -flto" \
      -DCMAKE_CXX_FLAGS="-Os -flto" \
      -G Ninja -S src/main -B build/cmake

cmake --build build/cmake -j $(sysctl -n hw.logicalcpu)
```

### Windows (MSYS2 MinGW)

```bash
# Install MSYS2 from https://www.msys2.org/
pacman -S mingw-w64-x86_64-gcc mingw-w64-x86_64-cmake mingw-w64-x86_64-ninja mingw-w64-x86_64-make

# Open "MSYS2 MinGW64" shell
cd wrapper-java
cmake -DCMAKE_BUILD_TYPE=MinSizeRel \
      -DCMAKE_C_COMPILER=gcc \
      -DCMAKE_CXX_COMPILER=g++ \
      -DCMAKE_C_FLAGS="-Os -flto -ffunction-sections -fdata-sections" \
      -DCMAKE_CXX_FLAGS="-Os -flto -ffunction-sections -fdata-sections" \
      -G "MinGW Makefiles" -S src/main -B build/cmake

cmake --build build/cmake -j $(nproc)
```

### Output

| Platform | File                                        |
| -------- | ------------------------------------------- |
| Linux    | `build/cmake/libquickjs-java-wrapper.so`    |
| macOS    | `build/cmake/libquickjs-java-wrapper.dylib` |
| Windows  | `build/cmake/libquickjs-java-wrapper.dll`   |

### Gradle Build (Java JAR)

```bash
cd ../..  # back to project root
./gradlew :wrapper-java:assemble
```

Output JARs in `wrapper-java/build/libs/`:

| JAR                                       | Contents                           |
| ----------------------------------------- | ---------------------------------- |
| `quickjs-java-wrapper.jar`                | All 4 platforms (~1.4 MB)          |
| `quickjs-java-wrapper-linux-x86_64.jar`   | Linux only (~370 KB)               |
| `quickjs-java-wrapper-macos-x86_64.jar`   | macOS Intel only (~380 KB)         |
| `quickjs-java-wrapper-macos-arm64.jar`    | macOS Apple Silicon only (~370 KB) |
| `quickjs-java-wrapper-windows-x86_64.jar` | Windows only (~390 KB)             |

## Platform Detection

`QuickJSNativeLoader` automatically detects the runtime platform and loads the matching native library:

1. Try `System.loadLibrary("quickjs-java-wrapper")` from `java.library.path`
2. Fallback: extract `native/<os>-<arch>/libquickjs-java-wrapper.*` from JAR to temp directory and load

## Memory Management

JS objects require explicit reference counting:

```java
JSObject obj = global.getJSObject("myObj");
obj.release();  // must release when done
```

Use `releaseObjectRecords()` before `destroy()` to find leaked references in debug builds.
