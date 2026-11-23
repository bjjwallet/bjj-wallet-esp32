// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <TrustWalletCore/TWHDWallet.h>
#include <string_view>

namespace airlock {
// Wallet Core 4.4.2's HDWallet passes an empty password to Icarus, even when
// created with a passphrase. Use its own linked Cardano primitives with the
// actual UTF-8 passphrase. Returns a payment + staking key owned by the caller.
// CIP-3 Icarus / CIP-1852 only: m/1852'/1815'/account'/role/index, role 0 or 1.
// Legacy Wallet Core adapter. The portable firmware signs through
// cardano_payment_witness in core/network_accounts.hpp.
TWPrivateKey* cardano_icarus_key(TWHDWallet* wallet, std::string_view passphrase, TWString* path);
} // namespace airlock
