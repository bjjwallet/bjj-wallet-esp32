// SPDX-License-Identifier: GPL-3.0-or-later
// Port of the confirmation lifecycle in ui/state_sign.py.
#pragma once
#include "core/config.hpp"
#include "core/public_wallet_service.hpp"
#include "core/secret.hpp"
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace airlock {
struct ReviewField {
    std::string label;
    std::string value;
};
struct SigningRequest {
    // Serialized original JSON request id, preserving number/string/null type.
    std::string id_json;
    std::uint64_t connection = 0; // Changes on every BLE connection, even same peer.
    std::vector<ReviewField> review;
    // Owned, immutable request. The real signer must validate registry metadata,
    // derivation path, ownership, recipient, contract, fees and change address.
    // This lifecycle object is NOT a transaction validator or signer.
    SecretBuffer payload;
    Credential password;
    bool local_phrase = false;
    std::optional<MoneroOperation> monero;
    PublicWalletMode monero_mode = PublicWalletMode::Standard;
    std::string wallet_id, monero_recipient;
    std::uint64_t monero_amount = 0, monero_fee = 0;
};
struct SigningJob {
    std::uint64_t token;
    SigningRequest request;
};
enum class SignStage { Idle, Review, Signing };
enum class SignEnd { Rejected, TimedOut, Disconnected, Completed, Failed };
struct SignOutcome {
    std::string id_json;
    std::uint64_t connection;
    SignEnd reason;
    // Set only after the hardware signing worker completes successfully.
    std::string encoded;
};
class TransactionSignState {
  public:
    bool begin(SigningRequest request, std::uint64_t now_ms);
    bool mark_reviewed(std::uint64_t token);
    bool set_local_phrase(std::string_view value);
    std::optional<SigningJob> confirm(std::uint64_t token, std::uint64_t now_ms);
    std::optional<SignOutcome> reject();
    std::optional<SignOutcome> poll(std::uint64_t now_ms);
    std::optional<SignOutcome> disconnect(std::uint64_t connection);
    std::optional<SignOutcome> completed(std::uint64_t token, std::uint64_t connection,
                                         std::string encoded, bool success);
    const std::vector<ReviewField>& fields() const {
        return fields_;
    }
    SignStage stage() const {
        return stage_;
    }
    std::uint64_t token() const {
        return token_;
    }

  private:
    SignStage stage_ = SignStage::Idle;
    std::uint64_t token_ = 0;
    std::uint64_t started_ms_ = 0;
    std::uint64_t connection_ = 0;
    bool reviewed_ = false;
    std::string id_json_;
    std::vector<ReviewField> fields_;
    std::optional<SigningRequest> pending_;
    std::optional<SignOutcome> finish(SignEnd reason, std::string encoded = {});
    bool expired(std::uint64_t now_ms) const;
};
} // namespace airlock
