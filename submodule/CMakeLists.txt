cmake_minimum_required(VERSION 3.22.1)
project(arirang_zygisk)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Native build type" FORCE)
endif()

# Architecture check: this module is designed exclusively for ARM64 (AArch64).
# The DRM hook (vtable scanning, inline trampolines) and the Zygisk injector
# (ptrace injection) both assume AArch64 instruction encodings, register sizes,
# and calling conventions. Building for x86, x86_64, or ARM32 is unsupported.
if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)")
    message(FATAL_ERROR "arirang-submodule supports only arm64 (aarch64). "
                        "Detected: ${CMAKE_SYSTEM_PROCESSOR}")
endif()

set(ARIRANG_APPLICATION_ID "" CACHE STRING "Android application id that owns the submodule config.")
set(ARIRANG_SUBMODULE_CONFIG_DIR "" CACHE STRING "Submodule config directory under app files.")
set(ARIRANG_SUBMODULE_CONFIG_FILE "" CACHE STRING "Submodule config file name.")

if(ARIRANG_APPLICATION_ID STREQUAL "")
    message(FATAL_ERROR "ARIRANG_APPLICATION_ID is required")
endif()
if(ARIRANG_SUBMODULE_CONFIG_DIR STREQUAL "")
    message(FATAL_ERROR "ARIRANG_SUBMODULE_CONFIG_DIR is required")
endif()
if(ARIRANG_SUBMODULE_CONFIG_FILE STREQUAL "")
    message(FATAL_ERROR "ARIRANG_SUBMODULE_CONFIG_FILE is required")
endif()

# These cache values are rendered into a C++ string literal and a privileged
# absolute path. Restrict them to their intended grammar before configure_file
# so a poisoned Gradle property cannot inject C++ or traverse directories.
if(NOT ARIRANG_APPLICATION_ID MATCHES "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    message(FATAL_ERROR "ARIRANG_APPLICATION_ID is not a valid Android application id")
endif()
foreach(component ARIRANG_SUBMODULE_CONFIG_DIR ARIRANG_SUBMODULE_CONFIG_FILE)
    string(LENGTH "${${comzponent}}" component_length)
    if(component_length GREATER 128 OR
       NOT "${${component}}" MATCHES "^[A-Za-z0-9][A-Za-z0-9._-]*$" OR
       "${${component}}" STREQUAL "." OR "${${component}}" STREQUAL "..")
        message(FATAL_ERROR "${component} must be a single safe path component")
    endif()
endforeach()

set(ARIRANG_CONFIG_PATH_DE
    "/data/user_de/0/${ARIRANG_APPLICATION_ID}/files/${ARIRANG_SUBMODULE_CONFIG_DIR}/${ARIRANG_SUBMODULE_CONFIG_FILE}"
)
set(ARIRANG_CONFIG_PATH_CE
    "/data/user/0/${ARIRANG_APPLICATION_ID}/files/${ARIRANG_SUBMODULE_CONFIG_DIR}/${ARIRANG_SUBMODULE_CONFIG_FILE}"
)

configure_file(
    src/main/cpp/core/arirang_build_config.hpp.in
    ${CMAKE_CURRENT_BINARY_DIR}/generated/arirang_build_config.hpp
    @ONLY
)

function(arirang_harden_target target)
    target_compile_options(${target} PRIVATE
        -Wall
        -Wextra
        -Werror
        -Wformat=2
        -Wformat-security
        -Werror=format-security
        -fstack-protector-strong
        -fno-omit-frame-pointer
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -mbranch-protection=standard
        # -U first avoids a "redefined" warning when the NDK sysroot already
        # predefines _FORTIFY_SOURCE at a lower level for this API level.
        -U_FORTIFY_SOURCE
        -D_FORTIFY_SOURCE=2
    )
    target_link_options(${target} PRIVATE
        -Wl,--build-id=sha1
        -Wl,--exclude-libs,ALL
        -Wl,--fatal-warnings
        -Wl,--gc-sections
        -Wl,--no-undefined
        -Wl,--strip-debug
        -Wl,-z,max-page-size=16384
        -Wl,-z,noexecstack
        -Wl,-z,now
        -Wl,-z,relro
        -Wl,-z,separate-code
    )
    set_target_properties(${target} PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
        POSITION_INDEPENDENT_CODE YES
    )
endfunction()

add_library(arirang_zygisk SHARED
    src/main/cpp/core/arirang_zygisk.cpp
    src/main/cpp/core/runtime_config.cpp
    src/main/cpp/core/submodule_config.cpp
    src/main/cpp/utils/io_utils.cpp
    src/main/cpp/utils/jni_utils.cpp
    src/main/cpp/utils/logging.cpp
    src/main/cpp/spoofer/build_spoofer.cpp
    src/main/cpp/spoofer/system_property_spoofer.cpp
    src/main/cpp/spoofer/sensor_spoofer.cpp
    src/main/cpp/spoofer/sensor_aosp_bridge.cpp
    src/main/cpp/spoofer/sensor_rules.cpp
    src/main/cpp/spoofer/sensor_vtable_patch.cpp
    src/main/cpp/spoofer/sensor_precision.cpp
    src/main/cpp/spoofer/sensor_aosp_types.hpp
    src/main/cpp/spoofer/sensor_shared_state.hpp
    src/main/cpp/hook/inline_hook.cpp
    src/main/cpp/hook/vtable_hook.cpp
    src/main/cpp/hook/symbol_resolver.cpp
)

target_compile_features(arirang_zygisk PRIVATE cxx_std_17)
arirang_harden_target(arirang_zygisk)
target_include_directories(arirang_zygisk PRIVATE
    src/main/cpp
    src/main/cpp/core
    src/main/cpp/utils
    src/main/cpp/spoofer
    src/main/cpp/hook
    src/main/cpp/api
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)
target_link_libraries(arirang_zygisk log dl)

# Separate native library LD_PRELOAD'd into the Widevine DRM HAL daemon.
# Intentionally has NO overlap with the Zygisk module — it must never be
# loaded into app processes.
#
# Hook strategy (ARM64 only):
#   1. PRIMARY: vtable/method-table data-level hook (drm_vtable_hook.cpp)
#   2. FALLBACK: instruction-level inline hook (drm_inline_hook.cpp)
#      Used when vtable scanning finds no usable slots. Logged as a
#      downgrade warning. Requires inline_hook.cpp (trampoline allocation
#      and prologue patching).
add_library(arirang_drm_hook SHARED
    src/main/cpp/drm/arirang_drm_hook.cpp
    src/main/cpp/drm/drm_vtable_hook.cpp
    src/main/cpp/drm/drm_inline_hook.cpp
    src/main/cpp/hook/inline_hook.cpp
    src/main/cpp/hook/symbol_resolver.cpp
    src/main/cpp/hook/vtable_hook.cpp
    src/main/cpp/utils/io_utils.cpp
    src/main/cpp/utils/logging.cpp
)

target_compile_features(arirang_drm_hook PRIVATE cxx_std_17)
arirang_harden_target(arirang_drm_hook)
target_include_directories(arirang_drm_hook PRIVATE
    src/main/cpp
    src/main/cpp/core
    src/main/cpp/utils
    src/main/cpp/hook
    src/main/cpp/drm
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)
target_link_libraries(arirang_drm_hook log dl binder_ndk)

# Standalone executable launched from the module's service.sh after boot.
# Uses ptrace to remote-dlopen libarirang_drm_hook.so inside the Widevine
# HAL daemon's address space. Statically linked against libc++ so the
# resulting binary can run from /data without depending on the daemon's
# linker namespace.
add_executable(arirang_injector
    src/main/cpp/drm/arirang_injector.cpp
)

target_compile_features(arirang_injector PRIVATE cxx_std_17)
arirang_harden_target(arirang_injector)
target_include_directories(arirang_injector PRIVATE
    src/main/cpp
    src/main/cpp/core
    src/main/cpp/utils
    src/main/cpp/drm
    src/main/cpp/api
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)
target_link_libraries(arirang_injector log dl)
