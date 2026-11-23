// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "ui/states_wallet.hpp"
#include <array>
#include <cstdint>
#include <optional>

namespace airlock {
enum class WalletError { None, InvalidMnemonic, Random, Crypto, Storage, Accounts, Unavailable };
struct WalletResult {
    std::uint64_t token = 0;
    WalletWork kind = WalletWork::Generate;
    WalletError error = WalletError::None;
    std::array<MnemonicWord, config::mnemonic_words> words;
    std::size_t accounts = 0;
    std::size_t failed_coins = 0;
};

// The UI never owns platform APIs or cryptographic substitutes. Implementations
// execute jobs off the LVGL task and publish a result only after durable commit.
class WalletService {
  public:
    virtual ~WalletService() = default;
    virtual bool available() const = 0;
    virtual bool exists() const = 0;
    virtual bool busy() const = 0;
    virtual bool submit(WalletJob job) = 0;
    virtual std::optional<WalletResult> poll() = 0;
};
} // namespace airlock
