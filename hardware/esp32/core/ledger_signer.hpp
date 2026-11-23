// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/hd_wallet.hpp"
#include <string>
#include <string_view>
namespace airlock {
struct LedgerReview {
    std::string wallet_id, mode, sender, recipient, symbol, amount, fee, coin, fee_symbol;
    unsigned decimals = 0;
};
bool review_ledger(std::string_view payload, LedgerReview& review);
bool sign_ledger(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
                 std::string& encoded);
} // namespace airlock
