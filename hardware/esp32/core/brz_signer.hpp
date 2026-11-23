// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/hd_wallet.hpp"

namespace airlock {
struct BrzReview {
    std::string wallet_id, mode, sender, recipient, path, amount, fee;
    bool ethereum = false;
    bool polygon = false;
    bool native_ethereum = false;
    bool arbitrum = false;
    bool token = false;
    std::string symbol = "BRZ";
    std::string contract, network_name = "BNB Smart Chain", fee_symbol = "BNB";
    std::uint32_t chain_id = 56;
    unsigned decimals = 18;
};
bool review_brz(std::string_view payload, BrzReview& review);
bool sign_brz(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
              std::string& encoded);
} // namespace airlock
