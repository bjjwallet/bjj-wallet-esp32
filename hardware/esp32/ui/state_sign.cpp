// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/state_sign.hpp"
#include <utility>

namespace airlock {
bool TransactionSignState::begin(SigningRequest request, std::uint64_t now) {
    if (stage_ != SignStage::Idle || request.review.empty() ||
        (request.payload.view().empty() && (request.monero != MoneroOperation::WatchWallet &&
                                            request.monero != MoneroOperation::ReceiveAddress)))
        return false;
    id_json_ = request.id_json;
    connection_ = request.connection;
    fields_ = request.review;
    pending_.emplace(std::move(request));
    reviewed_ = false;
    started_ms_ = now;
    ++token_;
    stage_ = SignStage::Review;
    return true;
}
bool TransactionSignState::set_local_phrase(std::string_view value) {
    return stage_ == SignStage::Review && pending_ &&
           (pending_->monero || pending_->local_phrase) && pending_->password.assign(value);
}
bool TransactionSignState::expired(std::uint64_t now) const {
    // Clock rollback also expires the request. Wall-clock time supplied over BLE
    // must never be used for this deadline; use esp_timer_get_time()/1000.
    return now < started_ms_ || now - started_ms_ > config::confirmation_timeout_ms;
}
bool TransactionSignState::mark_reviewed(std::uint64_t token) {
    if (stage_ != SignStage::Review || token != token_)
        return false;
    reviewed_ = true;
    return true;
}
std::optional<SigningJob> TransactionSignState::confirm(std::uint64_t token, std::uint64_t now) {
    if (stage_ != SignStage::Review || token != token_ || !reviewed_ || expired(now))
        return std::nullopt;
    SigningJob job{token_, std::move(*pending_)};
    pending_.reset();
    stage_ = SignStage::Signing;
    return job;
}
std::optional<SignOutcome> TransactionSignState::finish(SignEnd reason, std::string encoded) {
    if (stage_ == SignStage::Idle)
        return std::nullopt;
    SignOutcome outcome{std::move(id_json_), connection_, reason, std::move(encoded)};
    pending_.reset();
    fields_.clear();
    reviewed_ = false;
    stage_ = SignStage::Idle;
    return outcome;
}
std::optional<SignOutcome> TransactionSignState::reject() {
    if (stage_ != SignStage::Review)
        return std::nullopt;
    return finish(SignEnd::Rejected);
}
std::optional<SignOutcome> TransactionSignState::poll(std::uint64_t now) {
    if (stage_ == SignStage::Review && expired(now))
        return finish(SignEnd::TimedOut);
    return std::nullopt;
}
std::optional<SignOutcome> TransactionSignState::disconnect(std::uint64_t connection) {
    if (stage_ == SignStage::Idle || connection != connection_)
        return std::nullopt;
    // Does not stop an already running crypto worker; discards its completion.
    return finish(SignEnd::Disconnected);
}
std::optional<SignOutcome> TransactionSignState::completed(std::uint64_t token,
                                                           std::uint64_t connection,
                                                           std::string encoded, bool success) {
    if (stage_ != SignStage::Signing || token != token_ || connection != connection_) {
        wipe(encoded.data(), encoded.size());
        return std::nullopt;
    }
    if (!success || encoded.empty()) {
        wipe(encoded.data(), encoded.size());
        return finish(SignEnd::Failed);
    }
    return finish(SignEnd::Completed, std::move(encoded));
}
} // namespace airlock
