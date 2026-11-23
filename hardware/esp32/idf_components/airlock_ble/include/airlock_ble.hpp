// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/device_service.hpp"
#include "esp_err.h"
#include <memory>
#include <functional>

namespace airlock {
// Peripheral for the existing AirLock GATT service. Public RPC runs in a separate
// task; GAP callbacks never wait for SQL/storage, a user decision or TX pacing.
// The supplied storage backend must outlive this service.
class NimbleDeviceService final : public DeviceService {
  public:
    NimbleDeviceService(PublicWalletService& wallet, PublicDeviceInfo info,
                        std::function<std::string()> temperature = {});
    ~NimbleDeviceService() override;
    esp_err_t start();
    void stop();
    void set_unlocked(bool unlocked);
    void set_device_name(std::string_view name);
    PublicPeer public_peer() const override;
    PublicDeviceInfo read_device_info(const PublicDeviceInfo& fallback) const override;
    std::string connection_identity() const;
    bool notify_public(std::string_view fragment) override;
    void public_sync_progress(bool active) override;
    bool public_sync_active() const;
    std::vector<TrustedDevice> trusted_devices() override;
    std::uint64_t devices_revision() const override;
    bool remove_device(std::string_view id) override;
    bool block_device(std::string_view id) override;
    bool accept_current_device(std::uint64_t connection);
    bool reject_current_device(std::uint64_t connection);
    std::optional<PairingRequest> poll_pairing() override;
    bool pairing_active(std::uint64_t token) const override;
    void respond_pairing(std::uint64_t token, bool accept) override;
    bool connected(std::uint64_t connection) const override;
    std::string request_signature(std::string_view, std::uint64_t, std::string&) override;
    std::string request_monero(const MoneroRequest&, std::uint64_t, std::string&) override;
    std::optional<TransactionPrompt> poll_transaction() override;
    bool sign(SigningJob) override;
    std::optional<SigningResult> poll_signature() override;
    void respond_transaction(SignOutcome, std::string_view) override;

  private:
    std::string wait_for_signature(TransactionPrompt&&, std::uint64_t, std::string&);
    struct State;
    std::unique_ptr<State> state_;
};
} // namespace airlock
