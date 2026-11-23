// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/secret.hpp"
#include <cstddef>
#include <cstdlib>
#include <limits>
#ifdef ESP_PLATFORM
#include "esp_heap_caps.h"
#endif

namespace airlock {
// Objects carrying credentials must not spill to external PSRAM through the
// generic >4096-byte malloc policy. Wipe the entire allocation on every delete.
class InternalSecretAllocation {
    struct alignas(std::max_align_t) Header {
        std::size_t size;
    };

  public:
    static void* operator new(std::size_t size, const std::nothrow_t&) noexcept {
        if (size > std::numeric_limits<std::size_t>::max() - sizeof(Header))
            return nullptr;
#ifdef ESP_PLATFORM
        auto* header = static_cast<Header*>(
            heap_caps_calloc(1, sizeof(Header) + size, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
#else
        auto* header = static_cast<Header*>(std::calloc(1, sizeof(Header) + size));
#endif
        if (!header)
            return nullptr;
        header->size = size;
        return header + 1;
    }
    static void* operator new(std::size_t size) {
        auto* object = operator new(size, std::nothrow);
        if (!object)
            std::abort();
        return object;
    }
    static void operator delete(void* object) noexcept {
        if (!object)
            return;
        auto* header = static_cast<Header*>(object) - 1;
        wipe(header, sizeof(Header) + header->size);
#ifdef ESP_PLATFORM
        heap_caps_free(header);
#else
        std::free(header);
#endif
    }
    static void operator delete(void* object, const std::nothrow_t&) noexcept {
        operator delete(object);
    }
};
} // namespace airlock
