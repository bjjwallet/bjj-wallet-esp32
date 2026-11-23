// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const uint8_t* plaintext;
    size_t plaintext_len;
    const uint8_t* spend;
    const uint8_t* view;
    const uint8_t* entropy;
    const uint8_t* destination;
    size_t destination_len;
    uint64_t amount;
    uint64_t fee;
    bool stagenet;
} AirlockMoneroSignRequest;

// spend, view, entropy and txid are exactly 32 bytes each. All buffers must
// be disjoint. Returns zero only when it writes a complete transaction.
int32_t airlock_monero_sign_archive(const AirlockMoneroSignRequest* request, uint8_t* raw,
                                    size_t capacity, size_t* written, uint8_t* txid);

#ifdef __cplusplus
}
#endif
