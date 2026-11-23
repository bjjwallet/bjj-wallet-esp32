// SPDX-License-Identifier: GPL-3.0-or-later
// Port of Airlock, Copyright (C) 2026 Le Wang.
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace airlock::config {
inline constexpr char product_name[] = "BJJ-Cold-Wallet";
inline constexpr std::string_view wallet_core_version = "4.4.2";
inline constexpr std::string_view service_uuid = "0000beef-0000-1000-8000-00805f9b34fb";
inline constexpr std::string_view status_uuid = "0000bef0-0000-1000-8000-00805f9b34fb";
inline constexpr std::string_view command_uuid = "0000bef1-0000-1000-8000-00805f9b34fb";
inline constexpr std::string_view descriptor_uuid = "0000bef2-0000-1000-8000-00805f9b34fb";
inline constexpr std::string_view eof_marker = "###EOF###";
inline constexpr std::size_t max_packet_bytes = 1024 * 1024;
inline constexpr std::size_t notification_bytes = 180;
inline constexpr std::uint64_t confirmation_timeout_ms = 60000;
inline constexpr std::uint64_t rx_timeout_ms = 10000;
inline constexpr std::size_t mnemonic_words = 24;
inline constexpr std::array<std::size_t, 5> import_word_counts = {12, 15, 18, 21, 24};
inline constexpr std::size_t hidden_ed25519_accounts = 100;
inline constexpr std::size_t standard_ed25519_accounts = 10;
inline constexpr std::uint32_t keystore_iterations = 100000;
inline constexpr std::size_t salt_bytes = 16;
inline constexpr std::size_t nonce_bytes = 12;
inline constexpr std::size_t gcm_tag_bytes = 16;
} // namespace airlock::config
