// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/secret.hpp"
#include "monero_signer/ffi.h"
#include <esp_heap_caps.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

extern "C" void* airlock_monero_alloc(std::size_t size, std::size_t alignment) {
    return heap_caps_aligned_alloc(std::max(alignment, alignof(void*)), size,
                                   MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
}

extern "C" void airlock_monero_free(void* pointer, std::size_t size) {
    airlock::wipe(pointer, size);
    heap_caps_free(pointer);
}

extern "C" [[noreturn]] void airlock_monero_panic(const char* file, std::size_t length,
                                                  std::uint32_t line) {
    // Only the code location is logged. Panic messages can contain sensitive data.
    std::printf("Monero signer failure at %.*s:%u\n", int(length), file, unsigned(line));
    std::fflush(stdout);
    std::abort();
}

int32_t airlock_monero_sign_on_worker(const AirlockMoneroSignRequest& request, uint8_t* raw,
                                      size_t capacity, size_t& written, uint8_t* txid) {
    // The upstream generated Bulletproof+ initializer constructs two large
    // point arrays on the stack. Reserve PSRAM for that initializer.
    constexpr std::size_t stack_bytes = 192 * 1024;
    auto* stack = static_cast<StackType_t*>(
        heap_caps_malloc(stack_bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    if (!stack)
        return 6;

    StaticSemaphore_t storage{};
    StaticTask_t tcb{};
    struct Job {
        const AirlockMoneroSignRequest& request;
        uint8_t* raw;
        size_t capacity;
        size_t& written;
        uint8_t* txid;
        SemaphoreHandle_t done;
        int32_t status = 6;
    } job{request, raw, capacity, written, txid, xSemaphoreCreateBinaryStatic(&storage)};
    const auto task = xTaskCreateStaticPinnedToCore(
        [](void* argument) {
            auto& current = *static_cast<Job*>(argument);
            current.status = airlock_monero_sign_archive(
                &current.request, current.raw, current.capacity, &current.written, current.txid);
            xSemaphoreGive(current.done);
            // Only the caller deletes the task and wipes its stack.
            vTaskSuspend(nullptr);
        },
        "monero_prove", stack_bytes, &job, tskIDLE_PRIORITY, stack, &tcb, xPortGetCoreID());

    if (task) {
        xSemaphoreTake(job.done, portMAX_DELAY);
        while (eTaskGetState(task) != eSuspended)
            vTaskDelay(1);
        vTaskDelete(task);
    }
    vSemaphoreDelete(job.done);
    airlock::wipe(stack, stack_bytes);
    heap_caps_free(stack);
    return job.status;
}
