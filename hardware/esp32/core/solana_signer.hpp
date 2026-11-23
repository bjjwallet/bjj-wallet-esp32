// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/hd_wallet.hpp"

namespace airlock {
struct SolanaReview {
    std::string wallet_id, mode, sender, recipient, path, amount;
    static constexpr std::string_view fee = "5000";
    std::string symbol = "SOL", fee_amount = "5000";
    unsigned decimals = 9;
};
bool review_solana(std::string_view payload, SolanaReview& review);
bool sign_solana(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
                 std::string& encoded);
} // namespace airlock
