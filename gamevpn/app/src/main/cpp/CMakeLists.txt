cmake_minimum_required(VERSION 3.22.1)
project("packetengine")

add_library(
    packetengine
    SHARED
    packet_processor.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    packetengine
    ${log-lib}
    ${android-lib}
)

target_compile_options(packetengine PRIVATE
    -std=c++17
    -O3
    -march=armv8-a
    -ffast-math
    -fvisibility=hidden
    -Wall
    -Wextra
)
