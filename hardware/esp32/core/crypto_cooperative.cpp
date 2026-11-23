// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/crypto.hpp"
#include "core/config.hpp"
#include <TrezorCrypto/pbkdf2.h>

namespace airlock::crypto {
bool keystore_key_cooperative(Bytes password, const Salt& salt, Key& key, void (*yield)()) {
    if ((!password.data && password.size) || password.size > 1024 || !yield) {
        wipe(key.value.data(), key.value.size());
        return false;
    }
    struct Context {
        PBKDF2_HMAC_SHA256_CTX value{};
        ~Context() {
            wipe(&value, sizeof(value));
        }
    } context;
    constexpr std::uint8_t empty = 0;
    pbkdf2_hmac_sha256_Init(&context.value, password.size ? password.data : &empty,
                            static_cast<int>(password.size), salt.data(), salt.size(), 1);
    constexpr std::uint32_t chunk = 1000;
    static_assert(config::keystore_iterations % chunk == 0);
    for (std::uint32_t processed = 0; processed < config::keystore_iterations; processed += chunk) {
        pbkdf2_hmac_sha256_Update(&context.value, chunk);
        yield();
    }
    pbkdf2_hmac_sha256_Final(&context.value, key.value.data());
    return true;
}
} // namespace airlock::crypto
