cmake_minimum_required(VERSION 3.23)
project(quickjs-java-wrapper)

set(CMAKE_CXX_STANDARD 11)

include(${CMAKE_CURRENT_SOURCE_DIR}/../../../CMakeLists.txt)

add_library( # Sets the name of the library.
        quickjs-java-wrapper

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        ${wrapper_src} ${quickjs_src})



find_package(Threads REQUIRED)
target_link_libraries(quickjs-java-wrapper PRIVATE Threads::Threads)

include_directories($ENV{JAVA_HOME}/include/)

if(DEFINED ENV{JAVA_PLATFORM_INCLUDE} AND NOT "$ENV{JAVA_PLATFORM_INCLUDE}" STREQUAL "")
    # Cross-compilation: point to extracted Windows jni_md.h, etc.
    include_directories($ENV{JAVA_PLATFORM_INCLUDE})
elseif(WIN32)
    include_directories($ENV{JAVA_HOME}/include/win32)
elseif(APPLE)
    include_directories($ENV{JAVA_HOME}/include/darwin)
else()
    include_directories($ENV{JAVA_HOME}/include/linux)
endif()