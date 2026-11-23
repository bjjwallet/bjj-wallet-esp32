// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <functional>
#include <string>
#include <string_view>
#include <vector>
namespace airlock {
struct DeviceAccess {
    std::string id;
    bool blocked = false;
};
// Versioned, atomic NVS value. Blocked IDs are independent of the BLE bond cache.
class DeviceAccessList {
    std::vector<DeviceAccess> entries_;
    static bool valid_id(std::string_view id) {
        if (id.size() != 19 || (id[0] != '0' && id[0] != '1') || id[1] != ':')
            return false;
        for (std::size_t i = 2; i < id.size(); ++i) {
            if ((i - 2) % 3 == 2) {
                if (id[i] != ':')
                    return false;
            } else if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f')))
                return false;
        }
        return true;
    }

  public:
    const std::vector<DeviceAccess>& entries() const {
        return entries_;
    }
    bool blocked(std::string_view id) const {
        for (const auto& entry : entries_)
            if (entry.id == id)
                return entry.blocked;
        return false;
    }
    bool decode(std::string_view data) {
        if (data.substr(0, 8) != "BJJDEV1\n")
            return false;
        std::vector<DeviceAccess> next;
        for (std::size_t i = 8; i < data.size(); i += 21) {
            if (i + 21 > data.size() || (data[i] != 'A' && data[i] != 'B') || data[i + 20] != '\n')
                return false;
            const auto id = data.substr(i + 1, 19);
            if (!valid_id(id) ||
                std::any_of(next.begin(), next.end(), [&](const auto& e) { return e.id == id; }))
                return false;
            next.push_back({std::string(id), data[i] == 'B'});
        }
        entries_ = std::move(next);
        return true;
    }
    bool update(std::string_view id, bool blocked, bool remove,
                const std::function<bool(std::string_view)>& persist) {
        if (!valid_id(id))
            return false;
        auto next = entries_;
        auto found =
            std::find_if(next.begin(), next.end(), [&](const auto& e) { return e.id == id; });
        if (!remove && !blocked && found != next.end() && found->blocked)
            return false;
        if (remove) {
            if (found != next.end())
                next.erase(found);
        } else if (found != next.end())
            found->blocked = blocked;
        else
            next.push_back({std::string(id), blocked});
        std::string data = "BJJDEV1\n";
        for (const auto& e : next) {
            data += e.blocked ? 'B' : 'A';
            data += e.id;
            data += '\n';
        }
        if (!persist(data))
            return false;
        entries_ = std::move(next);
        return true;
    }
};
} // namespace airlock
