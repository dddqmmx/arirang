cmake_minimum_required(VERSION 3.22.1)
project(arirang_zygisk)

add_library(arirang_zygisk SHARED
    src/main/cpp/arirang_zygisk.cpp
)

target_compile_features(arirang_zygisk PRIVATE cxx_std_17)
target_compile_options(arirang_zygisk PRIVATE -Wall -Wextra -Werror)
target_include_directories(arirang_zygisk PRIVATE src/main/cpp)
target_link_libraries(arirang_zygisk log)
