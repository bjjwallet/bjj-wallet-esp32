# Sources are unmodified Wallet Core 4.4.2 TrezorCrypto. Its POSIX rand.c is
# deliberately excluded: the adapter must provide failure-aware hardware RNG.
set(AIRLOCK_TREZOR_ROOT "${CMAKE_CURRENT_LIST_DIR}/../vendor/trezor-crypto-4.4.2")
if(Python3_EXECUTABLE)
    set(AIRLOCK_TREZOR_PYTHON "${Python3_EXECUTABLE}")
else()
    set(AIRLOCK_TREZOR_PYTHON "${PYTHON}")
endif()
execute_process(COMMAND "${AIRLOCK_TREZOR_PYTHON}" "${CMAKE_CURRENT_LIST_DIR}/../tools/check_trezor_sources.py"
    RESULT_VARIABLE AIRLOCK_TREZOR_CHECK OUTPUT_QUIET)
if(NOT AIRLOCK_TREZOR_CHECK EQUAL 0)
    message(FATAL_ERROR "Pinned TrezorCrypto provenance or BIP39 wordlist check failed")
endif()
set(AIRLOCK_TREZOR_SOURCES
    bignum.c ecdsa.c curves.c secp256k1.c nist256p1.c hmac.c bip32.c bip39.c cardano.c
    pbkdf2.c base58.c base32.c address.c ripemd160.c sha2.c sha3.c hasher.c
    blake256.c blake2b.c blake2s.c groestl.c hmac_drbg.c rfc6979.c memzero.c
    aes/aescrypt.c aes/aeskey.c aes/aestab.c aes/aes_modes.c
    ed25519-donna/curve25519-donna-32bit.c ed25519-donna/curve25519-donna-helpers.c
    ed25519-donna/modm-donna-32bit.c ed25519-donna/ed25519-donna-basepoint-table.c
    ed25519-donna/ed25519-donna-32bit-tables.c ed25519-donna/ed25519-donna-impl-base.c
    ed25519-donna/ed25519.c ed25519-donna/curve25519-donna-scalarmult-base.c
    ed25519-donna/ed25519-sha3.c ed25519-donna/ed25519-keccak.c ed25519-donna/ed25519-blake2b.c)
list(TRANSFORM AIRLOCK_TREZOR_SOURCES PREPEND "${AIRLOCK_TREZOR_ROOT}/crypto/")
set(AIRLOCK_TREZOR_DEFINITIONS USE_BIP39_CACHE=0 USE_BIP32_CACHE=0 USE_CARDANO=1)
