// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <string>
#include <string_view>

namespace airlock {
struct CardanoReview {
    std::string wallet_id, mode, sender, recipient, amount, fee, change;
    std::string symbol = "ADA", recipient_lovelace;
    unsigned decimals = 6;
};
bool review_cardano(std::string_view payload, CardanoReview& review);
bool sign_cardano(std::string_view payload, std::string_view mnemonic, std::string_view passphrase,
                  std::string& witness);
} // namespace airlock
