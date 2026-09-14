cmake_minimum_required(VERSION 3.22.1)
project(forgekit_pty C)

# Clean-room ForgeKit PTY harness. Bionic provides forkpty/openpty in libc
# (API 23+, minSdk 24) so no extra libraries are needed on Android.
add_library(forgekit_pty SHARED forgekit_pty.c)

target_compile_options(forgekit_pty PRIVATE -O2 -Wall -Wextra)
