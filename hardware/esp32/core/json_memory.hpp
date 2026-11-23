// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <string_view>
struct cJSON;

namespace airlock {
// One allocator setup shared by public RPC and the native encrypted keystore.
// cJSON hooks must never change after the first allocation.
void init_json();
void json_free(void* value);
cJSON* parse_json(std::string_view value);
} // namespace airlock
