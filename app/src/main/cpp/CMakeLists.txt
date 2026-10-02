cmake_minimum_required(VERSION 3.22.1)
project(rechka_resampler)

add_library(rechka_resampler SHARED native_resampler.cpp)

target_include_directories(rechka_resampler PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../../ffmpeg-build/include"
)

target_link_libraries(rechka_resampler
    "${CMAKE_CURRENT_LIST_DIR}/../../../../ffmpeg-build/lib/libswresample.a"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../ffmpeg-build/lib/libavutil.a"
    log
    android
    dl
    m
)
