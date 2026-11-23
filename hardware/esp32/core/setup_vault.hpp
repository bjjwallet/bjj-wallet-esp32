// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/network_accounts.hpp"
#include "core/public_wallet_service.hpp"
#include "core/secure_allocation.hpp"
#include <memory>
#include <vector>

namespace airlock {
enum class StoreRead { Missing, Found, Error };
class SetupStore {
  public:
    virtual ~SetupStore() = default;
    virtual StoreRead read(std::vector<std::uint8_t>& data) = 0;
    virtual bool commit_if_empty(crypto::Bytes data) = 0;
    virtual StoreRead load_language(std::string& code) {
        code.clear();
        return StoreRead::Missing;
    }
    virtual bool save_language(std::string_view) {
        return false;
    }
    virtual StoreRead load_device_name(std::string& name) {
        name.clear();
        return StoreRead::Missing;
    }
    virtual bool save_device_name(std::string_view) {
        return false;
    }
    virtual bool public_cache_enabled() const {
        return false;
    }
    virtual StoreRead read_public(std::string_view, std::vector<std::uint8_t>& data) {
        data.clear();
        return StoreRead::Missing;
    }
    virtual bool write_public(std::string_view, crypto::Bytes) {
        return false;
    }
    virtual StoreRead read_monero_enabled(std::string_view, bool& enabled) {
        enabled = true;
        return StoreRead::Missing;
    }
    virtual bool write_monero_enabled(std::string_view, bool) {
        return false;
    }
    // Atomically replace only the exact record read by the caller.
    virtual bool replace(crypto::Bytes expected, crypto::Bytes data) {
        (void)expected;
        (void)data;
        return false;
    }
};
enum class VaultError {
    None,
    Missing,
    AlreadyConfigured,
    InvalidData,
    WrongPin,
    WrongPhrase,
    Random,
    Crypto,
    Storage,
    Locked,
    Full
};
enum class MobileAccess { DeviceApproval, Pin };
struct WalletProfile {
    std::string name;
    std::array<std::uint8_t, 32> record_id{};
    std::array<std::uint8_t, 32> public_id{}; // Stable across legacy PIN metadata migration.
    std::size_t word_count = 0;
    bool pin_enabled = false;
    bool pin_policy_current = false;
    bool shared_transaction_phrase = false;
    bool passphrase_enabled = false;
    bool imported = false;
    MobileAccess mobile_access = MobileAccess::DeviceApproval;
};
struct SetupRequest : InternalSecretAllocation {
    std::string name;
    std::size_t word_count = 12;
    bool imported = false;
    MobileAccess mobile_access = MobileAccess::DeviceApproval;
    Credential mnemonic;
    Credential passphrase;
    Credential pin;
    Credential transaction_phrase;
};
// Canonical, fixed-size, versioned encoding: authenticated header, encrypted
// entropy/passphrase, GCM tag, checksum for early accidental-corruption detection.
inline constexpr std::size_t wallet_record_bytes = 476;
inline constexpr std::size_t max_saved_wallets = 16;
inline constexpr std::size_t min_device_record_bytes = 168 + 32;
inline constexpr std::size_t max_device_record_bytes =
    168 + max_saved_wallets * wallet_record_bytes + 32;
using WalletRecord = std::array<std::uint8_t, wallet_record_bytes>;
struct SetupCandidate : InternalSecretAllocation {
    WalletRecord record{};
    WalletProfile profile;
    Credential backup_words;      // Never sent to console/BLE.
    Credential backup_passphrase; // Cleared with the on-device backup confirmation.
};
struct UnlockSession : InternalSecretAllocation {
    crypto::Key pin_key;
    bool authorized = false;
    void clear() {
        wipe(pin_key.value.data(), pin_key.value.size());
        authorized = false;
    }
};

bool valid_wallet_name(std::string_view name);
bool valid_setup_pin(std::string_view pin);
bool valid_transaction_phrase(std::string_view phrase);
struct ReceivingAddress {
    std::string network;
    std::string value;
};

struct WalletPhrase : InternalSecretAllocation {
    std::size_t index = 0;
    Credential current;
};
using WalletPhrases = std::vector<std::unique_ptr<WalletPhrase>>;

class SetupVault {
  public:
    SetupVault(SetupStore& store, crypto::RandomSource& random, void (*yield)() = nullptr)
        : store_(store), random_(random), yield_(yield) {}
    VaultError inspect(WalletProfile& profile);
    VaultError device_status(std::uint64_t now, std::vector<WalletProfile>& profiles,
                             std::uint64_t& retry_ms, WalletProfile* device_profile = nullptr);
    VaultError device_unlock(std::string_view pin, std::uint64_t now, UnlockSession& session,
                             std::uint64_t& retry_ms, bool remote = false);
    VaultError change_pin(const UnlockSession& session, std::string_view pin, MobileAccess policy);
    VaultError change_phrase(const UnlockSession& session, std::string_view old_phrase,
                             std::string_view new_phrase, std::size_t index,
                             bool shared_single = false);
    // Validate all supplied current phrases, then publish one atomic envelope.
    VaultError change_phrases(const UnlockSession& session, const WalletPhrases& phrases,
                              std::string_view new_phrase);
    VaultError delete_wallet(const UnlockSession& session, std::string_view phrase,
                             std::size_t index, const std::array<std::uint8_t, 32>& expected_id);
    VaultError verify_phrase(const UnlockSession& session, std::string_view phrase,
                             std::size_t index);
    VaultError prepare_additional(const SetupRequest& request, const UnlockSession& session,
                                  SetupCandidate& candidate);
    VaultError append(const WalletRecord& record, const UnlockSession& session);
    VaultError verify_common_phrase(const UnlockSession& session, std::string_view phrase);
    VaultError append_reusing_common(const WalletRecord& record, const UnlockSession& session,
                                     std::string_view phrase);
    VaultError append_shared(const WalletRecord& record, const UnlockSession& session,
                             const WalletPhrases& phrases, std::string_view new_phrase);
    VaultError generate_words(SetupRequest& request);
    VaultError prepare(const SetupRequest& request, SetupCandidate& candidate);
    VaultError commit(const WalletRecord& record, bool shared = false);
    VaultError unlock(std::string_view pin, UnlockSession& session);
    // Local callers supply the transaction phrase after session authorization.
    VaultError recover(const UnlockSession& session, std::string_view transaction_phrase,
                       crypto::Entropy& entropy, Credential& passphrase, std::size_t index = 0);
    VaultError public_wallets(std::string& generation, std::vector<PublicWallet>& wallets);
    VaultError monero_enabled(std::size_t index, bool& enabled);
    VaultError set_monero_enabled(const UnlockSession&, std::size_t index, bool enabled);
    PublicWalletError export_public_accounts(std::string_view generation,
                                             const PublicWalletQuery& query, std::size_t selected,
                                             std::vector<PublicAccount>& rows);
    VaultError public_accounts(std::size_t index, std::string& generation,
                               std::vector<PublicAccount>& rows);
    VaultError cache_public(const WalletProfile& profile, std::string_view mnemonic,
                            std::string_view passphrase);
    VaultError receiving_addresses(const UnlockSession& session,
                                   std::string_view transaction_phrase,
                                   std::vector<ReceivingAddress>& output, std::size_t index = 0);
    VaultError monero_view_qr(const UnlockSession& session, std::string_view phrase,
                              std::size_t index, PublicWalletMode mode, Credential& qr,
                              bool include_view_key = true);

  private:
    SetupStore& store_;
    crypto::RandomSource& random_;
    void (*yield_)();
    std::uint64_t retry_until_ = 0;
    bool retry_loaded_ = false;
    const crypto::Key* preparing_key_ = nullptr;
    bool preparing_additional_ = false;
    const std::array<std::uint8_t, 32>* preparing_wallet_id_ = nullptr;
    VaultError public_wallet_identity(const WalletProfile& profile,
                                      std::array<std::uint8_t, 32>& id);
    VaultError load(WalletRecord& record, WalletProfile& profile, std::size_t index = 0,
                    const crypto::Key* key = nullptr);
    VaultError decrypt_record(const WalletRecord& record, const WalletProfile& profile,
                              const UnlockSession& session, std::string_view phrase,
                              crypto::Entropy& entropy, Credential& passphrase);
    VaultError update_phrases(const UnlockSession& session, const WalletPhrases& phrases,
                              std::string_view new_phrase, const WalletRecord* append_record);
    VaultError rephrase_record(const UnlockSession& session, std::string_view old_phrase,
                               std::string_view new_phrase, std::size_t index,
                               WalletRecord& output);
};

enum class SetupWork {
    GenerateWords,
    Prepare,
    Commit,
    Unlock,
    ReceivingAddresses,
    PublicAddresses,
    Open,
    VerifyPhrase,
    ChangePin,
    ChangePhrase,
    PrepareAdditional,
    Append,
    ChangePhrases,
    DeleteWallet,
    AppendShared,
    VerifyCommon,
    AppendReuse
};
struct SetupJob : InternalSecretAllocation {
    std::uint64_t token = 0;
    SetupWork work = SetupWork::Prepare;
    std::unique_ptr<SetupRequest> request;
    WalletRecord record{};
    Credential pin;
    Credential transaction_phrase;
    Credential new_phrase;
    WalletPhrases phrases;
    MobileAccess mobile_access = MobileAccess::DeviceApproval;
    bool shared = false;
    std::uint64_t now = 0;
    std::size_t wallet_index = 0;
    std::array<std::uint8_t, 32> expected_id{};
    UnlockSession address_session;
};
struct SetupResult {
    std::unique_ptr<SetupRequest> request;
    std::uint64_t token = 0;
    SetupWork work = SetupWork::Prepare;
    VaultError error = VaultError::None;
    std::unique_ptr<SetupCandidate> candidate;
    std::unique_ptr<UnlockSession> session;
    std::vector<ReceivingAddress> addresses;
    std::vector<WalletProfile> profiles;
    WalletProfile device_profile;
    std::uint64_t retry_ms = 0;
};
void run_setup_job(SetupVault& vault, SetupJob& job, SetupResult& result);
class SetupService {
  public:
    virtual ~SetupService() = default;
    virtual VaultError inspect(WalletProfile& profile) = 0;
    virtual StoreRead load_language(std::string& code) {
        code.clear();
        return StoreRead::Missing;
    }
    virtual bool save_language(std::string_view) {
        return false;
    }
    virtual StoreRead load_device_name(std::string& name) {
        name.clear();
        return StoreRead::Missing;
    }
    virtual bool save_device_name(std::string_view) {
        return false;
    }
    virtual bool load_monero_enabled(std::size_t, bool&) {
        return false;
    }
    virtual bool save_monero_enabled(const UnlockSession&, std::size_t, bool) {
        return false;
    }
    virtual bool submit(std::unique_ptr<SetupJob> job) = 0;
    virtual std::unique_ptr<SetupResult> poll() = 0;
};
} // namespace airlock
