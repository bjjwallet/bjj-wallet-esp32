// SPDX-License-Identifier: GPL-3.0-or-later
// Flow port of ui/states_wallet.py (Airlock, Copyright (C) 2026 Le Wang).
#pragma once
#include "core/config.hpp"
#include "core/rules.hpp"
#include "core/secret.hpp"
#include <array>
#include <cstdint>
#include <optional>
#include <string_view>

namespace airlock {
enum class WalletFlow { Create, Import };
enum class WalletStage {
    Idle,
    Overwrite,
    WordCount,
    Passphrase,
    ConfirmPassphrase,
    Password,
    ConfirmPassword,
    ImportWord,
    ImportPassphrase,
    Generating,
    Backup,
    VerifyWord,
    VerifyPassphrase,
    VerifyPassword,
    Saving,
    Done
};
enum class WalletWork { Generate, Import };
struct WalletJob {
    std::uint64_t token = 0;
    WalletWork kind = WalletWork::Generate;
    std::size_t word_count = 0;
    std::array<SecretBuffer, config::mnemonic_words> words;
    Credential passphrase;
    Credential password;
};

// UI-only state transitions. Production jobs MUST go to an on-device Wallet
// Core backend. This class neither generates keys nor implements cryptography.
class WalletStates {
  public:
    void begin(WalletFlow flow, bool wallet_exists);
    void cancel();
    InputError confirm_overwrite();
    InputError select_word_count(std::size_t count);
    InputError input(std::string_view text);
    InputError begin_backup_verification();
    void restart_passphrase();
    std::optional<WalletJob> take_job();
    // Generate completion is accepted only for the current job and exactly 24
    // valid dictionary words. Checksum verification belongs to Wallet Core.
    bool generated(std::uint64_t token, const std::array<std::string_view, 24>& words);
    bool saved(std::uint64_t token);
    bool failed(std::uint64_t token);
    WalletStage stage() const {
        return stage_;
    }
    WalletFlow flow() const {
        return flow_;
    }
    std::size_t word_count() const {
        return word_count_;
    }
    std::size_t word_index() const {
        return word_index_;
    }
    std::string_view backup_word(std::size_t index) const;
    std::string_view verification_target() const;

  private:
    WalletStage stage_ = WalletStage::Idle;
    WalletFlow flow_ = WalletFlow::Create;
    std::uint64_t token_ = 0;
    bool in_flight_ = false;
    std::size_t word_count_ = config::mnemonic_words;
    std::size_t word_index_ = 0;
    std::array<SecretBuffer, config::mnemonic_words> words_;
    Credential passphrase_;
    Credential password_;
    void first_stage();
    void clear_secrets();
};
} // namespace airlock
