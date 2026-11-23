// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstddef>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace airlock {
enum class InputError {
    None,
    TooLong,
    InvalidUtf8,
    TooShort,
    Uppercase,
    Lowercase,
    Digit,
    Special,
    Mismatch,
    SameCredentials,
    InvalidWord,
    InvalidWordCount,
    WrongStage
};
struct Rules {
    std::size_t min_length;
    bool upper = true;
    bool lower = true;
    bool digit = true;
    bool special = true;
};
inline constexpr Rules passphrase_rules{16};
inline constexpr Rules password_rules{12};
// states_wallet.py::_validate_rules checks length, upper, lower and digit only.
inline constexpr Rules import_password_rules{12, true, true, true, false};
std::vector<InputError> credential_errors(std::string_view value, Rules rules);
InputError validate_credential(std::string_view value, Rules rules);
bool valid_text(std::string_view value);
bool is_mnemonic_word(std::string_view word);
bool valid_word_count(std::size_t count);
// Amounts never pass through float, double, or a bounded integer conversion.
bool is_unsigned_decimal(std::string_view value);
std::optional<std::string> format_amount(std::string_view raw, unsigned decimals);
} // namespace airlock
