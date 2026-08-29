cmake_minimum_required(VERSION 3.22.1)
project(gomoku_tactics)

add_library(gomoku_tactics SHARED gomoku_tactics.cpp)

target_compile_features(gomoku_tactics PRIVATE cxx_std_17)
target_compile_options(gomoku_tactics PRIVATE
    -Wall -Wextra -Werror
    $<$<CONFIG:Release>:-O2>
)
