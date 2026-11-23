// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <optional>
#include <string_view>
namespace airlock {
// Keep existing numeric IDs stable; presentation order is independent.
enum class Language : std::size_t {
    English,
    Chinese,
    PortugueseBrazil,
    French,
    Italian,
    German,
    Spanish,
    Japanese
};
inline constexpr std::array<const char*, 8> language_names = {
    "English", "简体中文", "Português", "Français", "Italiano", "Deutsch", "Español", "日本語"};
inline constexpr std::array<const char*, 8> language_codes = {"en", "zh", "pt-BR", "fr",
                                                              "it", "de", "es",    "ja"};
inline constexpr std::array<Language, 8> language_order = {
    Language::PortugueseBrazil, Language::English,  Language::Spanish, Language::French,
    Language::Chinese,          Language::Japanese, Language::Italian, Language::German};
inline std::optional<Language> parse_language(std::string_view code) {
    for (std::size_t i = 0; i < language_codes.size(); ++i)
        if (code == language_codes[i])
            return static_cast<Language>(i);
    return std::nullopt;
}
inline bool uses_cjk(Language language) {
    return language == Language::Chinese || language == Language::Japanese;
}
} // namespace airlock
