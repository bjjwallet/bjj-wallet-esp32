// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/setup_vault.hpp"
#include <algorithm>
#include <cstdlib>
#include <iostream>

using namespace airlock;
namespace {
unsigned checks = 0;
unsigned yields = 0;
void yield_counter() {
    ++yields;
}
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
void checksum(std::vector<std::uint8_t>& record) {
    std::array<std::uint8_t, 32> hash{};
    CHECK(crypto::sha256({record.data(), 444}, hash));
    std::copy(hash.begin(), hash.end(), record.begin() + 444);
}
void matrix() {
    for (auto words : {12U, 18U, 24U})
        for (bool pin : {false, true})
            for (bool pass : {false, true})
                for (bool require_mobile_pin : {false, true}) {
                    if (!pin && require_mobile_pin)
                        continue;
                    MemoryStore store;
                    FixtureRandom random;
                    SetupVault vault(store, random);
                    WalletProfile profile;
                    CHECK(vault.inspect(profile) == VaultError::Missing);
                    SetupRequest request;
                    request.pin.assign("Aa345678");
                    request.mobile_access = MobileAccess::Pin;
                    request.name = "Minha wallet";
                    request.word_count = words;
                    request.mobile_access =
                        require_mobile_pin ? MobileAccess::Pin : MobileAccess::DeviceApproval;
                    CHECK(request.transaction_phrase.assign("Public Transaction Phrase1"));
                    if (pin)
                        CHECK(request.pin.assign("Aa345678"));
                    if (pass)
                        CHECK(request.passphrase.assign(u8"Passphrase pública de teste"));
                    SetupCandidate candidate;
                    if (!pin || !require_mobile_pin) {
                        if (!pin)
                            request.pin.clear();
                        CHECK(vault.prepare(request, candidate) == VaultError::InvalidData);
                        CHECK(store.data.empty());
                        continue;
                    }
                    CHECK(vault.prepare(request, candidate) == VaultError::None);
                    CHECK(store.data.empty()); // No persistence until explicit backup/commit.
                    CHECK(!candidate.backup_words.view().empty());
                    CHECK(candidate.backup_passphrase.equals(request.passphrase.view()));
                    crypto::Entropy expected;
                    CHECK(crypto::mnemonic_entropy(candidate.backup_words.view(), expected) ==
                          crypto::HdError::None);
                    CHECK(expected.size / 4 * 3 == words);
                    CHECK(vault.commit(candidate.record) == VaultError::None);
                    CHECK(store.data.size() == wallet_record_bytes);
                    for (auto secret :
                         {candidate.backup_words.view(), request.transaction_phrase.view(),
                          request.passphrase.view()})
                        if (!secret.empty())
                            CHECK(std::search(store.data.begin(), store.data.end(), secret.begin(),
                                              secret.end()) == store.data.end());
                    SetupVault rebooted(store, random);
                    CHECK(rebooted.inspect(profile) == VaultError::None);
                    CHECK(profile.name == request.name && profile.word_count == words &&
                          profile.pin_enabled == pin && profile.passphrase_enabled == pass);
                    CHECK(profile.mobile_access == request.mobile_access);
                    UnlockSession session;
                    CHECK(rebooted.unlock(pin ? "999999" : "Aa345678", session) ==
                          VaultError::WrongPin);
                    CHECK(!session.authorized);
                    CHECK(rebooted.unlock(request.pin.view(), session) == VaultError::None);
                    crypto::Entropy recovered;
                    Credential phrase;
                    CHECK(rebooted.recover(session, "Wrong Transaction Phrase", recovered,
                                           phrase) == VaultError::WrongPhrase);
                    CHECK(recovered.size == 0 && phrase.view().empty());
                    CHECK(rebooted.recover(session, request.transaction_phrase.view(), recovered,
                                           phrase) == VaultError::None);
                    CHECK(recovered.size == expected.size &&
                          recovered.data.value == expected.data.value);
                    CHECK(phrase.equals(request.passphrase.view()));
                    CHECK(rebooted.prepare(request, candidate) == VaultError::AlreadyConfigured);
                    const auto saved = store.data;
                    // Checksum catches accidental corruption before offering login/create.
                    store.data[150] ^= 1;
                    CHECK(rebooted.inspect(profile) == VaultError::InvalidData);
                    store.data = saved;
                    // A recomputed public checksum cannot forge the authenticated name/policy.
                    store.data[11] ^= 1;
                    checksum(store.data);
                    CHECK(rebooted.recover(session, request.transaction_phrase.view(), recovered,
                                           phrase) == VaultError::WrongPhrase);
                    store.data = saved;
                    store.data[428] ^= 1;
                    checksum(store.data);
                    CHECK(rebooted.recover(session, request.transaction_phrase.view(), recovered,
                                           phrase) == VaultError::WrongPhrase);
                    CHECK(recovered.size == 0 && phrase.view().empty());
                    store.data = saved;
                    session.clear();
                    CHECK(rebooted.recover(session, request.transaction_phrase.view(), recovered,
                                           phrase) == VaultError::WrongPin);
                }
}
void imports_and_failures() {
    MemoryStore store;
    FixtureRandom random;
    SetupVault vault(store, random);
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Importada";
    request.imported = true;
    CHECK(request.mnemonic.assign("abandon abandon abandon abandon abandon abandon abandon abandon "
                                  "abandon abandon abandon about"));
    CHECK(request.transaction_phrase.assign("Public Transaction Phrase1"));
    SetupCandidate candidate;
    CHECK(vault.prepare(request, candidate) == VaultError::None);
    CHECK(candidate.backup_words.equals(request.mnemonic.view()) &&
          candidate.backup_passphrase.view().empty() && candidate.profile.imported);
    store.fail = true;
    CHECK(vault.commit(candidate.record) == VaultError::Storage);
    WalletProfile profile;
    CHECK(vault.inspect(profile) == VaultError::Missing);
    store.fail = false;
    CHECK(vault.commit(candidate.record) == VaultError::None);
    CHECK(vault.commit(candidate.record) == VaultError::AlreadyConfigured);
    store.data.clear();
    request.pin.clear();
    request.mobile_access = MobileAccess::Pin;
    CHECK(vault.prepare(request, candidate) == VaultError::InvalidData);
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.word_count = 18;
    CHECK(vault.prepare(request, candidate) == VaultError::InvalidData);
    request.word_count = 12;
    CHECK(request.mnemonic.assign("abandon abandon abandon abandon abandon abandon abandon abandon "
                                  "abandon abandon abandon abandon"));
    CHECK(vault.prepare(request, candidate) == VaultError::InvalidData);
    request.imported = false;
    request.mnemonic.clear();
    random.fail = true;
    CHECK(vault.generate_words(request) == VaultError::Random);
    CHECK(vault.prepare(request, candidate) == VaultError::Random);
    random.fail = false;
    store.read_error = true;
    CHECK(vault.prepare(request, candidate) == VaultError::Storage);
    CHECK(!valid_setup_pin("123") && !valid_setup_pin("abcd") && valid_setup_pin("0Ab123"));
    CHECK(!valid_transaction_phrase("short") && !valid_wallet_name("   "));
    store.read_error = false;
    CHECK(request.passphrase.assign(std::string(256, 'a')));
    request.imported = true;
    CHECK(request.mnemonic.assign("abandon abandon abandon abandon abandon abandon abandon abandon "
                                  "abandon abandon abandon about"));
    CHECK(request.transaction_phrase.assign(std::string(256, 'b')));
    CHECK(vault.prepare(request, candidate) == VaultError::None);
    CHECK(vault.commit(candidate.record) == VaultError::None);
    UnlockSession session;
    CHECK(vault.unlock("Aa345678", session) == VaultError::None);
    crypto::Entropy entropy;
    Credential pass;
    CHECK(vault.recover(session, request.transaction_phrase.view(), entropy, pass) ==
          VaultError::None);
    CHECK(pass.equals(request.passphrase.view()));
    store.data.clear();
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    SetupVault cooperative_vault(store, random, yield_counter);
    yields = 0;
    CHECK(cooperative_vault.prepare(request, candidate) == VaultError::None && yields == 200);
    CHECK(vault.commit(candidate.record) == VaultError::None);
    CHECK(vault.unlock("Aa345678", session) == VaultError::None);
    CHECK(vault.recover(session, request.transaction_phrase.view(), entropy, pass) ==
          VaultError::None);
    CHECK(pass.equals(request.passphrase.view()));
}
void device_credentials() {
    MemoryStore store;
    FixtureRandom random;
    SetupVault vault(store, random);
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Wallet A";
    request.pin.assign("Aa345678");
    request.transaction_phrase.assign("Public Phrase A");
    SetupCandidate candidate;
    CHECK(vault.prepare(request, candidate) == VaultError::None);
    CHECK(vault.commit(candidate.record) == VaultError::None);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(vault.device_unlock("999999", 100, session, retry) == VaultError::WrongPin &&
          retry == 30000 && !session.authorized);
    // A new vault simulates a power cycle: the pending wait restarts in full.
    SetupVault reboot(store, random);
    std::vector<WalletProfile> profiles;
    CHECK(reboot.device_status(0, profiles, retry) == VaultError::None && retry == 30000);
    CHECK(reboot.device_unlock("Aa345678", 29999, session, retry) == VaultError::Locked &&
          !session.authorized);
    CHECK(reboot.device_unlock("999999", 30000, session, retry) == VaultError::WrongPin &&
          retry == 30000);
    CHECK(reboot.device_unlock("999999", 60000, session, retry) == VaultError::WrongPin &&
          retry == 60000);
    SetupVault power_cycle(store, random);
    CHECK(power_cycle.device_status(0, profiles, retry) == VaultError::None && retry == 60000);
    CHECK(power_cycle.device_unlock("Aa345678", 60000, session, retry) == VaultError::None &&
          session.authorized);
    CHECK(power_cycle.device_unlock("999999", 60001, session, retry) == VaultError::WrongPin &&
          retry == 30000);
    CHECK(power_cycle.device_unlock("Aa345678", 90001, session, retry) == VaultError::None);
    // Add a second independent phrase under the shared device encryption key.
    SetupRequest second;
    second.name = "Wallet B";
    second.word_count = 18;
    second.transaction_phrase.assign("Public Phrase B");
    second.passphrase.assign("Public Seed Passphrase B");
    SetupCandidate extra;
    CHECK(power_cycle.prepare_additional(second, session, extra) == VaultError::None);
    CHECK(power_cycle.append(extra.record, session) == VaultError::None);
    CHECK(power_cycle.device_status(90002, profiles, retry) == VaultError::None &&
          profiles.size() == 2);
    CHECK(profiles[0].name == "Wallet A" && profiles[1].name == "Wallet B" &&
          profiles[1].pin_enabled);
    CHECK(power_cycle.verify_phrase(session, "Public Phrase A", 0) == VaultError::None);
    CHECK(power_cycle.verify_phrase(session, "Public Phrase B", 1) == VaultError::None);
    CHECK(power_cycle.verify_phrase(session, "Public Phrase A", 1) == VaultError::WrongPhrase);
    const auto saved = store.data;
    store.fail = true;
    CHECK(power_cycle.change_pin(session, "Bb654321", MobileAccess::Pin) == VaultError::Storage);
    CHECK(store.data == saved);
    store.fail = false;
    CHECK(power_cycle.change_pin(session, "Bb654321", MobileAccess::Pin) == VaultError::None);
    SetupVault changed(store, random);
    CHECK(changed.device_unlock("Aa345678", 0, session, retry) == VaultError::WrongPin);
    CHECK(changed.device_unlock("Bb654321", 30000, session, retry) == VaultError::None);
    UnlockSession legacy_session;
    CHECK(changed.unlock("Aa345678", legacy_session) == VaultError::WrongPin &&
          !legacy_session.authorized);
    CHECK(changed.verify_phrase(session, "Public Phrase A", 0) == VaultError::None);
    CHECK(changed.verify_phrase(session, "Public Phrase B", 1) == VaultError::None);
    // Rotating B's phrase must preserve A and B's actual seed/passphrase.
    CHECK(changed.change_phrase(session, "Wrong old phrase", "Public New Phrase B", 1) ==
          VaultError::WrongPhrase);
    crypto::Entropy expected_b, recovered_b;
    Credential recovered_pass;
    CHECK(crypto::mnemonic_entropy(extra.backup_words.view(), expected_b) == crypto::HdError::None);
    CHECK(changed.change_phrase(session, "Public Phrase B", "Public New Phrase B", 1) ==
          VaultError::None);
    CHECK(changed.recover(session, "Public New Phrase B", recovered_b, recovered_pass, 1) ==
          VaultError::None);
    CHECK(recovered_b.size == expected_b.size && recovered_b.data.value == expected_b.data.value);
    CHECK(recovered_pass.equals("Public Seed Passphrase B"));
    CHECK(changed.verify_phrase(session, "Public Phrase B", 1) == VaultError::WrongPhrase);
    CHECK(changed.verify_phrase(session, "Public New Phrase B", 1) == VaultError::None);
    CHECK(changed.verify_phrase(session, "Public Phrase A", 0) == VaultError::None);
    CHECK(changed.change_pin(session, "", MobileAccess::DeviceApproval) == VaultError::InvalidData);
    SetupVault no_pin(store, random);
    CHECK(no_pin.device_unlock("Bb654321", 0, session, retry) == VaultError::None);
    CHECK(no_pin.device_status(1, profiles, retry) == VaultError::None && profiles[0].pin_enabled &&
          profiles[1].pin_enabled);
    CHECK(no_pin.verify_phrase(session, "Public New Phrase B", 1) == VaultError::None);
    // Failed PIN removal preserves access and phrase rotation across reboot.
    CHECK(no_pin.change_phrase(session, "Public Phrase A", "Public New Phrase A", 0) ==
          VaultError::None);
    SetupVault final_boot(store, random);
    CHECK(final_boot.device_unlock("Bb654321", 0, session, retry) == VaultError::None);
    CHECK(final_boot.verify_phrase(session, "Public New Phrase A", 0) == VaultError::None);
    CHECK(final_boot.change_pin(session, "Cc112233", MobileAccess::Pin) == VaultError::None);
    SetupVault pin_added(store, random);
    CHECK(pin_added.device_unlock("Cc112233", 0, session, retry) == VaultError::None);
    CHECK(pin_added.verify_phrase(session, "Public New Phrase A", 0) == VaultError::None);
    CHECK(pin_added.verify_phrase(session, "Public New Phrase B", 1) == VaultError::None);
    // Applying one phrase to all wallets is one atomic publication.
    crypto::Entropy before_a, before_b, after_a, after_b;
    Credential pass_a, pass_b, after_pass;
    CHECK(pin_added.recover(session, "Public New Phrase A", before_a, pass_a, 0) ==
          VaultError::None);
    CHECK(pin_added.recover(session, "Public New Phrase B", before_b, pass_b, 1) ==
          VaultError::None);
    WalletPhrases proofs;
    auto a = std::make_unique<WalletPhrase>();
    a->index = 0;
    a->current.assign("Public New Phrase A");
    auto b = std::make_unique<WalletPhrase>();
    b->index = 1;
    b->current.assign("Wrong but valid phrase");
    proofs.push_back(std::move(a));
    proofs.push_back(std::move(b));
    const auto before_batch = store.data;
    CHECK(pin_added.change_phrases(session, proofs, "Shared Public Phrase") ==
          VaultError::WrongPhrase);
    CHECK(store.data == before_batch);
    proofs[1]->current.assign("Public New Phrase B");
    proofs[1]->index = 0;
    CHECK(pin_added.change_phrases(session, proofs, "Shared Public Phrase") ==
              VaultError::InvalidData &&
          store.data == before_batch);
    proofs[1]->index = 1;
    UnlockSession unauthorized;
    CHECK(pin_added.change_phrases(unauthorized, proofs, "Shared Public Phrase") ==
          VaultError::WrongPin);
    auto missing = std::move(proofs.back());
    proofs.pop_back();
    CHECK(pin_added.change_phrases(session, proofs, "Shared Public Phrase") ==
          VaultError::InvalidData);
    proofs.push_back(std::move(missing));
    random.fail = true;
    CHECK(pin_added.change_phrases(session, proofs, "Shared Public Phrase") == VaultError::Random &&
          store.data == before_batch);
    random.fail = false;
    store.fail = true;
    CHECK(pin_added.change_phrases(session, proofs, "Shared Public Phrase") ==
              VaultError::Storage &&
          store.data == before_batch);
    store.fail = false;
    CHECK(pin_added.change_phrases(session, proofs, "Shared Public Phrase") == VaultError::None);
    SetupVault batch_reboot(store, random);
    UnlockSession batch_session;
    CHECK(batch_reboot.device_unlock("Cc112233", 0, batch_session, retry) == VaultError::None);
    CHECK(batch_reboot.recover(batch_session, "Shared Public Phrase", after_a, after_pass, 0) ==
          VaultError::None);
    CHECK(after_a.size == before_a.size && after_a.data.value == before_a.data.value &&
          after_pass.equals(pass_a.view()));
    CHECK(batch_reboot.recover(batch_session, "Shared Public Phrase", after_b, after_pass, 1) ==
          VaultError::None);
    CHECK(after_b.size == before_b.size && after_b.data.value == before_b.data.value &&
          after_pass.equals(pass_b.view()));
    CHECK(batch_reboot.verify_phrase(batch_session, "Public New Phrase A", 0) ==
          VaultError::WrongPhrase);
    CHECK(batch_reboot.verify_phrase(batch_session, "Public New Phrase B", 1) ==
          VaultError::WrongPhrase);
    // A persistence failure must not open the device or reset attempts.
    store.fail = true;
    CHECK(pin_added.device_unlock("999999", 1, session, retry) == VaultError::Storage &&
          !session.authorized);
    CHECK(pin_added.device_unlock("Cc112233", 2, session, retry) == VaultError::Storage &&
          !session.authorized);
    store.fail = false;
    store.data[12] ^= 1;
    CHECK(pin_added.device_status(3, profiles, retry) == VaultError::InvalidData);
}
void deletion() {
    MemoryStore store;
    FixtureRandom random;
    SetupVault vault(store, random);
    SetupRequest initial;
    initial.mobile_access = MobileAccess::Pin;
    initial.name = "Keep A";
    initial.pin.assign("Aa345678");
    initial.passphrase.assign("Seed pass A");
    initial.transaction_phrase.assign("Delete fixture A");
    SetupCandidate candidate;
    CHECK(vault.prepare(initial, candidate) == VaultError::None);
    CHECK(vault.commit(candidate.record) == VaultError::None);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(vault.device_unlock("Aa345678", 0, session, retry) == VaultError::None);
    for (auto name : {"Remove B", "Keep C"}) {
        SetupRequest next;
        next.name = name;
        next.transaction_phrase.assign("Shared delete fixture");
        next.passphrase.assign(name);
        CHECK(vault.prepare_additional(next, session, candidate) == VaultError::None);
        CHECK(vault.append(candidate.record, session) == VaultError::None);
    }
    std::vector<WalletProfile> profiles;
    WalletProfile device;
    CHECK(vault.device_status(1, profiles, retry, &device) == VaultError::None &&
          profiles.size() == 3);
    const auto a_id = profiles[0].record_id, b_id = profiles[1].record_id,
               c_id = profiles[2].record_id;
    crypto::Entropy before_a, before_c, after;
    Credential before_pass_a, before_pass_c, pass;
    CHECK(vault.recover(session, "Delete fixture A", before_a, before_pass_a, 0) ==
          VaultError::None);
    CHECK(vault.recover(session, "Shared delete fixture", before_c, before_pass_c, 2) ==
          VaultError::None);
    const auto saved = store.data;
    UnlockSession denied;
    CHECK(vault.delete_wallet(denied, "Shared delete fixture", 1, b_id) == VaultError::WrongPin &&
          store.data == saved);
    CHECK(vault.delete_wallet(session, "Wrong delete phrase", 1, b_id) == VaultError::WrongPhrase &&
          store.data == saved);
    CHECK(vault.delete_wallet(session, "Shared delete fixture", 1, a_id) ==
              VaultError::InvalidData &&
          store.data == saved);
    CHECK(vault.delete_wallet(session, "Shared delete fixture", 9, b_id) ==
              VaultError::InvalidData &&
          store.data == saved);
    store.fail = true;
    CHECK(vault.delete_wallet(session, "Shared delete fixture", 1, b_id) == VaultError::Storage &&
          store.data == saved);
    store.fail = false;
    CHECK(vault.delete_wallet(session, "Shared delete fixture", 1, b_id) == VaultError::None);
    CHECK(vault.device_status(2, profiles, retry) == VaultError::None && profiles.size() == 2);
    CHECK(profiles[0].record_id == a_id && profiles[1].record_id == c_id);
    CHECK(profiles[0].name == "Keep A" && profiles[1].name == "Keep C");
    CHECK(vault.recover(session, "Delete fixture A", after, pass, 0) == VaultError::None);
    CHECK(after.size == before_a.size && after.data.value == before_a.data.value &&
          pass.equals(before_pass_a.view()));
    CHECK(vault.recover(session, "Shared delete fixture", after, pass, 1) == VaultError::None);
    CHECK(after.size == before_c.size && after.data.value == before_c.data.value &&
          pass.equals(before_pass_c.view()));
    // B's old slot now holds C with the same phrase. A stale confirmation cannot delete C.
    const auto shifted = store.data;
    CHECK(vault.delete_wallet(session, "Shared delete fixture", 1, b_id) ==
              VaultError::InvalidData &&
          store.data == shifted);
    // Removing the migrated legacy root must preserve the other ciphertext/key.
    CHECK(vault.delete_wallet(session, "Delete fixture A", 0, a_id) == VaultError::None);
    SetupVault reboot(store, random);
    CHECK(reboot.device_unlock("Aa345678", 0, session, retry) == VaultError::None);
    CHECK(reboot.device_status(1, profiles, retry) == VaultError::None && profiles.size() == 1 &&
          profiles[0].record_id == c_id);
    CHECK(reboot.recover(session, "Shared delete fixture", after, pass) == VaultError::None);
    CHECK(after.data.value == before_c.data.value && pass.equals(before_pass_c.view()));
    CHECK(reboot.delete_wallet(session, "Shared delete fixture", 0, c_id) == VaultError::None);
    CHECK(store.data.size() == min_device_record_bytes);
    SetupVault empty(store, random);
    CHECK(empty.device_status(0, profiles, retry, &device) == VaultError::None &&
          profiles.empty() && device.pin_enabled);
    CHECK(empty.device_unlock("999999", 0, session, retry) == VaultError::WrongPin &&
          retry == 30000);
    CHECK(empty.device_unlock("Aa345678", 30000, session, retry) == VaultError::None);
    SetupRequest replacement;
    replacement.name = "After deletion";
    replacement.transaction_phrase.assign("After deletion phrase");
    CHECK(empty.prepare_additional(replacement, session, candidate) == VaultError::None);
    CHECK(empty.append(candidate.record, session) == VaultError::None);
    CHECK(empty.device_status(30001, profiles, retry) == VaultError::None && profiles.size() == 1);
    CHECK(empty.recover(session, "After deletion phrase", after, pass) == VaultError::None);
    CHECK(empty.change_pin(session, "", MobileAccess::DeviceApproval) == VaultError::InvalidData);
    CHECK(empty.delete_wallet(session, "After deletion phrase", 0, profiles[0].record_id) ==
          VaultError::None);
    SetupVault no_pin(store, random);
    CHECK(no_pin.device_unlock("Aa345678", 0, session, retry) == VaultError::None);
    CHECK(no_pin.device_status(1, profiles, retry, &device) == VaultError::None &&
          profiles.empty() && device.pin_enabled);
}
void common_phrase_policy() {
    MemoryStore store;
    FixtureRandom random;
    SetupVault vault(store, random);
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "A";
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.transaction_phrase.assign("Original Common Phrase");
    request.passphrase.assign("Seed A passphrase");
    SetupCandidate first;
    CHECK(vault.prepare(request, first) == VaultError::None);
    CHECK(vault.commit(first.record, true) == VaultError::None);
    SetupVault reboot(store, random);
    std::vector<WalletProfile> profiles;
    std::uint64_t retry = 0;
    CHECK(reboot.device_status(0, profiles, retry) == VaultError::None &&
          profiles[0].shared_transaction_phrase && profiles[0].pin_enabled);
    UnlockSession session;
    CHECK(reboot.device_unlock("Aa345678", 1, session, retry) == VaultError::None);
    CHECK(reboot.verify_common_phrase(session, "Wrong common phrase") == VaultError::WrongPhrase);
    CHECK(reboot.verify_common_phrase(session, "Original Common Phrase") == VaultError::None);
    crypto::Entropy before_a, after;
    Credential before_pass, pass;
    CHECK(reboot.recover(session, "Original Common Phrase", before_a, before_pass, 0) ==
          VaultError::None);
    CHECK(reboot.device_status(2, profiles, retry) == VaultError::None);
    const auto a_id = profiles[0].record_id;
    SetupRequest next;
    next.name = "B";
    next.transaction_phrase.assign("Original Common Phrase");
    SetupCandidate second;
    CHECK(reboot.prepare_additional(next, session, second) == VaultError::None);
    const auto initial_bytes = store.data;
    CHECK(reboot.append_reusing_common(second.record, session, "Wrong common phrase") ==
              VaultError::WrongPhrase &&
          store.data == initial_bytes);
    CHECK(reboot.append_reusing_common(second.record, session, "Original Common Phrase") ==
          VaultError::None);
    CHECK(reboot.device_status(3, profiles, retry) == VaultError::None && profiles.size() == 2 &&
          profiles[0].record_id == a_id && profiles[1].shared_transaction_phrase);
    CHECK(reboot.change_phrase(session, "Original Common Phrase", "Independent B phrase", 1) ==
          VaultError::None);
    CHECK(reboot.device_status(4, profiles, retry) == VaultError::None &&
          !profiles[0].shared_transaction_phrase);
    CHECK(reboot.verify_common_phrase(session, "Original Common Phrase") ==
          VaultError::InvalidData);
    next.name = "C";
    next.transaction_phrase.assign("New Shared Phrase");
    SetupCandidate third;
    CHECK(reboot.prepare_additional(next, session, third) == VaultError::None);
    WalletPhrases proofs;
    auto a = std::make_unique<WalletPhrase>();
    a->index = 0;
    a->current.assign("Original Common Phrase");
    proofs.push_back(std::move(a));
    auto b = std::make_unique<WalletPhrase>();
    b->index = 1;
    b->current.assign("Wrong B phrase");
    proofs.push_back(std::move(b));
    const auto before = store.data;
    CHECK(reboot.append_shared(third.record, session, proofs, "New Shared Phrase") ==
              VaultError::WrongPhrase &&
          store.data == before);
    proofs[1]->current.assign("Independent B phrase");
    store.fail = true;
    CHECK(reboot.append_shared(third.record, session, proofs, "New Shared Phrase") ==
              VaultError::Storage &&
          store.data == before);
    store.fail = false;
    CHECK(reboot.append_shared(third.record, session, proofs, "New Shared Phrase") ==
          VaultError::None);
    SetupVault common_reboot(store, random);
    CHECK(common_reboot.device_unlock("Aa345678", 0, session, retry) == VaultError::None);
    CHECK(common_reboot.device_status(1, profiles, retry) == VaultError::None &&
          profiles.size() == 3 && profiles[0].shared_transaction_phrase);
    CHECK(common_reboot.verify_common_phrase(session, "New Shared Phrase") == VaultError::None);
    CHECK(common_reboot.recover(session, "New Shared Phrase", after, pass) == VaultError::None);
    CHECK(after.size == before_a.size && after.data.value == before_a.data.value &&
          pass.equals(before_pass.view()));
    CHECK(common_reboot.change_phrase(session, "New Shared Phrase", "A independent", 0, true) ==
          VaultError::InvalidData);
    CHECK(common_reboot.change_phrase(session, "New Shared Phrase", "A independent", 0) ==
          VaultError::None);
    CHECK(common_reboot.verify_phrase(session, "New Shared Phrase", 1) == VaultError::None);
    CHECK(common_reboot.device_status(2, profiles, retry) == VaultError::None &&
          !profiles[0].shared_transaction_phrase);
}
} // namespace
namespace {
void remote_access_pin_matches_local_unlock() {
    MemoryStore store;
    FixtureRandom random;
    SetupVault vault(store, random);
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Public PIN fixture";
    request.pin.assign("0Ab123");
    request.mobile_access = MobileAccess::Pin;
    request.transaction_phrase.assign("Public transaction phrase");
    SetupCandidate candidate;
    CHECK(vault.prepare(request, candidate) == VaultError::None);
    CHECK(vault.commit(candidate.record) == VaultError::None);
    UnlockSession local, remote;
    std::uint64_t retry = 0;
    CHECK(vault.device_unlock("0Ab123", 0, local, retry) == VaultError::None);
    CHECK(vault.device_unlock("0Ab123", 1, remote, retry, true) == VaultError::None);
    CHECK(local.pin_key.value == remote.pin_key.value);
    crypto::Entropy expected, actual;
    Credential local_pass, remote_pass;
    CHECK(vault.recover(local, "Public transaction phrase", expected, local_pass) ==
          VaultError::None);
    CHECK(vault.recover(remote, "Public transaction phrase", actual, remote_pass) ==
          VaultError::None);
    CHECK(expected.size == actual.size && expected.data.value == actual.data.value);
    CHECK(vault.device_unlock("1234", 2, remote, retry, true) == VaultError::WrongPin);
    CHECK(vault.device_unlock("0Ab123", 3, remote, retry, true) == VaultError::None);
}
} // namespace
int main() {
    crypto::Key reference, cooperative;
    crypto::Salt salt{};
    CHECK(crypto::keystore_key(crypto::bytes("Public KDF comparison"), salt, reference));
    CHECK(crypto::keystore_key_cooperative(crypto::bytes("Public KDF comparison"), salt,
                                           cooperative, yield_counter));
    CHECK(reference.value == cooperative.value && yields == 100);
    matrix();
    imports_and_failures();
    device_credentials();
    deletion();
    common_phrase_policy();
    remote_access_pin_matches_local_unlock();
    std::cout << checks
              << " setup vault checks passed: encrypted create/import, mandatory PIN, optional "
                 "passphrase, mobile policy, reboot, corruption, AAD and failed commits.\n";
}
