cmake_minimum_required(VERSION 3.22.1)
project(arirang_zygisk)

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

add_library(arirang_zygisk SHARED
    src/main/cpp/core/arirang_zygisk.cpp
    src/main/cpp/core/submodule_config.cpp
    src/main/cpp/utils/io_utils.cpp
    src/main/cpp/utils/jni_utils.cpp
    src/main/cpp/utils/logging.cpp
    src/main/cpp/spoofer/build_spoofer.cpp
    src/main/cpp/spoofer/system_property_spoofer.cpp
    src/main/cpp/spoofer/sensor_spoofer.cpp
    src/main/cpp/spoofer/sensor_aosp_types.hpp
    src/main/cpp/hook/inline_hook.cpp
    src/main/cpp/hook/vtable_hook.cpp
    src/main/cpp/hook/symbol_resolver.cpp
)

target_compile_features(arirang_zygisk PRIVATE cxx_std_17)
target_compile_options(arirang_zygisk PRIVATE -Wall -Wextra -Werror)
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
# Intentionally has NO overlap with the Zygisk module - it must never be
# loaded into app processes.
# The DRM hook now uses the data-level vtable/method-table hook instead of the
# fragile inline hook, so inline_hook.cpp is intentionally omitted here.
add_library(arirang_drm_hook SHARED
    src/main/cpp/drm/arirang_drm_hook.cpp
    src/main/cpp/hook/symbol_resolver.cpp
    src/main/cpp/hook/vtable_hook.cpp
    src/main/cpp/utils/logging.cpp
)

target_compile_features(arirang_drm_hook PRIVATE cxx_std_17)
target_compile_options(arirang_drm_hook PRIVATE -Wall -Wextra -Werror)
target_include_directories(arirang_drm_hook PRIVATE
    src/main/cpp
    src/main/cpp/core
    src/main/cpp/utils
    src/main/cpp/hook
    src/main/cpp/drm
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)
target_link_libraries(arirang_drm_hook log dl)

# Standalone executable launched from the module's service.sh after boot.
# Uses ptrace to remote-dlopen libarirang_drm_hook.so inside the Widevine
# HAL daemon's address space. Statically linked against libc++ so the
# resulting binary can run from /data without depending on the daemon's
# linker namespace.
add_executable(arirang_injector
    src/main/cpp/drm/arirang_injector.cpp
)

target_compile_features(arirang_injector PRIVATE cxx_std_17)
target_compile_options(arirang_injector PRIVATE -Wall -Wextra -Werror)
target_include_directories(arirang_injector PRIVATE
    src/main/cpp
    src/main/cpp/core
    src/main/cpp/utils
    src/main/cpp/drm
    src/main/cpp/api
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)
target_link_libraries(arirang_injector log dl)

