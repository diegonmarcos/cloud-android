# SPDX-License-Identifier: Apache-2.0
#
# Slim CMakeLists.txt for Cloud-SuperApp's libs/net WireGuard tunnel.
#
# Upstream wireguard-android/tunnel ships a CMakeLists.txt that builds
# three native targets — libwg-go.so (Go userspace) + libwg.so and
# libwg-quick.so (C userspace, from the `wireguard-tools` git submodule
# which is empty in our clone) — and runs an `elf-cleaner` post-pass
# (also a git submodule, also empty). Both libwg{,-quick}.so are only
# needed by WgQuickBackend (the rooted-Android backend) which we drop
# at sync time. SuperApp targets unrooted Android via GoBackend only,
# so libwg-go.so is the entire native surface we need.
#
# This file is NOT overwritten by `./build.sh sync-net` — the sync
# helper excludes CMakeLists.txt so this stays canonical across
# upstream pulls.

cmake_minimum_required(VERSION 3.4.1)
project("WireGuard")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
add_link_options(LINKER:--build-id=none)
add_compile_options(-Wall -Werror)

# libwg-go.so — wireguard-go userspace + JNI bridge. The Makefile in
# libwg-go/ downloads + checksum-verifies Go 1.24.x and produces a
# c-shared library; CMake just delegates.
add_custom_target(libwg-go.so
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/libwg-go"
    COMMENT "Building wireguard-go" VERBATIM
    COMMAND "${ANDROID_HOST_PREBUILTS}/bin/make"
        ANDROID_ARCH_NAME=${ANDROID_ARCH_NAME}
        ANDROID_PACKAGE_NAME=${ANDROID_PACKAGE_NAME}
        GRADLE_USER_HOME=${GRADLE_USER_HOME}
        CC=${CMAKE_C_COMPILER}
        CFLAGS=${CMAKE_C_FLAGS}
        LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS}
        SYSROOT=${CMAKE_SYSROOT}
        TARGET=${CMAKE_C_COMPILER_TARGET}
        DESTDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        BUILDDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/../generated-src
)
