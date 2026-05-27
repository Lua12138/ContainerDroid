# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.4.1)
project(blackbox)
include(CheckCXXCompilerFlag)

check_cxx_compiler_flag("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=." BLACKBOX_HAS_FILE_PREFIX_MAP)
if (BLACKBOX_HAS_FILE_PREFIX_MAP)
        set(BLACKBOX_PREFIX_MAP_FLAG "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
else ()
        set(BLACKBOX_PREFIX_MAP_FLAG "-fdebug-prefix-map=${CMAKE_SOURCE_DIR}=.")
endif ()

if (NOT DEFINED BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED)
        set(BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED 1)
endif ()

if (NOT DEFINED BLACKBOX_GUARD_SIGNATURE_SHA256)
        set(BLACKBOX_GUARD_SIGNATURE_SHA256 "")
endif ()

if (NOT DEFINED BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS)
        set(BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS 3)
endif ()

if (NOT DEFINED BLACKBOX_GUARD_DIAGNOSTIC_ENABLED)
        set(BLACKBOX_GUARD_DIAGNOSTIC_ENABLED 0)
endif ()

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
include_directories(JniHook)
include_directories(./)

aux_source_directory(./ SRC1)
aux_source_directory(Utils SRC2)
set(SRC3
        Hook/BaseHook.cpp
        Hook/BinderHook.cpp
        Hook/JniDiagnosticsHook.cpp
        Hook/NativeFileHook.cpp
        Hook/RuntimeHook.cpp
        Hook/UnixFileSystemHook.cpp
        Hook/VMClassLoaderHook.cpp)
aux_source_directory(JniHook SRC4)
add_compile_options(-w)
if (CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
        set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/Hook/NativeFileHook.cpp PROPERTIES
                COMPILE_FLAGS "-O1 -fno-omit-frame-pointer")
endif ()
add_library( # Sets the name of the library.
        blackbox
        # Sets the library as a shared library.
        SHARED
        # Provides a relative path to your source file(s).
        BoxCore.cpp
        HostGuard.cpp
        SeccompShield.cpp
        RawSyscallTerminationProbe.cpp
        ${SRC1}
        ${SRC2}
        ${SRC3}
        ${SRC4}
        )

target_compile_options(blackbox PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        ${BLACKBOX_PREFIX_MAP_FLAG})

set_target_properties(blackbox PROPERTIES
        LINK_FLAGS "-Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,-soname,libblackbox.so")

target_compile_definitions(blackbox PRIVATE
        BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED=${BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED}
        BLACKBOX_GUARD_SIGNATURE_SHA256="${BLACKBOX_GUARD_SIGNATURE_SHA256}"
        BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS=${BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS}
        BLACKBOX_GUARD_DIAGNOSTIC_ENABLED=${BLACKBOX_GUARD_DIAGNOSTIC_ENABLED})

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        blackbox
        # Links the target library to the log library
        # included in the NDK.
        ${log-lib}
        z
        )
