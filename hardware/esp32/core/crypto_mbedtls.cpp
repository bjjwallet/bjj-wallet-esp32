// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/crypto.hpp"
#include "core/config.hpp"
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include <mbedtls/pkcs5.h>
#include <mbedtls/sha256.h>

namespace airlock::crypto {
namespace {
const std::uint8_t empty = 0;
const std::uint8_t* readable(Bytes input) {
    return input.size ? input.data : &empty;
}
bool valid(Bytes input) {
    return input.data || input.size == 0;
}
bool overlap(Bytes input, Output output) {
    if (!input.size || !output.size)
        return false;
    const auto a = reinterpret_cast<std::uintptr_t>(input.data);
    const auto b = reinterpret_cast<std::uintptr_t>(output.data);
    return a <= b ? b - a < input.size : a - b < output.size;
}
void clear(Output output) {
    if (output.data)
        wipe(output.data, output.size);
}
bool kdf(mbedtls_md_type_t hash, Bytes password, Bytes salt, std::uint32_t iterations,
         Output output) {
    // Bound untrusted work. Production keystores use precisely 100,000 rounds.
    if (!valid(password) || !valid(salt) || password.size > 1024 || salt.size > 1032 ||
        iterations == 0 || iterations > 1000000 || overlap(password, output) ||
        overlap(salt, output)) {
        clear(output);
        return false;
    }
    const auto status = mbedtls_pkcs5_pbkdf2_hmac_ext(
        hash, readable(password), password.size, readable(salt), salt.size, iterations,
        static_cast<std::uint32_t>(output.size), output.data);
    if (status != 0)
        clear(output);
    return status == 0;
}
bool buffers(Bytes input, Output output) {
    return valid(input) && output.data && input.size == output.size &&
           input.size <= max_plaintext && !overlap(input, output);
}
} // namespace
bool sha256(Bytes input, std::array<std::uint8_t, 32>& digest) {
    if (!valid(input) || overlap(input, {digest.data(), digest.size()})) {
        wipe(digest.data(), digest.size());
        return false;
    }
    const auto status = mbedtls_sha256(readable(input), input.size, digest.data(), 0);
    if (status != 0)
        wipe(digest.data(), digest.size());
    return status == 0;
}
bool pbkdf2_sha256(Bytes password, Bytes salt, std::uint32_t iterations, Key& key) {
    return kdf(MBEDTLS_MD_SHA256, password, salt, iterations, {key.value.data(), key.value.size()});
}
bool pbkdf2_sha512(Bytes password, Bytes salt, std::uint32_t iterations, Seed& seed) {
    return kdf(MBEDTLS_MD_SHA512, password, salt, iterations,
               {seed.value.data(), seed.value.size()});
}
bool keystore_key(Bytes password, const Salt& salt, Key& key) {
    return pbkdf2_sha256(password, {salt.data(), salt.size()}, config::keystore_iterations, key);
}
bool seal(const Key& key, const Nonce& nonce, Bytes plaintext, Output ciphertext, Tag& tag,
          Bytes aad) {
    if (!buffers(plaintext, ciphertext) || !valid(aad) || aad.size > 1024 ||
        overlap(aad, ciphertext)) {
        clear(ciphertext);
        wipe(tag.data(), tag.size());
        return false;
    }
    mbedtls_gcm_context context;
    mbedtls_gcm_init(&context);
    auto status = mbedtls_gcm_setkey(&context, MBEDTLS_CIPHER_ID_AES, key.value.data(), 256);
    if (status == 0)
        status = mbedtls_gcm_crypt_and_tag(
            &context, MBEDTLS_GCM_ENCRYPT, plaintext.size, nonce.data(), nonce.size(),
            readable(aad), aad.size, readable(plaintext), ciphertext.data, tag.size(), tag.data());
    mbedtls_gcm_free(&context);
    if (status != 0) {
        clear(ciphertext);
        wipe(tag.data(), tag.size());
    }
    return status == 0;
}
bool open(const Key& key, const Nonce& nonce, Bytes ciphertext, const Tag& tag, Output plaintext,
          Bytes aad) {
    if (!buffers(ciphertext, plaintext) || !valid(aad) || aad.size > 1024 ||
        overlap(aad, plaintext)) {
        clear(plaintext);
        return false;
    }
    mbedtls_gcm_context context;
    mbedtls_gcm_init(&context);
    auto status = mbedtls_gcm_setkey(&context, MBEDTLS_CIPHER_ID_AES, key.value.data(), 256);
    if (status == 0)
        status = mbedtls_gcm_auth_decrypt(&context, ciphertext.size, nonce.data(), nonce.size(),
                                          readable(aad), aad.size, tag.data(), tag.size(),
                                          readable(ciphertext), plaintext.data);
    mbedtls_gcm_free(&context);
    if (status != 0)
        clear(plaintext);
    return status == 0;
}
} // namespace airlock::crypto
