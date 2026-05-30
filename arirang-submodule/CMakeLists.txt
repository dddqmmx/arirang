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
    src/main/cpp/arirang_build_config.hpp.in
    ${CMAKE_CURRENT_BINARY_DIR}/generated/arirang_build_config.hpp
    @ONLY
)

add_library(arirang_zygisk SHARED
    src/main/cpp/arirang_zygisk.cpp
    src/main/cpp/build_spoofer.cpp
    src/main/cpp/io_utils.cpp
    src/main/cpp/jni_utils.cpp
    src/main/cpp/logging.cpp
    src/main/cpp/submodule_config.cpp
    src/main/cpp/system_property_spoofer.cpp
)

target_compile_features(arirang_zygisk PRIVATE cxx_std_17)
target_compile_options(arirang_zygisk PRIVATE -Wall -Wextra -Werror)
target_include_directories(arirang_zygisk PRIVATE
    src/main/cpp
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)
target_link_libraries(arirang_zygisk log)

# Separate native library LD_PRELOAD'd into the Widevine DRM HAL daemon.
# Intentionally has NO overlap with the Zygisk module - it must never be
# loaded into app processes.
add_library(arirang_drm_hook SHARED
    src/main/cpp/arirang_drm_hook.cpp
    src/main/cpp/inline_hook.cpp
    src/main/cpp/logging.cpp
    src/main/cpp/symbol_resolver.cpp
)

target_compile_features(arirang_drm_hook PRIVATE cxx_std_17)
target_compile_options(arirang_drm_hook PRIVATE -Wall -Wextra -Werror)
target_include_directories(arirang_drm_hook PRIVATE
    src/main/cpp
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)
target_link_libraries(arirang_drm_hook log dl)

# Standalone executable launched from the module's service.sh after boot.
# Uses ptrace to remote-dlopen libarirang_drm_hook.so inside the Widevine
# HAL daemon's address space. Statically linked against libc++ so the
# resulting binary can run from /data without depending on the daemon's
# linker namespace.
add_executable(arirang_injector
    src/main/cpp/arirang_injector.cpp
)

target_compile_features(arirang_injector PRIVATE cxx_std_17)
target_compile_options(arirang_injector PRIVATE -Wall -Wextra -Werror)
target_link_libraries(arirang_injector log dl)
