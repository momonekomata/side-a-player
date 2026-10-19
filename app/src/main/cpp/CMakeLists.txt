cmake_minimum_required(VERSION 3.22.1)
project(sidea_usb C)

add_library(sidea_usb SHARED usb_audio.c)
target_compile_options(sidea_usb PRIVATE -Wall -Wextra -O2)
target_link_libraries(sidea_usb log)
