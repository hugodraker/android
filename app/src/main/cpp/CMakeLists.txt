cmake_minimum_required(VERSION 3.22.1)
project("caldavd")

find_package(openssl REQUIRED CONFIG)

add_library(caldavd SHARED caldavd_jni.c)

target_link_libraries(
    caldavd
    openssl::crypto
    openssl::ssl
    android
    log
)
