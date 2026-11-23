// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/hd_wallet.hpp"

namespace airlock {
enum class Network {
    Bitcoin,
    Dogecoin,
    Monero,
    Solana,
    Ethereum,
    Stellar,
    Base,
    Ripple,
    HyperEvm,
    Arbitrum,
    Bnb,
    Polygon,
    Tron,
    Robinhood,
    BitcoinCash,
    Ton,
    Avalanche,
    Classic,
    Litecoin,
    Sui,
    Dash,
    Ecash
};

struct NetworkAccountSpec {
    Network network;
    std::string_view name;
    std::string_view native_symbol;
    std::uint32_t evm_chain_id; // Zero for networks outside EVM.
    crypto::Curve curve;
    std::string_view first_account_path; // Empty while the key scheme is pending.
    // Requested symbols only. A token needs its verified network-specific
    // contract, mint or issuer before it can be used for funds or signing.
    std::array<std::string_view, 14> pending_tokens;
};

inline constexpr std::array<NetworkAccountSpec, 22> network_accounts{{
    {Network::Bitcoin, "Bitcoin", "BTC", 0, crypto::Curve::Secp256k1, "m/84'/0'/0'/0/0", {}},
    {Network::Dogecoin, "Dogecoin", "DOGE", 0, crypto::Curve::Secp256k1, "m/44'/3'/0'/0/0", {}},
    {Network::Monero, "Monero", "XMR", 0, crypto::Curve::Ed25519, {}, {}},
    {Network::Solana,
     "Solana",
     "SOL",
     0,
     crypto::Curve::Ed25519,
     "m/44'/501'/0'/0'",
     {"BRLP", "BRZ", "USDC", "USDT", "XRP", "SKR", "USDS", "PYUSD", "EURC", "LINK", "JUP", "MON"}},
    {Network::Ethereum,
     "Ethereum",
     "ETH",
     1,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {"USDT", "USDC", "USDS", "USDe", "RLUSD", "EURC", "BNB", "BRZ"}},
    {Network::Stellar,
     "Stellar",
     "XLM",
     0,
     crypto::Curve::Ed25519,
     "m/44'/148'/0'",
     {"USDC", "BRLP", "BRZ"}},
    {Network::Base,
     "Base",
     "ETH",
     8453,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {"USDC", "USDe", "USDS", "EURC", "LINK", "USDT", "BRZ"}},
    {Network::Ripple,
     "XRP Ledger",
     "XRP",
     0,
     crypto::Curve::Secp256k1,
     "m/44'/144'/0'/0/0",
     {"RLUSD", "USDC"}},
    {Network::HyperEvm,
     "HyperEVM",
     "HYPE",
     999,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {"USDC", "LINK"}},
    {Network::Arbitrum,
     "Arbitrum One",
     "ETH",
     42161,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {"ARB", "USDT", "USDC", "USDS", "USDe", "LINK", "BRZ"}},
    {Network::Bnb,
     "BNB Smart Chain",
     "BNB",
     56,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {"USDT", "USDC", "USDe", "XRP", "LINK", "BRZ"}},
    {Network::Polygon,
     "Polygon PoS",
     "POL",
     137,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {"USDT", "USDC", "LINK", "BRZ"}},
    {Network::Tron,
     "TRON",
     "TRX",
     0,
     crypto::Curve::Secp256k1,
     "m/44'/195'/0'/0/0",
     {"USDT", "USDC"}},
    {Network::Robinhood,
     "Robinhood Chain",
     "ETH",
     4663,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {"USDG"}},
    {Network::BitcoinCash,
     "Bitcoin Cash",
     "BCH",
     0,
     crypto::Curve::Secp256k1,
     "m/44'/145'/0'/0/0",
     {}},
    {Network::Ton, "TON", "TON", 0, crypto::Curve::Ed25519, "m/44'/607'/0'", {}},
    {Network::Avalanche,
     "Avalanche C-Chain",
     "AVAX",
     43114,
     crypto::Curve::Secp256k1,
     "m/44'/60'/0'/0/0",
     {}},
    {Network::Classic,
     "Ethereum Classic",
     "ETC",
     61,
     crypto::Curve::Secp256k1,
     "m/44'/61'/0'/0/0",
     {}},
    {Network::Litecoin, "Litecoin", "LTC", 0, crypto::Curve::Secp256k1, "m/84'/2'/0'/0/0", {}},
    {Network::Sui, "Sui", "SUI", 0, crypto::Curve::Ed25519, "m/44'/784'/0'/0'/0'", {}},
    {Network::Dash, "Dash", "DASH", 0, crypto::Curve::Secp256k1, "m/44'/5'/0'/0/0", {}},
    {Network::Ecash, "eCash", "XEC", 0, crypto::Curve::Secp256k1, "m/44'/899'/0'/0/0", {}},
}};

struct AccountPublicKey {
    std::array<std::uint8_t, 33> bytes{};
    std::size_t size = 0;
};

inline constexpr std::string_view cardano_receiving_path = "m/1852'/1815'/0'/0/0";
crypto::HdError cardano_receiving_address(std::string_view mnemonic, std::string_view passphrase,
                                          std::string& output);
bool cardano_payment_witness(std::string_view mnemonic, std::string_view passphrase,
                             std::string_view sender, const std::array<std::uint8_t, 32>& digest,
                             std::array<std::uint8_t, 96>& witness);

crypto::HdError encode_receiving_address(Network network, const AccountPublicKey& key,
                                         std::string& output);

// Derive the initial account key locally. Tokens use the network account's
// key. The resulting key still needs network-specific address encoding.
crypto::HdError derive_account_public_key(Network network, const crypto::Seed& seed,
                                          crypto::RandomSource& random, AccountPublicKey& output);
crypto::HdError derive_receiving_address(Network network, const crypto::Seed& seed,
                                         crypto::RandomSource& random, std::string& output);
} // namespace airlock
