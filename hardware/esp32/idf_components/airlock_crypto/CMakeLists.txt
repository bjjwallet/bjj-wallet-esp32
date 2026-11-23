set(MONERO_CRYPTO "../../vendor/monero-0.18.5.4")
idf_component_register(SRCS "../../core/ton_signer.cpp" "../../core/sui_signer.cpp" "../../core/xrp_signer.cpp" "../../core/tron_signer.cpp" "../../core/ledger_signer.cpp" "../../core/brz_signer.cpp" "../../core/solana_signer.cpp" "../../core/cardano_signer.cpp" "../../core/utxo_signer.cpp" "../../core/crypto_mbedtls.cpp" "../../core/crypto_selftest.cpp"
    "../../core/crypto_cooperative.cpp" "../../core/hd_wallet.cpp" "../../core/monero_address.cpp" "../../core/network_accounts.cpp" "../../core/trezor_random.cpp" "../../core/hd_selftest.cpp" "../../core/setup_vault.cpp" "../../core/setup_public.cpp" "random.cpp"
    "../../core/monero_archive_crypto.cpp" "${MONERO_CRYPTO}/aesb.c" "${MONERO_CRYPTO}/groestl.c"
    "${MONERO_CRYPTO}/jh.c" "${MONERO_CRYPTO}/skein.c"
    INCLUDE_DIRS "../.." "include" PRIV_INCLUDE_DIRS "${MONERO_CRYPTO}"
    REQUIRES mbedtls PRIV_REQUIRES airlock_core airlock_trezor json bootloader_support esp_hw_support bt)
set_source_files_properties("${MONERO_CRYPTO}/aesb.c" PROPERTIES COMPILE_DEFINITIONS "t_fn=airlock_monero_t_fn")
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Werror
    "$<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions;-fno-rtti>")

if(AIRLOCK_MONERO_SIGNER_ARCHIVE)
    if(NOT EXISTS "${AIRLOCK_MONERO_SIGNER_ARCHIVE}")
        message(FATAL_ERROR "AIRLOCK_MONERO_SIGNER_ARCHIVE must be an Xtensa ESP32-S3 static library")
    endif()
    target_sources(${COMPONENT_LIB} PRIVATE "monero_signer_memory.cpp")
    target_compile_definitions(${COMPONENT_LIB} PRIVATE AIRLOCK_MONERO_TRANSFER_SIGNER=1)
    target_link_libraries(${COMPONENT_LIB} PRIVATE "${AIRLOCK_MONERO_SIGNER_ARCHIVE}")
endif()
