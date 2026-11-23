// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/setup_vault.hpp"
#include <algorithm>
#include <cstdlib>
#include <iostream>

using namespace airlock;
namespace {
unsigned checks = 0;
#define CHECK(value)                                                                               \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(value)) {                                                                            \
            std::cerr << "Setup vault check failed: line " << __LINE__ << '\n';                    \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
class FixtureRandom final : public crypto::RandomSource {
  public:
    unsigned counter = 0;
    bool fail = false;
    bool fill(crypto::Output output) override {
        if (fail)
            return false;
        for (std::size_t i = 0; i < output.size; ++i)
            output.data[i] = static_cast<std::uint8_t>(++counter);
        return true;
    }
};
class MemoryStore final : public SetupStore {
  public:
    std::vector<std::uint8_t> data;
    bool fail = false, read_error = false;
    StoreRead read(std::vector<std::uint8_t>& output) override {
        output = data;
        return read_error ? StoreRead::Error : data.empty() ? StoreRead::Missing : StoreRead::Found;
    }
    bool replace(crypto::Bytes expected, crypto::Bytes input) override {
        if (fail || expected.size != data.size() ||
            !std::equal(data.begin(), data.end(), expected.data))
            return false;
        data.assign(input.data, input.data + input.size);
        return true;
    }
    bool commit_if_empty(crypto::Bytes input) override {
        if (fail || !data.empty())
            return false;
        data.assign(input.data, input.data + input.size);
        return true;
    }
};

void mandatory_pin() {
    MemoryStore store;
    FixtureRandom random;
    SetupVault vault(store, random);
    SetupRequest request;
    request.name = "Access fixture";
    request.imported = true;
    request.mnemonic.assign("abandon abandon abandon abandon abandon abandon abandon abandon "
                            "abandon abandon abandon about");
    request.transaction_phrase.assign("Public Access Phrase");
    SetupCandidate candidate;
    CHECK(vault.prepare(request, candidate) == VaultError::InvalidData);
    request.mobile_access = MobileAccess::Pin;
    for (const auto* pin : {"123456", "abcdef", "ABC123", "abc123", "Aa123", "Aa123!"}) {
        request.pin.assign(pin);
        CHECK(vault.prepare(request, candidate) == VaultError::InvalidData);
    }
    request.pin.assign("0Ab123");
    CHECK(vault.prepare(request, candidate) == VaultError::None);
    CHECK(vault.commit(candidate.record) == VaultError::None);
    WalletProfile profile;
    CHECK(vault.inspect(profile) == VaultError::None && !profile.pin_policy_current);
    UnlockSession local, remote;
    std::uint64_t retry = 0;
    CHECK(vault.device_unlock("0Ab123", 0, local, retry) == VaultError::None);
    CHECK(vault.inspect(profile) == VaultError::None && profile.pin_policy_current &&
          profile.mobile_access == MobileAccess::Pin);
    SetupVault reboot(store, random);
    CHECK(reboot.device_unlock("0Ab123", 1, remote, retry, true) == VaultError::None);
    CHECK(local.pin_key.value == remote.pin_key.value);
    const auto saved = store.data;
    CHECK(reboot.change_pin(local, "", MobileAccess::DeviceApproval) == VaultError::InvalidData);
    CHECK(reboot.change_pin(local, "123456", MobileAccess::Pin) == VaultError::InvalidData);
    CHECK(reboot.change_pin(local, "Aa1234", MobileAccess::DeviceApproval) ==
          VaultError::InvalidData);
    CHECK(store.data == saved);
    store.fail = true;
    CHECK(reboot.change_pin(local, "Bb1234", MobileAccess::Pin) == VaultError::Storage);
    CHECK(store.data == saved);
    store.fail = false;
    CHECK(reboot.change_pin(local, "Bb1234", MobileAccess::Pin) == VaultError::None);
    SetupVault changed(store, random);
    CHECK(changed.device_unlock("Bb1234", 2, remote, retry, true) == VaultError::None);
    crypto::Entropy entropy;
    Credential pass;
    CHECK(changed.recover(remote, "Public Access Phrase", entropy, pass) == VaultError::None);
    CHECK(entropy.size == 16);
}
} // namespace
int main() {
    mandatory_pin();
    std::cout << checks << " mandatory PIN vault checks passed\n";
}
