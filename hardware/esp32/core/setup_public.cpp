// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/setup_vault.hpp"
#include <algorithm>

namespace airlock {
namespace {
std::string identifier(const std::array<std::uint8_t, 32>& id) {
    constexpr char hex[] = "0123456789abcdef";
    std::string out;
    for (auto b : id) {
        out += hex[b >> 4];
        out += hex[b & 15];
    }
    return out;
}
bool cache_header(const std::vector<std::uint8_t>& bytes,
                  const std::array<std::uint8_t, 32>& expected,
                  std::array<std::uint8_t, 32>& stable, std::size_t& count_position) {
    if (bytes.size() < 69 || bytes.size() > 65536 || bytes[0] != 'P' || bytes[1] != 'U' ||
        bytes[2] != 'B' || (bytes[3] != 1 && bytes[3] != 2) ||
        !std::equal(expected.begin(), expected.end(), bytes.begin() + 4))
        return false;
    count_position = bytes[3] == 2 ? 68 : 36;
    if (bytes.size() < count_position + 33)
        return false;
    std::array<std::uint8_t, 32> hash{};
    if (!crypto::sha256({bytes.data(), bytes.size() - 32}, hash) ||
        !std::equal(hash.begin(), hash.end(), bytes.end() - 32))
        return false;
    stable = expected;
    if (bytes[3] == 2)
        std::copy_n(bytes.begin() + 36, 32, stable.begin());
    return std::any_of(stable.begin(), stable.end(), [](auto byte) { return byte != 0; });
}
PublicWalletError export_error(VaultError error) {
    if (error == VaultError::None)
        return PublicWalletError::None;
    if (error == VaultError::Missing)
        return PublicWalletError::Unavailable;
    if (error == VaultError::InvalidData)
        return PublicWalletError::InvalidData;
    return PublicWalletError::Internal;
}
const char* coin(Network n) {
    switch (n) {
        case Network::Bitcoin:
            return "bitcoin";
        case Network::Dogecoin:
            return "doge";
        case Network::Solana:
            return "solana";
        case Network::Ethereum:
            return "ethereum";
        case Network::Stellar:
            return "stellar";
        case Network::Base:
            return "base";
        case Network::Ripple:
            return "ripple";
        case Network::HyperEvm:
            return "hyperevm";
        case Network::Arbitrum:
            return "arbitrum";
        case Network::Bnb:
            return "smartchain";
        case Network::Polygon:
            return "polygon";
        case Network::Tron:
            return "tron";
        case Network::Robinhood:
            return "robinhoodchain";
        case Network::BitcoinCash:
            return "bitcoincash";
        case Network::Ton:
            return "ton";
        case Network::Avalanche:
            return "avalanchec";
        case Network::Classic:
            return "classic";
        case Network::Litecoin:
            return "litecoin";
        case Network::Sui:
            return "sui";
        case Network::Dash:
            return "dash";
        case Network::Ecash:
            return "ecash";
        default:
            return "";
    }
}
void put(std::vector<std::uint8_t>& out, std::string_view s, std::vector<std::string>& dictionary) {
    const auto found = std::find(dictionary.begin(), dictionary.end(), s);
    if (found != dictionary.end()) {
        const auto index = static_cast<std::size_t>(found - dictionary.begin());
        out.push_back(255);
        out.push_back(255);
        out.push_back(index >> 8);
        out.push_back(index);
        return;
    }
    dictionary.emplace_back(s);
    out.push_back(s.size() >> 8);
    out.push_back(s.size());
    out.insert(out.end(), s.begin(), s.end());
}
bool get(const std::vector<std::uint8_t>& in, std::size_t& pos, std::string& s,
         std::vector<std::string>& dictionary) {
    if (pos + 2 > in.size())
        return false;
    const std::size_t size = in[pos] * 256U + in[pos + 1];
    pos += 2;
    if (size == 65535) {
        if (pos + 2 > in.size())
            return false;
        const auto index = in[pos] * 256U + in[pos + 1];
        pos += 2;
        if (index >= dictionary.size())
            return false;
        s = dictionary[index];
        return true;
    }
    if (size > 512 || pos + size > in.size())
        return false;
    s.assign(reinterpret_cast<const char*>(in.data() + pos), size);
    pos += size;
    dictionary.push_back(s);
    return true;
}
} // namespace
VaultError SetupVault::public_wallet_identity(const WalletProfile& profile,
                                              std::array<std::uint8_t, 32>& id) {
    id = profile.public_id;
    std::vector<std::uint8_t> bytes;
    const auto status = store_.read_public(identifier(profile.public_id), bytes);
    if (status == StoreRead::Missing)
        return VaultError::None;
    if (status != StoreRead::Found)
        return VaultError::Storage;
    std::size_t position = 0;
    return cache_header(bytes, profile.public_id, id, position) ? VaultError::None
                                                                : VaultError::InvalidData;
}
VaultError SetupVault::cache_public(const WalletProfile& profile, std::string_view mnemonic,
                                    std::string_view passphrase) {
    if (!store_.public_cache_enabled())
        return VaultError::None;
    std::array<std::uint8_t, 32> stable_id{};
    if (preparing_wallet_id_)
        stable_id = *preparing_wallet_id_;
    else {
        const auto identity = public_wallet_identity(profile, stable_id);
        if (identity != VaultError::None)
            return identity;
    }
    std::vector<PublicAccount> rows;
    for (auto mode : {PublicWalletMode::Standard, PublicWalletMode::Hidden}) {
        crypto::Seed seed;
        if (crypto::wallet_core_seed(
                mnemonic, mode == PublicWalletMode::Hidden ? passphrase : std::string_view{},
                seed) != crypto::HdError::None)
            return VaultError::Crypto;
        for (const auto& spec : network_accounts) {
            if (spec.first_account_path.empty())
                continue;
            PublicAccount row;
            row.coin = coin(spec.network);
            row.symbol = spec.native_symbol;
            row.name = spec.name;
            row.blockchain = spec.name;
            row.path = spec.first_account_path;
            row.mode = mode;
            row.decimals = spec.network == Network::Ecash ? 2
                           : spec.evm_chain_id            ? 18
                           : (spec.network == Network::Solana || spec.network == Network::Ton ||
                              spec.network == Network::Sui)
                               ? 9
                           : spec.network == Network::Stellar                                   ? 7
                           : (spec.network == Network::Tron || spec.network == Network::Ripple) ? 6
                                                                                                : 8;
            row.curve = spec.curve == crypto::Curve::Secp256k1 ? "secp256k1" : "ed25519";
            if (spec.network == Network::Solana)
                row.path = "m/44'/501'/0'";
            crypto::DerivedKey leaf;
            if (crypto::derive_key({seed.value.data(), seed.value.size()}, spec.curve, row.path,
                                   random_, leaf) != crypto::HdError::None)
                return VaultError::Crypto;
            AccountPublicKey public_key{leaf.public_key, leaf.public_key_size};
            if (encode_receiving_address(spec.network, public_key, row.address) !=
                crypto::HdError::None)
                return VaultError::Crypto;
            if (spec.curve == crypto::Curve::Secp256k1) {
                crypto::DerivedKey account;
                const auto parent = row.path.substr(0, row.path.find_last_of('/'));
                const auto account_path = parent.substr(0, parent.find_last_of('/'));
                if (crypto::derive_key({seed.value.data(), seed.value.size()}, spec.curve,
                                       account_path, random_, account) != crypto::HdError::None ||
                    crypto::extended_public(
                        account,
                        (spec.network == Network::Bitcoin || spec.network == Network::Litecoin)
                            ? 0x04b24746U
                        : spec.network == Network::Dogecoin ? 0x02facafdU
                                                            : 0x0488b21eU,
                        row.xpub) != crypto::HdError::None)
                    return VaultError::Crypto;
            } else
                row.address_index = 0;
            rows.push_back(std::move(row));
            if (yield_)
                yield_();
        }
        PublicAccount cardano;
        cardano.coin = "cardano";
        cardano.symbol = "ADA";
        cardano.name = "Cardano";
        cardano.blockchain = cardano.name;
        cardano.path = cardano_receiving_path;
        cardano.mode = mode;
        cardano.decimals = 6;
        cardano.curve = "ed25519ExtendedCardano";
        cardano.address_index = 0;
        if (crypto::HdError::None !=
            cardano_receiving_address(
                mnemonic, mode == PublicWalletMode::Hidden ? passphrase : std::string_view{},
                cardano.address))
            return VaultError::Crypto;
        rows.push_back(std::move(cardano));
        if (yield_)
            yield_();
    }
    std::vector<std::uint8_t> bytes{'P', 'U', 'B', 2};
    std::vector<std::string> dictionary;
    bytes.insert(bytes.end(), profile.public_id.begin(), profile.public_id.end());
    bytes.insert(bytes.end(), stable_id.begin(), stable_id.end());
    bytes.push_back(rows.size());
    for (const auto& row : rows) {
        bytes.push_back(row.mode == PublicWalletMode::Hidden);
        bytes.push_back(row.decimals);
        bytes.push_back(row.address_index.has_value());
        for (const auto* field : {&row.coin, &row.symbol, &row.name, &row.address, &row.xpub,
                                  &row.path, &row.blockchain, &row.curve})
            put(bytes, *field, dictionary);
    }
    std::array<std::uint8_t, 32> digest{};
    if (!crypto::sha256({bytes.data(), bytes.size()}, digest))
        return VaultError::Crypto;
    bytes.insert(bytes.end(), digest.begin(), digest.end());
    return store_.write_public(identifier(profile.public_id), {bytes.data(), bytes.size()})
               ? VaultError::None
               : VaultError::Storage;
}
VaultError SetupVault::public_accounts(std::size_t index, std::string& generation,
                                       std::vector<PublicAccount>& rows) {
    rows.clear();
    generation.clear();
    WalletRecord record{};
    WalletProfile profile;
    auto e = load(record, profile, index);
    if (e != VaultError::None)
        return e;
    generation = identifier(profile.public_id);
    std::vector<std::uint8_t> bytes;
    const auto read = store_.read_public(generation, bytes);
    if (read == StoreRead::Missing)
        return VaultError::Missing;
    std::array<std::uint8_t, 32> stable_id{};
    std::size_t count_position = 0;
    if (read != StoreRead::Found ||
        !cache_header(bytes, profile.public_id, stable_id, count_position))
        return VaultError::InvalidData;
    const auto count = bytes[count_position];
    std::size_t pos = count_position + 1;
    std::vector<PublicAccount> decoded;
    std::vector<std::string> dictionary;
    for (unsigned i = 0; i < count; ++i) {
        if (pos + 3 > bytes.size() - 32 || bytes[pos] > 1 || bytes[pos + 2] > 1)
            return VaultError::InvalidData;
        PublicAccount row;
        row.mode = bytes[pos++] ? PublicWalletMode::Hidden : PublicWalletMode::Standard;
        row.decimals = bytes[pos++];
        if (bytes[pos++])
            row.address_index = 0;
        for (auto* field : {&row.coin, &row.symbol, &row.name, &row.address, &row.xpub, &row.path,
                            &row.blockchain, &row.curve})
            if (!get(bytes, pos, *field, dictionary))
                return VaultError::InvalidData;
        decoded.push_back(std::move(row));
    }
    if (pos != bytes.size() - 32)
        return VaultError::InvalidData;
    rows = std::move(decoded);
    return VaultError::None;
}
PublicWalletError SetupVault::export_public_accounts(std::string_view generation,
                                                     const PublicWalletQuery& query,
                                                     std::size_t selected,
                                                     std::vector<PublicAccount>& rows) {
    rows.clear();
    std::string current;
    std::vector<PublicWallet> wallets;
    auto status = export_error(public_wallets(current, wallets));
    if (status != PublicWalletError::None)
        return status;
    if (generation != current)
        return PublicWalletError::GenerationChanged;
    if (!query.wallet_id.empty()) {
        const auto found = std::find_if(wallets.begin(), wallets.end(), [&](const auto& wallet) {
            return wallet.id == query.wallet_id;
        });
        if (found == wallets.end())
            return PublicWalletError::WalletNotFound;
        selected = static_cast<std::size_t>(found - wallets.begin());
    }
    if (selected >= wallets.size())
        return PublicWalletError::Unavailable;
    std::string record_generation;
    std::vector<PublicAccount> cached, result;
    status = export_error(public_accounts(selected, record_generation, cached));
    if (status != PublicWalletError::None)
        return status;
    for (const auto& row : cached)
        if (row.mode == query.mode &&
            std::find(query.coins.begin(), query.coins.end(), row.coin) != query.coins.end())
            result.push_back(row);
    if (!validate_public_accounts(query, result))
        return PublicWalletError::InvalidData;
    status = export_error(public_wallets(current, wallets));
    if (status != PublicWalletError::None)
        return status;
    if (generation != current)
        return PublicWalletError::GenerationChanged;
    rows = std::move(result);
    return PublicWalletError::None;
}
} // namespace airlock
