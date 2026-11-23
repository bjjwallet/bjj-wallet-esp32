// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/network_accounts.hpp"
#include <vector>
namespace airlock {
struct UtxoReview {
    std::string coin, symbol, address, recipient, path, amount, fee, change, mode;
    Network network = Network::Bitcoin;
    unsigned decimals = 8;
};
// Validates mainnet addresses, funding transactions, exact outputs and fee before review.
// No seed or password is accepted by the validator.
bool review_utxo(std::string_view payload, UtxoReview& review);
// Only the local confirmation lifecycle may call this with a recovered device seed.
bool sign_utxo(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
               std::string& encoded);
} // namespace airlock
