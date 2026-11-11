cmake_minimum_required(VERSION 3.22.1)

project(wearvpn LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Static dependencies will be linked into libwearvpn.so.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(TUNNEL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/openvpn")
set(THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
set(LWIP_DIR "${THIRD_PARTY_DIR}/lwip")

# This runtime uses lwIP's IPv4 raw API.
# Ethernet, PPP and serial-interface implementations are not needed.
file(GLOB LWIP_SOURCES CONFIGURE_DEPENDS
    "${LWIP_DIR}/src/core/*.c"
    "${LWIP_DIR}/src/core/ipv4/*.c"
)

# ----- mbedTLS -----

set(USE_MBEDTLS ON CACHE BOOL "" FORCE)
set(MBEDTLS_AS_SUBPROJECT ON CACHE BOOL "" FORCE)

# Use generated sources included in the pinned snapshot; no Perl generator is needed.
set(GEN_FILES OFF CACHE BOOL "" FORCE)

set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)

add_subdirectory(
    "${THIRD_PARTY_DIR}/mbedtls"
    "${CMAKE_CURRENT_BINARY_DIR}/mbedtls"
)

# ----- fmt -----

set(FMT_INSTALL OFF CACHE BOOL "" FORCE)
set(FMT_TEST OFF CACHE BOOL "" FORCE)
set(FMT_DOC OFF CACHE BOOL "" FORCE)

add_subdirectory(
    "${THIRD_PARTY_DIR}/fmt"
    "${CMAKE_CURRENT_BINARY_DIR}/fmt"
)

# ----- LZ4 -----

set(LZ4_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(LZ4_BUILD_LEGACY_LZ4C OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)

add_subdirectory(
    "${THIRD_PARTY_DIR}/lz4/build/cmake"
    "${CMAKE_CURRENT_BINARY_DIR}/lz4"
)

# ----- WearTAK native tunnel -----

add_library(wearvpn SHARED
    "${TUNNEL_DIR}/NativeTunnelSession.cpp"
    "${TUNNEL_DIR}/OpenVpnTransport.cpp"
    "${TUNNEL_DIR}/WearOpenVpnClient.cpp"
    "${TUNNEL_DIR}/LwipPort.cpp"

    "${THIRD_PARTY_DIR}/openvpn3/client/ovpncli.cpp"
    "${THIRD_PARTY_DIR}/openvpn3/openvpn/crypto/data_epoch.cpp"

    ${LWIP_SOURCES}
)

# Put our lwipopts.h ahead of dependency include directories.
target_include_directories(wearvpn BEFORE PRIVATE
    "${TUNNEL_DIR}"
)

target_include_directories(wearvpn PRIVATE
    "${LWIP_DIR}/src/include"
    "${LWIP_DIR}/contrib/ports/unix/port/include"
    "${THIRD_PARTY_DIR}/asio/include"
    "${THIRD_PARTY_DIR}/openvpn3"
    "${THIRD_PARTY_DIR}/mbedtls/include"
    "${THIRD_PARTY_DIR}/fmt/include"
    "${THIRD_PARTY_DIR}/lz4/lib"
)

target_compile_definitions(wearvpn PRIVATE
    WEARVPN_ENABLE_OPENVPN3_CLIENT
    ASIO_STANDALONE
    USE_ASIO
    USE_MBEDTLS
    OPENVPN_CORE_API_VISIBILITY_HIDDEN
)


target_link_libraries(wearvpn PRIVATE
    mbedtls
    mbedx509
    mbedcrypto
    fmt::fmt
    lz4_static
)

if(ANDROID)
    # JNI is Android-only; preserve the Linux session build without JNI headers.
    target_sources(wearvpn PRIVATE
        "${TUNNEL_DIR}/OpenVpnJni.cpp"
    )

    find_library(ANDROID_LOG_LIBRARY log REQUIRED)
    target_link_libraries(wearvpn PRIVATE
        ${ANDROID_LOG_LIBRARY}
    )
endif()

include(CTest)
if(BUILD_TESTING AND NOT ANDROID AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
    find_package(Threads REQUIRED)

    add_executable(openvpn_transport_tests
        "${CMAKE_CURRENT_SOURCE_DIR}/../../test/cpp/OpenVpnTransportTest.cpp"
    )
    target_include_directories(openvpn_transport_tests PRIVATE "${TUNNEL_DIR}")
    target_link_libraries(openvpn_transport_tests PRIVATE wearvpn Threads::Threads)
    add_test(NAME openvpn_transport_tests COMMAND openvpn_transport_tests)

    add_executable(inbound_buffer_tests
        "${CMAKE_CURRENT_SOURCE_DIR}/../../test/cpp/InboundBufferTest.cpp"
    )
    target_include_directories(inbound_buffer_tests PRIVATE "${TUNNEL_DIR}")
    add_test(NAME inbound_buffer_tests COMMAND inbound_buffer_tests)

    add_executable(native_tunnel_session_tests
        "${CMAKE_CURRENT_SOURCE_DIR}/../../test/cpp/NativeTunnelSessionTest.cpp"
    )
    target_include_directories(native_tunnel_session_tests PRIVATE "${TUNNEL_DIR}")
    target_link_libraries(native_tunnel_session_tests PRIVATE wearvpn Threads::Threads)
    add_test(NAME native_tunnel_session_tests COMMAND native_tunnel_session_tests)
endif()
