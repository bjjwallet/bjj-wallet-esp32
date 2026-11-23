// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/crypto.hpp"

namespace airlock::crypto {
// Monero Wallet RPC v0.18 output archives use one CryptoNight v0 round with
// the standard wallet KDF setting. The scratchpad is wiped before release.
bool monero_archive_key(Bytes input, Key& key, void (*yield)() = nullptr);
} // namespace airlock::crypto
