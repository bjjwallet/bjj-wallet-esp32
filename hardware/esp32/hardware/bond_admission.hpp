// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>

namespace airlock {
// A full bond cache may rotate only for a locally approved pairing on this link.
// A previously bonded phone's ordinary connection approval is not sufficient.
class BondAdmission {
    std::uint64_t approved_connection_ = 0;

  public:
    void reset() {
        approved_connection_ = 0;
    }
    void approve_pairing(std::uint64_t connection) {
        approved_connection_ = connection;
    }
    bool may_rotate(std::uint64_t connection, bool connected, bool secure) const {
        return connection != 0 && connection == approved_connection_ && connected && secure;
    }
};
} // namespace airlock
