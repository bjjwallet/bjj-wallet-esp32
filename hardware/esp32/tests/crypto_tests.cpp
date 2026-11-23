// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/crypto.hpp"
#include <openssl/evp.h>
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace c = airlock::crypto;
namespace {
unsigned checks = 0;
#define CHECK(value)                                                                               \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(value)) {                                                                            \
            std::cerr << "Crypto check failed: line " << __LINE__ << '\n';                         \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
bool zero(const std::vector<std::uint8_t>& data) {
    return std::all_of(data.begin(), data.end(), [](auto byte) { return byte == 0; });
}
void interoperability() {
    // Same 100,000-round PBKDF2-SHA256 and salt/nonce/tag sizes as native_wallet.
    constexpr std::string_view password = u8"Public-é-Test1!";
    c::Salt salt{};
    c::Nonce nonce{};
    for (std::size_t i = 0; i < salt.size(); ++i)
        salt[i] = static_cast<std::uint8_t>(i + 1);
    for (std::size_t i = 0; i < nonce.size(); ++i)
        nonce[i] = static_cast<std::uint8_t>(i + 17);
    c::Key key, reference;
    CHECK(c::keystore_key(c::bytes(password), salt, key));
    CHECK(PKCS5_PBKDF2_HMAC(password.data(), static_cast<int>(password.size()), salt.data(),
                            salt.size(), 100000, EVP_sha256(), reference.value.size(),
                            reference.value.data()) == 1);
    CHECK(key.value == reference.value);
    for (std::size_t size : {0U, 1U, 15U, 16U, 17U, 257U, 8192U}) {
        // Extra byte supplies a valid pointer even when the authenticated message is empty.
        std::vector<std::uint8_t> plain(size + 1), cipher(size + 1), expected(size + 16),
            recovered(size + 1, 0xa5);
        for (std::size_t i = 0; i < size; ++i)
            plain[i] = static_cast<std::uint8_t>(i * 37);
        c::Tag tag{}, expected_tag{};
        CHECK(c::seal(key, nonce, {plain.data(), size}, {cipher.data(), size}, tag));
        auto* context = EVP_CIPHER_CTX_new();
        CHECK(context);
        int written = 0, tail = 0;
        CHECK(EVP_EncryptInit_ex(context, EVP_aes_256_gcm(), nullptr, reference.value.data(),
                                 nonce.data()) == 1);
        CHECK(EVP_EncryptUpdate(context, expected.data(), &written, plain.data(),
                                static_cast<int>(size)) == 1);
        CHECK(EVP_EncryptFinal_ex(context, expected.data() + written, &tail) == 1);
        CHECK(static_cast<std::size_t>(written + tail) == size);
        CHECK(EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_GET_TAG, expected_tag.size(),
                                  expected_tag.data()) == 1);
        EVP_CIPHER_CTX_free(context);
        CHECK(std::equal(cipher.begin(), cipher.begin() + size, expected.begin()));
        CHECK(tag == expected_tag);
        CHECK(c::open(key, nonce, {expected.data(), size}, expected_tag, {recovered.data(), size}));
        CHECK(std::equal(recovered.begin(), recovered.begin() + size, plain.begin()));
        // Independently corrupt key, nonce, tag and ciphertext. The sentinel after
        // the output must survive; the entire plaintext region must be erased.
        for (unsigned field = 0; field < 4; ++field) {
            if (field == 3 && size == 0)
                continue;
            recovered.assign(size + 1, 0xa5);
            if (field == 0)
                key.value[0] ^= 1;
            if (field == 1)
                nonce[0] ^= 1;
            if (field == 2)
                tag[0] ^= 1;
            if (field == 3)
                cipher[0] ^= 1;
            CHECK(!c::open(key, nonce, {cipher.data(), size}, tag, {recovered.data(), size}));
            CHECK(recovered.back() == 0xa5);
            recovered.pop_back();
            CHECK(zero(recovered));
            if (field == 0)
                key.value[0] ^= 1;
            if (field == 1)
                nonce[0] ^= 1;
            if (field == 2)
                tag[0] ^= 1;
            if (field == 3)
                cipher[0] ^= 1;
        }
        // OpenSSL also authenticates a message encrypted by the board backend.
        context = EVP_CIPHER_CTX_new();
        CHECK(context);
        CHECK(EVP_DecryptInit_ex(context, EVP_aes_256_gcm(), nullptr, reference.value.data(),
                                 nonce.data()) == 1);
        recovered.resize(size + 16);
        CHECK(EVP_DecryptUpdate(context, recovered.data(), &written, cipher.data(),
                                static_cast<int>(size)) == 1);
        CHECK(EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG, tag.size(), tag.data()) == 1);
        CHECK(EVP_DecryptFinal_ex(context, recovered.data() + written, &tail) == 1);
        CHECK(std::equal(recovered.begin(), recovered.begin() + size, plain.begin()));
        EVP_CIPHER_CTX_free(context);
        ++nonce[0]; // Never reuse a nonce under this key for different messages.
    }
}
void invalid_inputs() {
    c::Key key;
    c::Seed seed;
    c::Nonce nonce{};
    c::Tag tag{};
    std::array<std::uint8_t, 32> digest{};
    CHECK(!c::sha256({nullptr, 1}, digest));
    CHECK(!c::pbkdf2_sha256(c::bytes("password"), c::bytes("salt"), 0, key));
    key.value.fill(0xa5);
    CHECK(!c::pbkdf2_sha256({nullptr, 1}, c::bytes("salt"), 1, key));
    CHECK(std::all_of(key.value.begin(), key.value.end(), [](auto byte) { return byte == 0; }));
    CHECK(!c::pbkdf2_sha512(c::bytes("password"), c::bytes("salt"), 1000001, seed));
    CHECK(!c::pbkdf2_sha256(c::bytes(std::string(1025, 'a')), c::bytes("salt"), 1, key));
    CHECK(c::pbkdf2_sha256({nullptr, 0}, {nullptr, 0}, 1, key));
    std::vector<std::uint8_t> input(8193, 1), output(8193, 0xa5);
    CHECK(!c::seal(key, nonce, {input.data(), input.size()}, {output.data(), output.size()}, tag));
    CHECK(zero(output));
    output.assign(8193, 0xa5);
    CHECK(!c::open(key, nonce, {nullptr, 1}, tag, {output.data(), 1}));
    CHECK(output[0] == 0 && output[1] == 0xa5);
    CHECK(!c::open(key, nonce, {input.data(), 16}, tag, {output.data(), 15}));
    CHECK(!c::seal(key, nonce, {input.data(), 16}, {input.data() + 1, 16}, tag));
}
} // namespace
int main() {
    CHECK(c::self_test());
    interoperability();
    invalid_inputs();
    std::cout << checks
              << " crypto checks passed: known answers, OpenSSL interoperability, bounds and "
                 "authentication failure wiping.\n";
}
