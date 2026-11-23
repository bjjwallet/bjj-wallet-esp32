// SPDX-License-Identifier: GPL-3.0-or-later
#include "lvgl.h"
#include "core/secret.hpp"
#include <algorithm>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <limits>

// LVGL text areas, keyboard buffers and backup labels can contain secrets.
// Reallocation also wipes the old allocation, unlike an ordinary realloc.
namespace {
struct alignas(std::max_align_t) Allocation {
    std::size_t size;
};
} // namespace
extern "C" {
void lv_mem_init() {}
void lv_mem_deinit() {}
lv_mem_pool_t lv_mem_add_pool(void*, size_t) {
    return nullptr;
}
void lv_mem_remove_pool(lv_mem_pool_t) {}
void* lv_malloc_core(size_t size) {
    if (size > std::numeric_limits<std::size_t>::max() - sizeof(Allocation))
        return nullptr;
    auto* header = static_cast<Allocation*>(std::calloc(1, sizeof(Allocation) + size));
    if (!header)
        return nullptr;
    header->size = size;
    return header + 1;
}
void lv_free_core(void* memory) {
    if (!memory)
        return;
    auto* header = static_cast<Allocation*>(memory) - 1;
    airlock::wipe(header, sizeof(Allocation) + header->size);
    std::free(header);
}
void* lv_realloc_core(void* memory, size_t size) {
    if (!memory)
        return lv_malloc_core(size);
    if (!size) {
        lv_free_core(memory);
        return nullptr;
    }
    auto* replacement = lv_malloc_core(size);
    if (!replacement)
        return nullptr;
    const auto* header = static_cast<Allocation*>(memory) - 1;
    std::memcpy(replacement, memory, std::min(size, header->size));
    lv_free_core(memory);
    return replacement;
}
void lv_mem_monitor_core(lv_mem_monitor_t* monitor) {
    // No fabricated heap totals. Use the platform allocator diagnostics on ESP32.
    std::memset(monitor, 0, sizeof(*monitor));
}
lv_result_t lv_mem_test_core() {
    return LV_RESULT_OK;
}
}
