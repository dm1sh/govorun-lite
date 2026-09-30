cmake_minimum_required(VERSION 3.22.1)
project(govorun_resampler)

add_library(govorun_resampler SHARED native_resampler.cpp)

target_include_directories(govorun_resampler PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../../ffmpeg-build/include"
)

target_link_libraries(govorun_resampler
    "${CMAKE_CURRENT_LIST_DIR}/../../../../ffmpeg-build/lib/libswresample.a"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../ffmpeg-build/lib/libavutil.a"
    log
    android
    dl
    m
)
