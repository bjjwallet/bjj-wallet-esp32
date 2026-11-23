// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/hd_wallet.hpp"
#include <optional>
#include <vector>

namespace airlock::crypto {
enum class MoneroNetwork { Mainnet, Stagenet };

// Trezor-compatible BIP39/SLIP-0010 account zero. The caller supplies the BIP39
// seed for the chosen wallet and mode. This helper returns only the public address.
HdError derive_monero_address(const Seed& seed, MoneroNetwork network, RandomSource& random,
                              std::string& address);
// For a locally confirmed display operation. Do not pass the key to transport.
HdError derive_monero_view_wallet(const Seed& seed, MoneroNetwork network, RandomSource& random,
                                  std::string& address, Key& private_view_key);

using MoneroPoint = std::array<std::uint8_t, 32>;
struct MoneroOutput {
    MoneroPoint public_key{}, transaction_key{};
    std::optional<MoneroPoint> additional_key;
    std::uint32_t output_index = 0, subaddress_major = 0, subaddress_minor = 0;
};
struct MoneroSignedKeyImage {
    MoneroPoint key_image{};
    std::array<std::uint8_t, 64> signature{};
};
struct MoneroSignedTransfer {
    std::vector<std::uint8_t> transaction;
    MoneroPoint txid{};
};
enum class MoneroTransferError {
    None,
    InvalidArchive,
    Unsupported,
    ReviewMismatch,
    InputNotOwned,
    Crypto,
    Capacity
};
inline constexpr std::size_t monero_max_unsigned_bytes = 128 * 1024;
bool monero_transfer_supported();
MoneroTransferError sign_monero_transfer_archive(const Seed& seed, Bytes encrypted,
                                                 std::string_view recipient, std::uint64_t amount,
                                                 std::uint64_t fee, RandomSource& random,
                                                 MoneroSignedTransfer& result,
                                                 void (*yield)() = nullptr);

enum class MoneroKeyImageError { None, InvalidOutputs, TooManyOutputs, Crypto };
inline constexpr std::size_t monero_max_key_images = 128;
inline constexpr std::size_t monero_max_output_archive_bytes = 256 * 1024;

// Consume the authenticated plaintext of Monero's current output archive.
// Bind the archive to both wallet public keys and accept only complete batches.
MoneroKeyImageError parse_monero_output_archive(Bytes plaintext, const MoneroPoint& public_spend,
                                                const MoneroPoint& public_view,
                                                std::vector<MoneroOutput>& result);

// Process validated archive records after local authorization. Verify ownership
// against the selected wallet seed before publishing any image or proof.
MoneroKeyImageError sign_monero_key_images(const Seed& seed,
                                           const std::vector<MoneroOutput>& outputs,
                                           RandomSource& random,
                                           std::vector<MoneroSignedKeyImage>& result);
// Verify the official export_outputs archive signature, decrypt it with the
// selected view key and sign only outputs that belong to the selected seed.
MoneroKeyImageError sign_monero_output_archive(const Seed& seed, Bytes encrypted,
                                               RandomSource& random,
                                               std::vector<MoneroSignedKeyImage>& result,
                                               void (*yield)() = nullptr);
} // namespace airlock::crypto
