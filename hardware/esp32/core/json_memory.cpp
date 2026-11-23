// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/json_memory.hpp"
#include "core/secret.hpp"
#include "cJSON.h"
#include <cstdlib>
#include <limits>
#include <mutex>
#include <string>

namespace airlock {
namespace {
struct alignas(std::max_align_t) JsonAllocation {
    std::size_t length;
};
void* json_alloc(std::size_t n) {
    if (n > std::numeric_limits<std::size_t>::max() - sizeof(JsonAllocation))
        return nullptr;
    auto* p = static_cast<JsonAllocation*>(std::malloc(sizeof(JsonAllocation) + n));
    if (!p)
        return nullptr;
    p->length = n;
    return p + 1;
}
} // namespace
void json_free(void* value) {
    if (!value)
        return;
    auto* p = static_cast<JsonAllocation*>(value) - 1;
    wipe(value, p->length);
    std::free(p);
}
void init_json() {
    static std::once_flag once;
    std::call_once(once, [] {
        cJSON_Hooks hooks{json_alloc, json_free};
        cJSON_InitHooks(&hooks);
    });
}
cJSON* parse_json(std::string_view value) {
    init_json();
    // cJSON exposes C strings; embedded NULs cannot be represented faithfully.
    if (value.find('\0') != std::string_view::npos)
        return nullptr;
    // Bound recursive parser stack use before calling the host/ESP-IDF cJSON.
    unsigned depth = 0;
    bool quoted = false, escaped = false;
    for (std::size_t i = 0; i < value.size(); ++i) {
        const char c = value[i];
        if (quoted) {
            if (static_cast<unsigned char>(c) < 0x20)
                return nullptr;
            if (escaped)
                escaped = false;
            else if (c == '\\') {
                // Reject a decoded NUL, but preserve a literal "\\u0000"
                // string (e.g. in a user's existing encrypted passphrase).
                if (value.substr(i, 6) == "\\u0000")
                    return nullptr;
                escaped = true;
            } else if (c == '"')
                quoted = false;
        } else if (c == '"')
            quoted = true;
        else if (c == '{' || c == '[') {
            if (++depth > 16)
                return nullptr;
        } else if (c == '}' || c == ']') {
            if (!depth)
                return nullptr;
            --depth;
        }
    }
    if (quoted || depth)
        return nullptr;
    std::string terminated(value);
    // cJSON writes a global parse-error pointer even when callers do not use it.
    static std::mutex parser_mutex;
    std::lock_guard<std::mutex> guard(parser_mutex);
    auto* parsed =
        cJSON_ParseWithLengthOpts(terminated.c_str(), terminated.size() + 1, nullptr, true);
    wipe(terminated.data(), terminated.size());
    return parsed;
}
} // namespace airlock
