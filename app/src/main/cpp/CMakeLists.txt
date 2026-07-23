cmake_minimum_required(VERSION 3.22.1)

project(rebound LANGUAGES CXX)

# Provided by the com.google.oboe:oboe AAR via AGP's prefab support.
find_package(oboe REQUIRED CONFIG)

add_library(rebound SHARED
        native-lib.cpp
        AudioEngine.cpp)

target_compile_options(rebound PRIVATE -Wall -Wextra -O3 -ffast-math)

target_link_libraries(rebound
        oboe::oboe
        android
        log)
