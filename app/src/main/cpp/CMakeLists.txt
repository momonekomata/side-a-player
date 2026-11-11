cmake_minimum_required(VERSION 3.22.1)
project(sidea_usb C)

add_library(sidea_usb SHARED usb_audio.c)
target_compile_options(sidea_usb PRIVATE -Wall -Wextra -O2)
target_link_libraries(sidea_usb log)
# 16 KB pages (Android 15+ devices that use them, and Play's requirement): align the LOAD
# segments to 16 KB, which works on 4 KB-page devices too.
target_link_options(sidea_usb PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
