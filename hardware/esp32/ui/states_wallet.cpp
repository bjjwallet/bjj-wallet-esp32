// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/states_wallet.hpp"
#include <algorithm>
#include <string>

namespace airlock {
namespace {
// Original str.strip()/lower() behavior for English BIP39 input, including the
// Unicode spaces accepted by Python and the Kelvin sign that lowercases to k.
// Never normalize/trim passwords or BIP39 passphrases here.
bool normalize_word(std::string_view input, SecretBuffer& result) {
    constexpr std::string_view whitespace[] = {" ",
                                               "\t",
                                               "\r",
                                               "\n",
                                               "\v",
                                               "\f",
                                               "\x1c",
                                               "\x1d",
                                               "\x1e",
                                               "\x1f",
                                               "\xc2\x85",
                                               "\xc2\xa0",
                                               "\xe1\x9a\x80",
                                               "\xe2\x80\x80",
                                               "\xe2\x80\x81",
                                               "\xe2\x80\x82",
                                               "\xe2\x80\x83",
                                               "\xe2\x80\x84",
                                               "\xe2\x80\x85",
                                               "\xe2\x80\x86",
                                               "\xe2\x80\x87",
                                               "\xe2\x80\x88",
                                               "\xe2\x80\x89",
                                               "\xe2\x80\x8a",
                                               "\xe2\x80\xa8",
                                               "\xe2\x80\xa9",
                                               "\xe2\x80\xaf",
                                               "\xe2\x81\x9f",
                                               "\xe3\x80\x80"};
    auto word = input;
    for (;;) {
        const auto before = word.size();
        for (const auto space : whitespace) {
            if (word.size() >= space.size() && word.substr(0, space.size()) == space)
                word.remove_prefix(space.size());
            if (word.size() >= space.size() && word.substr(word.size() - space.size()) == space)
                word.remove_suffix(space.size());
        }
        if (word.size() == before)
            break;
    }
    if (word.empty())
        return false;
    if (word.size() > 1024)
        return false;
    std::array<char, 1024> lower{};
    std::size_t length = 0;
    for (std::size_t i = 0; i < word.size(); ++i) {
        if (word.substr(i, 3) == "\xe2\x84\xaa") {
            lower[length++] = 'k';
            i += 2;
            continue;
        }
        const auto c = word[i];
        lower[length++] = c >= 'A' && c <= 'Z' ? static_cast<char>(c + ('a' - 'A')) : c;
    }
    const std::string_view normalized(lower.data(), length);
    const bool valid = result.assign(normalized);
    wipe(lower.data(), lower.size());
    return valid;
}
} // namespace
void WalletStates::clear_secrets() {
    passphrase_.clear();
    password_.clear();
    for (auto& word : words_)
        word.clear();
}
void WalletStates::cancel() {
    ++token_; // Late worker completions cannot alter a later UI session.
    in_flight_ = false;
    clear_secrets();
    word_index_ = 0;
    stage_ = WalletStage::Idle;
}
void WalletStates::first_stage() {
    stage_ = flow_ == WalletFlow::Create ? WalletStage::Passphrase : WalletStage::WordCount;
}
void WalletStates::begin(WalletFlow flow, bool exists) {
    cancel();
    flow_ = flow;
    word_count_ = config::mnemonic_words;
    if (exists)
        stage_ = WalletStage::Overwrite;
    else
        first_stage();
}
InputError WalletStates::confirm_overwrite() {
    if (stage_ != WalletStage::Overwrite)
        return InputError::WrongStage;
    // Original warning advances to input; reset occurs when the job starts.
    first_stage();
    return InputError::None;
}
InputError WalletStates::select_word_count(std::size_t count) {
    if (stage_ != WalletStage::WordCount)
        return InputError::WrongStage;
    if (!valid_word_count(count))
        return InputError::InvalidWordCount;
    word_count_ = count;
    stage_ = WalletStage::Password;
    return InputError::None;
}
InputError WalletStates::input(std::string_view value) {
    switch (stage_) {
        case WalletStage::Passphrase: {
            auto error = validate_credential(value, passphrase_rules);
            if (error != InputError::None)
                return error;
            passphrase_.assign(value);
            stage_ = WalletStage::ConfirmPassphrase;
            break;
        }
        case WalletStage::ConfirmPassphrase:
            if (!passphrase_.equals(value)) {
                passphrase_.clear();
                stage_ = WalletStage::Passphrase;
                return InputError::Mismatch;
            }
            stage_ = WalletStage::Password;
            break;
        case WalletStage::Password: {
            auto error = validate_credential(
                value, flow_ == WalletFlow::Import ? import_password_rules : password_rules);
            if (error != InputError::None)
                return error;
            password_.assign(value);
            stage_ = WalletStage::ConfirmPassword;
            break;
        }
        case WalletStage::ConfirmPassword:
            if (!password_.equals(value)) {
                password_.clear();
                stage_ = WalletStage::Password;
                return InputError::Mismatch;
            }
            if (flow_ == WalletFlow::Create && passphrase_.equals(value)) {
                password_.clear();
                stage_ = WalletStage::Password;
                return InputError::SameCredentials;
            }
            stage_ =
                flow_ == WalletFlow::Create ? WalletStage::Generating : WalletStage::ImportWord;
            word_index_ = 0;
            break;
        case WalletStage::ImportWord:
            if (value.size() > 1024)
                return InputError::TooLong;
            if (!normalize_word(value, words_[word_index_]))
                return InputError::InvalidWord;
            if (++word_index_ == word_count_)
                stage_ = WalletStage::ImportPassphrase;
            break;
        case WalletStage::ImportPassphrase:
            // Existing wallets may have an empty or weak passphrase. Applying the
            // creation rule to recovery would derive a different wallet or lock out.
            if (value.size() > 1024)
                return InputError::TooLong;
            if (!valid_text(value))
                return InputError::InvalidUtf8;
            passphrase_.assign(value);
            stage_ = WalletStage::Saving;
            break;
        case WalletStage::VerifyWord: {
            SecretBuffer normalized;
            if (!normalize_word(value, normalized) ||
                words_[word_index_].view() != normalized.view())
                return InputError::Mismatch;
            if (++word_index_ == word_count_)
                stage_ = WalletStage::VerifyPassphrase;
            break;
        }
        case WalletStage::VerifyPassphrase:
            if (!passphrase_.equals(value))
                return InputError::Mismatch;
            stage_ = WalletStage::VerifyPassword;
            break;
        case WalletStage::VerifyPassword:
            if (!password_.equals(value))
                return InputError::Mismatch;
            clear_secrets();
            stage_ = WalletStage::Done;
            break;
        default:
            return InputError::WrongStage;
    }
    return InputError::None;
}
void WalletStates::restart_passphrase() {
    if (flow_ == WalletFlow::Create && stage_ == WalletStage::Password && !in_flight_) {
        clear_secrets();
        stage_ = WalletStage::Passphrase;
    }
}
std::string_view WalletStates::verification_target() const {
    switch (stage_) {
        case WalletStage::VerifyWord:
            return words_[word_index_].view();
        case WalletStage::VerifyPassphrase:
            return passphrase_.view();
        case WalletStage::VerifyPassword:
            return password_.view();
        default:
            return {};
    }
}
InputError WalletStates::begin_backup_verification() {
    if (stage_ != WalletStage::Backup)
        return InputError::WrongStage;
    word_index_ = 0;
    stage_ = WalletStage::VerifyWord;
    return InputError::None;
}
std::string_view WalletStates::backup_word(std::size_t index) const {
    if (stage_ != WalletStage::Backup || index >= word_count_)
        return {};
    return words_[index].view();
}
std::optional<WalletJob> WalletStates::take_job() {
    if (in_flight_ || (stage_ != WalletStage::Generating && stage_ != WalletStage::Saving))
        return std::nullopt;
    WalletJob job;
    job.token = ++token_;
    job.kind = stage_ == WalletStage::Generating ? WalletWork::Generate : WalletWork::Import;
    job.word_count = word_count_;
    job.passphrase.assign(passphrase_.view());
    // Like generate_mnemonic in the original, generation includes encryption,
    // account derivation and storage BEFORE the backup screen is displayed.
    job.password.assign(password_.view());
    if (job.kind != WalletWork::Generate) {
        for (std::size_t i = 0; i < word_count_; ++i)
            job.words[i].assign(words_[i].view());
    }
    in_flight_ = true;
    return job;
}
bool WalletStates::generated(std::uint64_t token, const std::array<std::string_view, 24>& words) {
    if (!in_flight_ || token != token_ || stage_ != WalletStage::Generating)
        return false;
    if (!std::all_of(words.begin(), words.end(), is_mnemonic_word))
        return false;
    for (std::size_t i = 0; i < words.size(); ++i)
        words_[i].assign(words[i]);
    in_flight_ = false;
    stage_ = WalletStage::Backup;
    return true;
}
bool WalletStates::saved(std::uint64_t token) {
    if (!in_flight_ || token != token_ || stage_ != WalletStage::Saving)
        return false;
    clear_secrets();
    in_flight_ = false;
    stage_ = WalletStage::Done;
    return true;
}
bool WalletStates::failed(std::uint64_t token) {
    if (!in_flight_ || token != token_)
        return false;
    in_flight_ = false;
    if (flow_ == WalletFlow::Create) {
        clear_secrets();
        stage_ = WalletStage::Passphrase;
    } else {
        for (auto& word : words_)
            word.clear();
        passphrase_.clear();
        word_index_ = 0;
        stage_ = WalletStage::ImportWord;
    }
    return true;
}
} // namespace airlock
