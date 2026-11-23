// SPDX-License-Identifier: GPL-3.0-or-later
#include "core/rules.hpp"
#include "core/config.hpp"
#include "wordlist.hpp"
#include <algorithm>

namespace airlock {
namespace {
bool text_length(std::string_view value, std::size_t& count) {
    count = 0;
    for (std::size_t i = 0; i < value.size();) {
        auto c = static_cast<unsigned char>(value[i++]);
        // Embedded NUL/control bytes cannot pass to Wallet Core C strings/UI.
        if (c < 0x20 || c == 0x7f)
            return false;
        unsigned continuation = 0;
        unsigned code = c;
        unsigned minimum = 0;
        if (c < 0x80) {
        } else if ((c & 0xe0) == 0xc0) {
            continuation = 1;
            code = c & 0x1f;
            minimum = 0x80;
        } else if ((c & 0xf0) == 0xe0) {
            continuation = 2;
            code = c & 0x0f;
            minimum = 0x800;
        } else if ((c & 0xf8) == 0xf0) {
            continuation = 3;
            code = c & 0x07;
            minimum = 0x10000;
        } else
            return false;
        while (continuation--) {
            if (i == value.size())
                return false;
            c = static_cast<unsigned char>(value[i++]);
            if ((c & 0xc0) != 0x80)
                return false;
            code = (code << 6) | (c & 0x3f);
        }
        if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff))
            return false;
        ++count;
    }
    return true;
}
} // namespace
bool valid_text(std::string_view value) {
    std::size_t count;
    return value.size() <= 1024 && text_length(value, count);
}
std::vector<InputError> credential_errors(std::string_view value, Rules rules) {
    if (value.size() > 1024)
        return {InputError::TooLong};
    std::size_t count;
    if (!text_length(value, count))
        return {InputError::InvalidUtf8};
    std::vector<InputError> errors;
    if (count < rules.min_length)
        errors.push_back(InputError::TooShort);
    bool upper = false, lower = false, digit = false, special = false;
    for (unsigned char c : value) {
        upper |= c >= 'A' && c <= 'Z';
        lower |= c >= 'a' && c <= 'z';
        digit |= c >= '0' && c <= '9';
        special |= !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'));
    }
    if (rules.upper && !upper)
        errors.push_back(InputError::Uppercase);
    if (rules.lower && !lower)
        errors.push_back(InputError::Lowercase);
    if (rules.digit && !digit)
        errors.push_back(InputError::Digit);
    if (rules.special && !special)
        errors.push_back(InputError::Special);
    return errors;
}
InputError validate_credential(std::string_view value, Rules rules) {
    const auto errors = credential_errors(value, rules);
    return errors.empty() ? InputError::None : errors.front();
}
bool valid_word_count(std::size_t count) {
    return std::find(config::import_word_counts.begin(), config::import_word_counts.end(), count) !=
           config::import_word_counts.end();
}
bool is_mnemonic_word(std::string_view word) {
    return std::binary_search(words.begin(), words.end(), word);
}
bool is_unsigned_decimal(std::string_view value) {
    return !value.empty() &&
           std::all_of(value.begin(), value.end(), [](char c) { return c >= '0' && c <= '9'; });
}
std::optional<std::string> format_amount(std::string_view raw, unsigned decimals) {
    if (!is_unsigned_decimal(raw) || raw.size() > 1024 || decimals > 255)
        return std::nullopt;
    auto first = raw.find_first_not_of('0');
    std::string value(first == std::string_view::npos ? "0" : raw.substr(first));
    if (!decimals)
        return value;
    if (value.size() <= decimals)
        value.insert(0, decimals + 1 - value.size(), '0');
    value.insert(value.size() - decimals, 1, '.');
    while (value.back() == '0')
        value.pop_back();
    if (value.back() == '.')
        value.pop_back();
    return value;
}
} // namespace airlock
