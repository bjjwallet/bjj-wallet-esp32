// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/public_wallet_service.hpp"
#include "ui/state_sign.hpp"

namespace airlock {
struct TrustedDevice {
    std::string name, id;
    bool connected = false;
    bool blocked = false;
};
struct PairingRequest {
    std::uint64_t token;
    std::uint32_t passkey;
};
struct TransactionPrompt {
    SigningRequest request;
    std::string symbol, coin, amount, recipient;
    std::string fee, change, change_address, fee_symbol, wallet_name, mode;
    std::string native_amount;
    unsigned decimals = 0;
};
struct SigningResult {
    std::uint64_t token, connection;
    std::string encoded, error;
    bool success = false;
};

// Boundary between authenticated transport, physical approval, and hardware signing.
// The transport binds requests to their connection; the signing worker validates
// the payment against the physical review before producing a signature.
class DeviceService : public PublicWalletTransport {
  public:
    virtual ~DeviceService() = default;
    virtual std::vector<TrustedDevice> trusted_devices() = 0;
    virtual std::uint64_t devices_revision() const = 0;
    virtual bool remove_device(std::string_view id) = 0;
    virtual bool block_device(std::string_view) {
        return false;
    }
    virtual std::optional<PairingRequest> poll_pairing() = 0;
    virtual bool pairing_active(std::uint64_t token) const = 0;
    virtual void respond_pairing(std::uint64_t token, bool accept) = 0;
    virtual std::optional<TransactionPrompt> poll_transaction() = 0;
    virtual bool connected(std::uint64_t connection) const = 0;
    virtual bool sign(SigningJob job) = 0;
    virtual std::optional<SigningResult> poll_signature() = 0;
    virtual void respond_transaction(SignOutcome outcome, std::string_view error) = 0;
};
} // namespace airlock
