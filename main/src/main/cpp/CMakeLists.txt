cmake_minimum_required(VERSION 3.4.1)
project("ics-openvpn")
# Git version string

include(GetGitRevisionDescription.cmake)
git_describe(OPENVPN2_GIT "${CMAKE_CURRENT_SOURCE_DIR}/openvpn" "--tags" "--always" "--long")
git_describe(OPENVPN3_GIT "${CMAKE_CURRENT_SOURCE_DIR}/openvpn3" "--tags" "--always" "--long")
message("OpenVPN 2.x version ${OPENVPN2_GIT}")
message("OpenVPN 3.x version ${OPENVPN3_GIT}")

# Make builds (like F-droid) happy that want reproducible builds
add_link_options("LINKER:--build-id=none")

# Set mbedtls options
OPTION(ENABLE_PROGRAMS "" OFF)
OPTION(USE_SHARED_MBEDTLS_LIBRARY "" OFF)
OPTION(ENABLE_TESTING "" OFF)

# Own options
OPTION(OPENVPN3OSSL "Use OpenSSL for OpenVPN3" ON)
SET(OPENVPN3OSSL ON)

# STATIC or SHARED
SET(SSLLIBTYPE STATIC)
SET(OPENSSL_PATH "openssl")

set(CMAKE_CXX_STANDARD 23)


#add_subdirectory(lzo)
include(tools.cmake)
include(lzo.cmake)
include(lz4.cmake)
include(openssl/openssl.cmake)
add_subdirectory(fmt)

if(NOT ${OPENVPN3OSSL})
    add_subdirectory(mbedtls)
endif()

    # Super hacky way to determine flavour
#-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=/Users/arne/software/icsopenvpn/main/build/intermediates/cmake/normal/debug/obj/arm64-v8a
if (NOT ${CMAKE_LIBRARY_OUTPUT_DIRECTORY} MATCHES "build/intermediates/cmake/.*skeleton.*/")
    FIND_PACKAGE(SWIG 3.0 REQUIRED)

    add_custom_command(OUTPUT "ovpncli_wrap.cxx"
            COMMAND ${CMAKE_COMMAND} -E make_directory ovpn3
            COMMAND ${SWIG_EXECUTABLE} -outdir ovpn3
            -c++
            -java -package net.openvpn.ovpn3
            -outcurrentdir
            -DOPENVPN_PLATFORM_ANDROID
            -I${CMAKE_SOURCE_DIR}/openvpn3/client
            -I${CMAKE_SOURCE_DIR}/openvpn3
            ${CMAKE_SOURCE_DIR}/openvpn3/client/ovpncli.i)


    # proper way bunt unfinished
    #INCLUDE(${SWIG_USE_FILE})
    #SET_SOURCE_FILES_PROPERTIES(openvpn3/javacli/ovpncli.i PROPERTIES SWIG_FLAGS "-package net.openvpn.ovpn3")
    #SET_SOURCE_FILES_PROPERTIES(openvpn3/javacli/ovpncli.i PROPERTIES CPLUSPLUS ON)
    #SWIG_ADD_MODULE(ovpen3cli java openvpn3/javacli/ovpncli.i)


    set(ovpn3_SRCS
            openvpn3/client/ovpncli.cpp
            openvpn3/openvpn/openssl/xkey/xkey_provider.c
            openvpn3/openvpn/openssl/xkey/xkey_helper.c
            openvpn3/openvpn/crypto/data_epoch.cpp
            ovpncli_wrap.cxx)

    add_library(ovpn3 SHARED ${ovpn3_SRCS})

    target_include_directories(ovpn3 PUBLIC
            ${CMAKE_CURRENT_SOURCE_DIR}/lzo/include
            ${CMAKE_CURRENT_SOURCE_DIR}/openvpn3
            ${CMAKE_CURRENT_SOURCE_DIR}/asio/asio/include
            ${CMAKE_CURRENT_SOURCE_DIR}/openvpn3/client
            ${CMAKE_CURRENT_SOURCE_DIR}/mbedtls/include
            )

    if (${OPENVPN3OSSL})
        target_compile_definitions(ovpn3 PRIVATE
                -DUSE_OPENSSL
                )
        target_link_libraries(ovpn3 crypto ssl lzo lz4 fmt)
    else ()
        target_compile_definitions(ovpn3 PRIVATE
                -DUSE_MBEDTLS
                )
        target_link_libraries(ovpn3 mbedtls mbedx509 mbedcrypto lzo lz4 fmt)
    endif ()

    target_compile_definitions(ovpn3 PRIVATE
            -DHAVE_CONFIG_H
            -DHAVE_LZO
            -DHAVE_LZ4
            -DASIO_STANDALONE
            -DUSE_ASIO
            -DOPENVPN_CORE_GIT_VERSION=\"${OPENVPN3_GIT}\"
            -DOPENVPN_SHOW_SESSION_TOKEN
            -DOPENSSL_API_COMPAT=0x10200000L
            -DOPENVPN_ALLOW_INSECURE_CERTPROFILE
             -DENABLE_EXTERNAL_PKI
            )
else ()
    message("Not budiling OpenVPN for output dir ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif ()

add_library(ovpnutil SHARED ovpnutil/jniglue.c)
target_compile_definitions(ovpnutil PRIVATE -DTARGET_ARCH_ABI=\"${ANDROID_ABI}\"
        -DOPENVPN2_GIT_REVISION=\"${OPENVPN2_GIT}\"
        -DOPENVPN3_GIT_REVISION=\"${OPENVPN3_GIT}\"
        )
target_link_libraries(ovpnutil log)

add_library(osslutil SHARED ovpnutil/osslutil.cpp)
target_link_libraries(osslutil log crypto ssl)

if (NOT ${CMAKE_LIBRARY_OUTPUT_DIRECTORY} MATCHES "build/intermediates/cmake/.*skeleton.*/")
    add_library(osslspeedtest SHARED ovpnutil/sslspeed.c)
    target_link_libraries(osslspeedtest log crypto ssl)
else ()
    message("Not budiling SSLSpeedTest for output dir ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif ()
