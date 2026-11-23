include("${CMAKE_CURRENT_LIST_DIR}/../../cmake/trezor_crypto.cmake")
idf_component_register(SRCS ${AIRLOCK_TREZOR_SOURCES}
    INCLUDE_DIRS "${AIRLOCK_TREZOR_ROOT}/include")
target_compile_definitions(${COMPONENT_LIB} PUBLIC ${AIRLOCK_TREZOR_DEFINITIONS})
# Preserve upstream constant-time volatile annotations; SDK C++23 warns about
# these C declarations. Treat third-party headers as system headers, unchanged.
target_include_directories(${COMPONENT_LIB} SYSTEM PUBLIC "${AIRLOCK_TREZOR_ROOT}/include")
