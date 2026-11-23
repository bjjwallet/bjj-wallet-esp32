// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/ledger_signer.hpp"
namespace airlock {
bool review_xrp(std::string_view payload, LedgerReview& review);
bool sign_xrp(std::string_view payload, crypto::Bytes seed, crypto::RandomSource& random,
              std::string& encoded);
} // namespace airlock
