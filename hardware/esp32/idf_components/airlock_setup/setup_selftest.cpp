// SPDX-License-Identifier: GPL-3.0-or-later
// Temporary test build only. Never opens or erases the user's bjj_wallet space.
#include "airlock_setup.hpp"
#include "nvs.h"
#include "esp_log.h"
#include "freertos/task.h"
#include <algorithm>

namespace airlock {
namespace {
constexpr char test_space[] = "bjj_selftest";
bool clear_test_key() {
    nvs_handle_t handle = 0;
    if (nvs_open_from_partition("wallet_nvs", test_space, NVS_READWRITE, &handle) != ESP_OK)
        return false;
    const auto status = nvs_erase_key(handle, "wallet_v1");
    const bool ok =
        (status == ESP_OK || status == ESP_ERR_NVS_NOT_FOUND) && nvs_commit(handle) == ESP_OK;
    nvs_close(handle);
    return ok;
}
bool is_owned_fixture(SetupVault& vault) {
    UnlockSession session;
    crypto::Entropy entropy;
    Credential pass;
    return vault.unlock("345678", session) == VaultError::None &&
           vault.recover(session, "Public Transaction Phrase1", entropy, pass) ==
               VaultError::None &&
           entropy.size == 16 &&
           std::all_of(entropy.data.value.begin(), entropy.data.value.end(),
                       [](auto b) { return b == 0; }) &&
           pass.equals("Public Fixture Passphrase");
}
} // namespace
bool setup_storage_self_test(crypto::RandomSource& random) {
    NvsSetupStore store(test_space);
    SetupVault vault(store, random, [] { vTaskDelay(pdMS_TO_TICKS(1)); });
    WalletProfile profile;
    const auto existing = vault.inspect(profile);
    if (existing == VaultError::None) {
        if (profile.name != "Bjj public selftest" || !is_owned_fixture(vault) || !clear_test_key())
            return false;
    } else if (existing != VaultError::Missing)
        return false;
    auto request = std::make_unique<SetupRequest>();
    request->name = "Bjj public selftest";
    request->imported = true;
    request->mobile_access = MobileAccess::Pin;
    request->mnemonic.assign("abandon abandon abandon abandon abandon abandon abandon abandon "
                             "abandon abandon abandon about");
    request->passphrase.assign("Public Fixture Passphrase");
    request->pin.assign("345678");
    request->transaction_phrase.assign("Public Transaction Phrase1");
    auto candidate = std::make_unique<SetupCandidate>();
    if (vault.prepare(*request, *candidate) != VaultError::None ||
        vault.commit(candidate->record) != VaultError::None)
        return false;
    request.reset();
    candidate.reset();
    NvsSetupStore reopened(test_space);
    SetupVault reload(reopened, random, [] { vTaskDelay(pdMS_TO_TICKS(1)); });
    if (reload.inspect(profile) != VaultError::None || profile.mobile_access != MobileAccess::Pin ||
        !profile.pin_enabled)
        return false;
    UnlockSession session;
    if (reload.unlock("999999", session) != VaultError::WrongPin || session.authorized ||
        !is_owned_fixture(reload))
        return false;
    if (!clear_test_key() || reload.inspect(profile) != VaultError::Missing)
        return false;
    ESP_LOGI("airlock_setup", "Encrypted NVS self-test passed: prepare/commit/reopen/PIN/recover; "
                              "isolated fixture removed");
    return true;
}
} // namespace airlock
