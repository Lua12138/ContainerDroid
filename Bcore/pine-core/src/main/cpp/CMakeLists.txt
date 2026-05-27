cmake_minimum_required(VERSION 3.4.1)
project(pine)
include(CheckCXXCompilerFlag)

check_cxx_compiler_flag("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=." PINE_HAS_FILE_PREFIX_MAP)
if (PINE_HAS_FILE_PREFIX_MAP)
    set(PINE_PREFIX_MAP_FLAG "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
else()
    set(PINE_PREFIX_MAP_FLAG "-fdebug-prefix-map=${CMAKE_SOURCE_DIR}=.")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LINKER_FLAGS "-ffixed-x18 -Wl,--hash-style=both")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${LINKER_FLAGS}")
set(CMAKE_STATIC_LINKER_FLAGS "${CMAKE_STATIC_LINKER_FLAGS} ${LINKER_FLAGS}")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} ${LINKER_FLAGS}")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} ${LINKER_FLAGS}")

set(PINE_SOURCES
        pine.cpp
        ruler.cpp
        android.cpp
        jni_bridge.cpp
        art/art_method.cpp
        art/thread.cpp
        art/jit.cpp
        trampoline/trampoline_installer.cpp
        utils/memory.cpp
        utils/scoped_memory_access_protection.cpp
        utils/elf_img.cpp
        utils/well_known_classes.cpp)

if (CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -arch armv7")
    set(PINE_SOURCES
            ${PINE_SOURCES}
            trampoline/arch/thumb2.cpp
            trampoline/arch/thumb2.S)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -arch arm64")
    set(PINE_SOURCES
            ${PINE_SOURCES}
            trampoline/arch/arm64.cpp
            trampoline/arch/arm64.S)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -arch x86")
    set(PINE_SOURCES
            ${PINE_SOURCES}
            trampoline/arch/x86.cpp)
else()
    message(FATAL_ERROR "Unsupported cpu abi ${CMAKE_ANDROID_ARCH_ABI} (Only armeabi-v7a and arm64-v8a are now supported)!")
endif()

add_library(pine SHARED ${PINE_SOURCES})

target_compile_options(pine PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        ${PINE_PREFIX_MAP_FLAG})

set_target_properties(pine PROPERTIES
        LINK_FLAGS "-Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/pine.exports.map -Wl,-soname,libpine.so")

if (NOT DEFINED PINE_LOGCAT_ENABLED)
    set(PINE_LOGCAT_ENABLED 1)
endif()
target_compile_definitions(pine PRIVATE PINE_LOGCAT_ENABLED=${PINE_LOGCAT_ENABLED})

find_library(log-lib log)
# find_package(cxx REQUIRED CONFIG)

target_link_libraries(pine ${log-lib})

ENABLE_LANGUAGE(ASM)
