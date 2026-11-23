// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace airlock {
struct TransactionPrompt;
enum class PublicWalletMode { Standard, Hidden };
enum class MoneroOperation { WatchWallet, ReceiveAddress, KeyImages, SignTransfer };
struct MoneroRequest {
    MoneroOperation operation;
    PublicWalletMode mode;
    std::string_view wallet_id, archive_hex;
    std::string_view recipient{};
    std::uint64_t amount = 0, fee = 0;
};
inline constexpr std::size_t monero_max_archive_hex = 2 * (256 * 1024 + 93);
enum class PublicWalletError {
    None,
    Unavailable,
    Busy,
    GenerationChanged,
    InvalidData,
    InvalidParams,
    ApprovalRequired,
    PinRequired,
    PinLocked,
    Internal,
    WalletNotFound
};

struct PublicAccount {
    std::string coin, symbol, name, address, xpub, path, blockchain, curve;
    unsigned decimals = 0;
    PublicWalletMode mode = PublicWalletMode::Standard;
    std::optional<std::uint32_t> address_index;
};
struct PublicWallet {
    std::string id, name;
    std::size_t word_count = 0;
    bool passphrase_enabled = false, imported = false;
    bool monero_enabled = true;
};
struct PublicWalletQuery {
    PublicWalletMode mode = PublicWalletMode::Standard;
    bool include_all_addresses = false;
    std::vector<std::string> coins;
    std::string wallet_id{};
    bool all_wallets = false;
};
struct PublicDeviceInfo {
    // Empty fields are omitted. Version means firmware/application version,
    // never the Wallet Core dependency version.
    std::string name, version, hardware, screen, cpu;
    std::string platform{};
};
// Checks the public schema, requested scope, uniqueness and index-zero presence,
// then orders cached addresses by index. Failure must discard the whole result.
bool validate_public_accounts(const PublicWalletQuery& query, std::vector<PublicAccount>& accounts);

// Public exports and hardware-confirmed signing share link authorization.
// A backend resolves its own wallet generation; no seed/private key leaves it.
class PublicWalletService {
  public:
    virtual bool brz_signing_supported() const {
        return false;
    }
    virtual bool polygon_signing_supported() const {
        return false;
    }
    virtual bool ethereum_native_signing_supported() const {
        return false;
    }
    virtual bool solana_signing_supported() const {
        return false;
    }
    virtual bool cardano_signing_supported() const {
        return false;
    }
    virtual bool utxo_signing_supported() const {
        return false;
    }
    virtual bool monero_sync_supported() const {
        return false;
    }
    virtual bool monero_transfer_supported() const {
        return false;
    }
    virtual std::string monero_wallet_name(std::string_view) {
        return {};
    }
    virtual bool run_monero(const MoneroRequest&, std::string_view, std::string&, std::string&) {
        return false;
    }
    virtual bool review_signing(std::string_view, TransactionPrompt&) {
        return false;
    }
    virtual bool sign_transaction(std::string_view, std::string_view, std::string&) {
        return false;
    }
    virtual ~PublicWalletService() = default;
    // Approval is supplied by the local device UI, never by request parameters.
    virtual std::uint64_t public_authorization_epoch() const {
        return 0;
    }
    virtual PublicWalletError public_access(std::uint64_t, bool& pin_required) {
        pin_required = false;
        return PublicWalletError::None;
    }
    virtual PublicWalletError verify_public_pin(std::uint64_t, std::string_view, std::uint64_t,
                                                bool& accepted) {
        accepted = false;
        return PublicWalletError::InvalidParams;
    }
    virtual bool multi_wallet_sync_supported() const {
        return false;
    }
    virtual PublicWalletError public_wallets(std::string_view,
                                             std::vector<PublicWallet>& wallets) const {
        wallets.clear();
        return PublicWalletError::Unavailable;
    }
    virtual PublicWalletError public_generation(std::string& generation) const = 0;
    virtual PublicWalletError public_accounts(std::string_view generation,
                                              const PublicWalletQuery& query,
                                              std::vector<PublicAccount>& accounts) const = 0;
};

struct PublicPeer {
    std::uint64_t connection = 0;
    bool connected = false, authenticated = false, encrypted = false;
    std::uint16_t mtu = 23;
};
// Implemented by the platform transport using actual link security, not fields
// supplied in JSON. notify_public must honor backpressure and return false on
// disconnect/failure. Defaults deliberately deny all traffic.
class PublicWalletTransport {
  public:
    virtual std::string request_monero(const MoneroRequest&, std::uint64_t, std::string& error) {
        error = "MONERO_UNAVAILABLE";
        return {};
    }
    virtual std::string request_signature(std::string_view, std::uint64_t, std::string& error) {
        error = "UNSUPPORTED_SIGNING";
        return {};
    }
    virtual ~PublicWalletTransport() = default;
    virtual PublicPeer public_peer() const {
        return {};
    }
    // Refresh public telemetry for every request; static adapters use the fallback.
    virtual PublicDeviceInfo read_device_info(const PublicDeviceInfo& fallback) const {
        return fallback;
    }
    virtual void public_sync_progress(bool) {}
    virtual bool notify_public(std::string_view) {
        return false;
    }
};
} // namespace airlock
