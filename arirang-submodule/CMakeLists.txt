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
