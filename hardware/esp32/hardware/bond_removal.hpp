// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
namespace airlock {
// NimBLE cannot remove an IRK from the resolving list while advertising.
// Call on the host task so GAP callbacks cannot restart advertising mid-delete.
template <class Stop, class Remove, class Resume>
int without_advertising(bool advertising, Stop stop, Remove remove, Resume resume) {
    if (advertising) {
        const int status = stop();
        if (status)
            return status;
    }
    const int status = remove();
    if (advertising)
        resume();
    return status;
}
} // namespace airlock
