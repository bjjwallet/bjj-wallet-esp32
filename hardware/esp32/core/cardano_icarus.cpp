// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/cardano_icarus.hpp"
#include "core/secret.hpp"
#include <TrustWalletCore/TWDerivationPathIndex.h>
#include <TrezorCrypto/cardano.h>
#include <array>
#include <cstring>
#include <limits>
#include <memory>

namespace airlock {
namespace {
template <class T> struct Cleared {
    T value{};
    ~Cleared() {
        wipe(&value, sizeof(value));
    }
};
struct DataDelete {
    void operator()(TWData* data) const {
        if (data) {
            wipe(TWDataBytes(data), TWDataSize(data));
            TWDataDelete(data);
        }
    }
};
using Data = std::unique_ptr<TWData, DataDelete>;
} // namespace

TWPrivateKey* cardano_icarus_key(TWHDWallet* wallet, std::string_view passphrase, TWString* path) {
    if (!wallet || !path ||
        passphrase.size() > static_cast<std::size_t>(std::numeric_limits<int>::max()))
        return nullptr;
    std::unique_ptr<TWDerivationPath, decltype(&TWDerivationPathDelete)> parsed(
        TWDerivationPathCreateWithString(path), TWDerivationPathDelete);
    if (!parsed || TWDerivationPathIndicesCount(parsed.get()) != 5)
        return nullptr;
    std::array<std::uint32_t, 5> indices{};
    constexpr std::uint32_t hardened = 0x80000000U;
    for (std::uint32_t i = 0; i < indices.size(); ++i) {
        std::unique_ptr<TWDerivationPathIndex, decltype(&TWDerivationPathIndexDelete)> index(
            TWDerivationPathIndexAt(parsed.get(), i), TWDerivationPathIndexDelete);
        if (!index || TWDerivationPathIndexHardened(index.get()) != (i < 3))
            return nullptr;
        const auto value = TWDerivationPathIndexValue(index.get());
        if (value >= hardened)
            return nullptr;
        indices[i] = value | (i < 3 ? hardened : 0U);
    }
    if (indices[0] != (1852U | hardened) || indices[1] != (1815U | hardened) || indices[3] > 1U)
        return nullptr;
    Data entropy(TWHDWalletEntropy(wallet));
    if (!entropy || TWDataSize(entropy.get()) < 16 || TWDataSize(entropy.get()) > 32)
        return nullptr;
    Cleared<std::array<std::uint8_t, CARDANO_SECRET_LENGTH>> secret;
    const auto* pass =
        reinterpret_cast<const std::uint8_t*>(passphrase.empty() ? "" : passphrase.data());
    if (!secret_from_entropy_cardano_icarus(
            pass, static_cast<int>(passphrase.size()), TWDataBytes(entropy.get()),
            static_cast<int>(TWDataSize(entropy.get())), secret.value.data(), nullptr))
        return nullptr;
    Cleared<HDNode> payment, staking;
    if (!hdnode_from_secret_cardano(secret.value.data(), &payment.value))
        return nullptr;
    for (std::size_t i = 0; i < 3; ++i)
        if (!hdnode_private_ckd_cardano(&payment.value, indices[i]))
            return nullptr;
    staking.value = payment.value;
    if (!hdnode_private_ckd_cardano(&payment.value, indices[3]) ||
        !hdnode_private_ckd_cardano(&payment.value, indices[4]) ||
        !hdnode_private_ckd_cardano(&staking.value, 2) ||
        !hdnode_private_ckd_cardano(&staking.value, 0))
        return nullptr;
    // Wallet Core's Cardano key format: payment (scalar, extension, chain code),
    // then staking (scalar, extension, chain code). Never persist private keys.
    Cleared<std::array<std::uint8_t, 192>> bytes;
    for (std::size_t i = 0; i < 2; ++i) {
        const auto& node = i == 0 ? payment.value : staking.value;
        std::memcpy(bytes.value.data() + i * 96, node.private_key, 32);
        std::memcpy(bytes.value.data() + i * 96 + 32, node.private_key_extension, 32);
        std::memcpy(bytes.value.data() + i * 96 + 64, node.chain_code, 32);
    }
    Data encoded(TWDataCreateWithBytes(bytes.value.data(), bytes.value.size()));
    return encoded ? TWPrivateKeyCreateWithData(encoded.get()) : nullptr;
}
} // namespace airlock
