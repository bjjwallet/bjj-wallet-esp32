// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/public_wallet_service.hpp"
#include "hardware/ble_framing.hpp"

namespace airlock {
// One instance per BLE connection, owned by its serialized transport task.
// Reconnect creates a new instance; losing security or failing a notification
// invalidates the old one. The transport owns pairing and subscription checks.
class PublicWalletRpc {
  public:
    PublicWalletRpc(PublicWalletService& service, PublicDeviceInfo info,
                    PublicWalletTransport& transport);
    ReceiveStatus receive(std::string_view fragment, std::uint64_t now_ms);
    ReceiveStatus poll(std::uint64_t now_ms);
    void disconnect();
    bool active() const {
        return active_;
    }

  private:
    PublicWalletService& service_;
    PublicDeviceInfo info_;
    PublicWalletTransport& transport_;
    BleFraming framing_;
    std::uint64_t connection_ = 0;
    bool active_ = false, stale_ = false, pin_verified_ = false;
    bool syncing_ = false;
    std::uint64_t now_ = 0, authorization_epoch_ = 0;
    std::string generation_;
    bool authorized() const;
    std::string dispatch(std::string_view request);
};
} // namespace airlock
