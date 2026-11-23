// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/wallet_app.hpp"
#include "original_strings.hpp"
#include <cstdio>
#include <cstring>
#include <string>
#include <utility>

namespace airlock {
namespace {
std::string numbered(const char* pattern, std::size_t first, std::size_t second = 0) {
    std::string result(pattern);
    for (auto value : {first, second}) {
        const auto p = result.find("{}");
        if (p == std::string::npos)
            break;
        result.replace(p, 2, std::to_string(value));
    }
    return result;
}
std::vector<std::string> lines(const char* text) {
    std::vector<std::string> result;
    std::string_view rest(text);
    while (!rest.empty()) {
        const auto end = rest.find('\n');
        result.emplace_back(rest.substr(0, end));
        if (end == std::string_view::npos)
            break;
        rest.remove_prefix(end + 1);
    }
    return result;
}
std::string interpolate(std::string_view pattern, std::initializer_list<std::string_view> values) {
    std::string result;
    std::size_t offset = 0;
    for (const auto value : values) {
        const auto pos = pattern.find("{}", offset);
        if (pos == std::string_view::npos)
            break;
        result.append(pattern.substr(offset, pos - offset));
        result.append(value);
        offset = pos + 2;
    }
    result.append(pattern.substr(offset));
    return result;
}
} // namespace
WalletApp::WalletApp(lv_obj_t* screen, WalletService& service, DeviceService* device)
    : view_(screen), service_(service), device_(device) {
    menu();
}
const char* WalletApp::t(const char* group, const char* key) const {
    return original_text(group, key, language_);
}
const char* WalletApp::choice(const char* en, const char* zh) const {
    return custom_text(en, zh, language_);
}
void WalletApp::choose_language(bool required) {
    require_language_ = required;
    page_ = WalletPage::Language;
    std::vector<std::string> names;
    for (auto language : language_order)
        names.emplace_back(language_names[static_cast<std::size_t>(language)]);
    view_.menu(t("menu", "lang_title"), names, !required);
}
void WalletApp::set_language(Language language) {
    language_ = language;
    view_.language(language);
    menu();
}
void WalletApp::menu() {
    wallet_.cancel();
    page_ = WalletPage::Menu;
    view_.menu(config::product_name, lines(t("menu", "menu_items")));
}
void WalletApp::cancel() {
    if (page_ == WalletPage::Language && require_language_)
        return;
    if (page_ == WalletPage::Pairing) {
        if (pairing_ && device_->pairing_active(pairing_->token))
            device_->respond_pairing(pairing_->token, false);
        pairing_.reset();
        menu();
        return;
    }
    if (page_ == WalletPage::RemoveDevice) {
        connections();
        return;
    }
    if (page_ == WalletPage::ReviewTransaction) {
        if (auto outcome = signing_.reject())
            sign_finished(std::move(*outcome));
        return;
    }
    if (page_ == WalletPage::Signing || page_ == WalletPage::SignFeedback)
        return;
    // The original generation/import calls are blocking; its backup list has no
    // Escape handler. Keep exactly those navigation boundaries on the touch UI.
    if (page_ == WalletPage::Busy || page_ == WalletPage::Backup)
        return;
    if (page_ == WalletPage::Intro && wallet_.flow() == WalletFlow::Create &&
        wallet_.stage() == WalletStage::Password) {
        wallet_.restart_passphrase();
        draw_wallet(true);
        return;
    }
    menu();
}
AppAction WalletApp::take_action() {
    const auto action = action_;
    action_ = AppAction::None;
    return action;
}
void WalletApp::begin(WalletFlow flow) {
    if (!service_.available() || service_.busy()) {
        page_ = WalletPage::Unavailable;
        view_.text(
            config::product_name,
            choice("Wallet storage is unavailable or another operation is still running. Close "
                   "other instances using this wallet and retry.",
                   "钱包存储不可用，或另一个操作仍在进行。请关闭使用此钱包的其他程序后重试。"),
            choice("Back", "返回"));
        return;
    }
    wallet_.begin(flow, service_.exists());
    draw_wallet(true);
}
void WalletApp::draw_wallet(bool intro) {
    const auto stage = wallet_.stage();
    if (stage == WalletStage::Overwrite) {
        page_ = WalletPage::Overwrite;
        view_.text(t("wallet", "warning_title"),
                   t("wallet",
                     wallet_.flow() == WalletFlow::Create ? "warning_overwrite" : "import_warning"),
                   choice("Continue", "继续"));
        return;
    }
    if (stage == WalletStage::WordCount) {
        page_ = WalletPage::WordCount;
        std::vector<std::string> items;
        for (auto count : config::import_word_counts)
            items.push_back(numbered(t("wallet", "word_count_option"), count));
        view_.menu(choice("Import Wallet", "导入钱包"), items, true);
        return;
    }
    if (stage == WalletStage::Generating || stage == WalletStage::Saving) {
        launch_job();
        return;
    }
    if (stage == WalletStage::Backup) {
        if (intro) {
            page_ = WalletPage::BackupIntro;
            view_.text(choice("Back up your wallet", "备份钱包"), t("wallet", "backup_intro"),
                       choice("Start", "开始"));
        } else {
            page_ = WalletPage::Backup;
            std::array<char, 1025> text{};
            std::size_t offset = 0;
            for (std::size_t i = 0; i < wallet_.word_count(); ++i) {
                const auto word = wallet_.backup_word(i);
                const auto n = std::snprintf(text.data() + offset, text.size() - offset,
                                             "%02u. %.*s\n", static_cast<unsigned>(i + 1),
                                             static_cast<int>(word.size()), word.data());
                if (n <= 0 || static_cast<std::size_t>(n) >= text.size() - offset) {
                    wipe(text.data(), text.size());
                    menu();
                    return;
                }
                offset += static_cast<std::size_t>(n);
            }
            view_.text(choice("24 recovery words", "24个助记词"), text.data(),
                       choice("Verify", "验证"), true, false);
            wipe(text.data(), text.size());
        }
        return;
    }
    if (stage == WalletStage::Done) {
        page_ = WalletPage::Success;
        view_.text(
            config::product_name,
            t("wallet", wallet_.flow() == WalletFlow::Create ? "success_msg" : "import_success"),
            choice("Menu", "菜单"));
        return;
    }
    const char* intro_key = nullptr;
    if (stage == WalletStage::Passphrase)
        intro_key = "step1_intro";
    if (stage == WalletStage::Password)
        intro_key = wallet_.flow() == WalletFlow::Create ? "step3_intro" : "import_step2_intro";
    if (stage == WalletStage::ImportWord && wallet_.word_index() == 0)
        intro_key = "import_step3_intro";
    if (stage == WalletStage::ImportPassphrase)
        intro_key = "import_step4_intro";
    if (intro && intro_key) {
        page_ = WalletPage::Intro;
        const auto body = numbered(t("wallet", intro_key), wallet_.word_count());
        view_.text(wallet_.flow() == WalletFlow::Create ? choice("New Wallet", "新建钱包")
                                                        : choice("Import Wallet", "导入钱包"),
                   body.c_str(), choice("Continue", "继续"));
        return;
    }
    page_ = WalletPage::Input;
    const char* key = "";
    const char* hint = choice("Enter to confirm; Esc to cancel", "输入后确认，或取消");
    // InputState defaults to is_pwd=False throughout these original workflows.
    bool hidden = false, word = false;
    switch (stage) {
        case WalletStage::Passphrase:
            key = "step1_input_prompt";
            hint = choice("16+ characters: Aa, 0-9, symbol", "至少16位：大小写、数字、符号");
            break;
        case WalletStage::ConfirmPassphrase:
            key = "step2_verify_prompt";
            break;
        case WalletStage::Password:
            key = wallet_.flow() == WalletFlow::Create ? "step3_input_prompt" : "import_pwd_prompt";
            hint = choice("12+ characters: Aa, 0-9, symbol", "至少12位：大小写、数字、符号");
            break;
        case WalletStage::ConfirmPassword:
            key =
                wallet_.flow() == WalletFlow::Create ? "step4_verify_prompt" : "import_pwd_verify";
            break;
        case WalletStage::ImportWord:
            key = "import_word_prompt";
            hidden = false;
            word = true;
            break;
        case WalletStage::ImportPassphrase:
            key = "import_passphrase_prompt";
            hint = choice("Use the original; leave blank if none", "输入原附加短语；没有则留空");
            break;
        case WalletStage::VerifyWord:
            key = "verify_word_prompt";
            hidden = false;
            word = true;
            break;
        case WalletStage::VerifyPassphrase:
            key = "verify_passphrase_prompt";
            break;
        case WalletStage::VerifyPassword:
            key = "verify_password_prompt";
            break;
        default:
            menu();
            return;
    }
    if (word)
        hint = choice("Enter the word from your backup", "输入备份中的助记词");
    const auto heading = numbered(t("wallet", key), wallet_.word_index() + 1, wallet_.word_count());
    // Original InputState accepts arbitrary text; spelling/checksum validation
    // happens on finalize, not while entering each recovery word.
    view_.input(heading.c_str(), hint, hidden, false);
}
void WalletApp::launch_job() {
    auto job = wallet_.take_job();
    if (!job)
        return;
    const auto token = job->token;
    if (!service_.submit(std::move(*job))) {
        wallet_.failed(token);
        page_ = WalletPage::Error;
        view_.text(choice("Operation unavailable", "操作不可用"),
                   choice("The wallet service could not start. Nothing was saved.",
                          "钱包服务无法启动。未保存任何内容。"),
                   choice("Retry", "重试"));
        return;
    }
    page_ = WalletPage::Busy;
    const bool generating = wallet_.stage() == WalletStage::Generating;
    const auto body =
        generating ? std::string(t("wallet", "time_estimate")) + "\n" + t("wallet", "please_wait")
                   : std::string{};
    view_.status(generating ? t("wallet", "creating_wallet") : t("wallet", "verifying"),
                 body.c_str(), false);
}
void WalletApp::input_error(InputError error, std::string_view value, WalletStage previous) {
    page_ = WalletPage::Error;
    retry_intro_ = false;
    if (error == InputError::Mismatch &&
        (previous == WalletStage::VerifyWord || previous == WalletStage::VerifyPassphrase ||
         previous == WalletStage::VerifyPassword)) {
        // Preserve the requested original verification screen, including the
        // entered and expected value. LVGL allocations are wiped on transition;
        // neither value is logged or included in test screenshots.
        std::string body;
        const std::string_view pattern(t("wallet", "verify_fail_detail"));
        body.reserve(pattern.size() + value.size() + wallet_.verification_target().size());
        std::size_t offset = 0;
        for (auto item : {value, wallet_.verification_target()}) {
            const auto pos = pattern.find("{}", offset);
            if (pos == std::string_view::npos)
                break;
            body.append(pattern.substr(offset, pos - offset));
            body.append(item);
            offset = pos + 2;
        }
        body.append(pattern.substr(offset));
        const auto* title =
            t("wallet", previous == WalletStage::VerifyWord         ? "verify_word_error"
                        : previous == WalletStage::VerifyPassphrase ? "verify_passphrase_error"
                                                                    : "verify_password_error");
        view_.text(title, body.c_str(), choice("Retry", "重试"));
        wipe(body.data(), body.size());
        return;
    }
    const char* text = "";
    std::string formatted;
    auto errors = std::vector<InputError>{error};
    if (previous == WalletStage::Passphrase || previous == WalletStage::Password) {
        errors =
            credential_errors(value, previous == WalletStage::Passphrase    ? passphrase_rules
                                     : wallet_.flow() == WalletFlow::Import ? import_password_rules
                                                                            : password_rules);
    }
    std::string body;
    for (const auto item : errors) {
        switch (item) {
            case InputError::TooShort:
                formatted = numbered(t("wallet", "err_len"),
                                     wallet_.stage() == WalletStage::Passphrase ? 16 : 12);
                text = formatted.c_str();
                break;
            case InputError::Uppercase:
                text = t("wallet", "err_upper");
                break;
            case InputError::Lowercase:
                text = t("wallet", "err_lower");
                break;
            case InputError::Digit:
                text = t("wallet", "err_digit");
                break;
            case InputError::Special:
                text = t("wallet", "err_special");
                break;
            case InputError::Mismatch:
                text = t("wallet", "mismatch_error");
                break;
            case InputError::SameCredentials:
                text = t("wallet", "same_as_passphrase_error");
                break;
            case InputError::InvalidWord:
                text = t("wallet", "word_empty_error");
                break;
            case InputError::TooLong:
                text =
                    choice("Input is too long. Maximum: 1024 bytes.", "输入过长，最多1024字节。");
                break;
            default:
                text = choice("Invalid input. Please try again.", "输入无效，请重试。");
                break;
        }
        if (!body.empty())
            body += '\n';
        body += text;
    }
    view_.text("", body.c_str(), choice("Retry", "重试"));
}
void WalletApp::service_result(WalletResult result) {
    if (result.error != WalletError::None) {
        if (!wallet_.failed(result.token))
            return;
        page_ = WalletPage::Error;
        retry_intro_ = true;
        const char* message =
            t("wallet", wallet_.flow() == WalletFlow::Import ? "import_fail" : "creation_error");
        view_.text("", message, choice("Retry", "重试"));
        return;
    }
    if (result.kind == WalletWork::Generate) {
        std::array<std::string_view, 24> words;
        for (std::size_t i = 0; i < words.size(); ++i)
            words[i] = result.words[i].view();
        if (wallet_.generated(result.token, words))
            draw_wallet(true);
    } else if (wallet_.saved(result.token))
        draw_wallet();
}
void WalletApp::handle(TouchEvent event) {
    if (event.screen != view_.screen_id())
        return;
    if (event.action == TouchAction::Cancel) {
        cancel();
        return;
    }
    if (page_ == WalletPage::Menu && event.action == TouchAction::Select) {
        switch (event.selection) {
            case 0:
                page_ = WalletPage::Readme;
                view_.text(t("menu", "readme_title"), t("menu", "readme_content"),
                           choice("Back", "返回"));
                break;
            case 1:
                connections();
                break;
            case 2:
                begin(WalletFlow::Create);
                break;
            case 3:
                begin(WalletFlow::Import);
                break;
            case 4:
                choose_language();
                break;
            case 5: {
                page_ = WalletPage::Info;
                const auto body =
                    std::string(config::product_name) + "\n\n" +
                    (service_.exists() ? choice("Wallet initialized", "钱包已初始化")
                                       : choice("Wallet not initialized", "钱包未初始化")) +
                    "\nTrust Wallet Core 4.4.2\nC/C++\n\n" +
                    choice("macOS simulator", "macOS模拟器") +
                    "\nES3C28P / ESP32-S3\n320 x 240\n\n" +
                    choice("Firmware: Wi-Fi and microSD disabled", "固件：Wi-Fi和microSD已禁用");
                view_.text(t("menu", "info_title"), body.c_str(), choice("Back", "返回"));
                break;
            }
            case 6:
                page_ = WalletPage::Site;
                view_.text(t("menu", "site_title"),
                           choice("Original Airlock project:\nwww.airlock.pub\n\nBJJ-Cold-Wallet "
                                  "is the ESP32 adaptation.\n\nBeware of phishing sites.",
                                  "原Airlock项目：\nwww.airlock.pub\n\nBJJ-Cold-"
                                  "Wallet是ESP32适配版本。\n\n认准网站，谨防钓鱼。"),
                           choice("Back", "返回"));
                break;
            case 7:
            case 8:
                page_ = event.selection == 7 ? WalletPage::Shutdown : WalletPage::Reboot;
                view_.text(t("menu", "power_title"),
                           t("menu", event.selection == 7 ? "shutdown_conf" : "reboot_conf"),
                           choice("Confirm", "确定"));
                break;
            default:
                break;
        }
    } else if (page_ == WalletPage::Connections && event.action == TouchAction::Select &&
               event.selection < devices_.size()) {
        selected_device_ = event.selection;
        page_ = WalletPage::RemoveDevice;
        const auto body = interpolate(t("ble", "device_row"), {devices_[selected_device_].name});
        view_.text(t("ble", "remove_title"), body.c_str(), choice("Confirm", "确定"), false, true,
                   choice("Reject", "拒绝"));
    } else if (page_ == WalletPage::Language && event.action == TouchAction::Select &&
               event.selection < language_names.size()) {
        require_language_ = false;
        set_language(language_order[event.selection]);
    } else if (page_ == WalletPage::WordCount && event.action == TouchAction::Select &&
               event.selection < config::import_word_counts.size()) {
        wallet_.select_word_count(config::import_word_counts[event.selection]);
        draw_wallet(true);
    } else if (page_ == WalletPage::Input && event.action == TouchAction::Submit) {
        const auto previous = wallet_.stage();
        const auto error = wallet_.input(event.value.view());
        if (error != InputError::None)
            input_error(error, event.value.view(), previous);
        else
            draw_wallet(wallet_.stage() != previous);
        event.value.clear();
    } else if (page_ == WalletPage::Input && event.action == TouchAction::InputTooLong) {
        input_error(InputError::TooLong);
    } else if (event.action == TouchAction::Confirm) {
        switch (page_) {
            case WalletPage::Pairing:
                if (pairing_ && device_->pairing_active(pairing_->token))
                    device_->respond_pairing(pairing_->token, true);
                pairing_.reset();
                menu();
                break;
            case WalletPage::RemoveDevice:
                view_.status(t("ble", "manage_title"), t("ble", "processing"), false);
                if (device_ && selected_device_ < devices_.size())
                    device_->remove_device(devices_[selected_device_].id);
                connections();
                break;
            case WalletPage::ReviewTransaction:
                if (!device_->connected(sign_connection_)) {
                    if (auto outcome = signing_.disconnect(sign_connection_))
                        sign_finished(std::move(*outcome));
                    break;
                }
                if (auto job = signing_.confirm(signing_.token(), lv_tick_get())) {
                    const auto token = job->token;
                    page_ = WalletPage::Signing;
                    view_.status(t("sign", "signing_overlay"), "", false);
                    if (!device_->sign(std::move(*job))) {
                        if (auto outcome = signing_.completed(token, sign_connection_, "", false))
                            sign_finished(std::move(*outcome), t("sign", "unknown_error"));
                    }
                }
                break;
            case WalletPage::Signing:
            case WalletPage::SignFeedback:
                break;
            case WalletPage::Overwrite:
                wallet_.confirm_overwrite();
                draw_wallet(true);
                break;
            case WalletPage::Intro:
            case WalletPage::BackupIntro:
                draw_wallet();
                break;
            case WalletPage::Error:
                draw_wallet(retry_intro_);
                break;
            case WalletPage::Backup:
                wallet_.begin_backup_verification();
                draw_wallet();
                break;
            case WalletPage::Shutdown:
                action_ = AppAction::Shutdown;
                break;
            case WalletPage::Reboot:
                action_ = AppAction::Reboot;
                menu();
                break;
            case WalletPage::Busy:
                break;
            default:
                menu();
                break;
        }
    }
}
void WalletApp::consume() {
    if (auto event = view_.take_event())
        handle(std::move(*event));
    if (auto result = service_.poll())
        service_result(std::move(*result));
    poll_device();
}

void WalletApp::connections() {
    page_ = WalletPage::Connections;
    view_.status(t("ble", "manage_title"), t("ble", "loading"), false);
    devices_ = device_ ? device_->trusted_devices() : std::vector<TrustedDevice>{};
    devices_revision_ = device_ ? device_->devices_revision() : 0;
    if (devices_.empty()) {
        // Same no-BLE-service/empty-list behavior as ConnectionManagerState.
        view_.text(t("ble", "manage_title"), t("ble", "no_devices"), choice("Back", "返回"));
    } else {
        std::vector<std::string> items;
        for (const auto& d : devices_)
            items.push_back(std::string(d.connected ? "[Y] " : "[N] ") + d.name);
        view_.menu(t("ble", "manage_title"), items, true);
    }
}
void WalletApp::review_transaction(TransactionPrompt prompt) {
    // Preserve state_sign.py::_format_amount, including a trailing decimal
    // separator for whole-unit amounts. Never round through floating point.
    std::string amount = prompt.amount;
    if (!is_unsigned_decimal(amount) || amount.size() > 1024 || prompt.decimals > 255 ||
        prompt.recipient.empty()) {
        SignOutcome outcome{std::move(prompt.request.id_json), prompt.request.connection,
                            SignEnd::Failed, ""};
        device_->respond_transaction(std::move(outcome),
                                     "Device UI Error: invalid transaction fields");
        wallet_.cancel();
        page_ = WalletPage::TransactionError;
        const auto body = std::string(t("sign", "data_error")) + "\n" +
                          interpolate(t("sign", "error_prefix"), {t("sign", "data_error")});
        view_.text(t("sign", "title"), body.c_str(), choice("Back", "返回"));
        return;
    }
    if (prompt.decimals) {
        if (amount.size() <= prompt.decimals)
            amount.insert(0, prompt.decimals + 1 - amount.size(), '0');
        amount.insert(amount.size() - prompt.decimals, 1, '.');
        while (amount.back() == '0')
            amount.pop_back();
    }
    const auto body = interpolate(t("sign", "asset_row"), {prompt.symbol, prompt.coin}) + "\n" +
                      interpolate(t("sign", "amount_row"), {amount}) + "\n" +
                      t("sign", "to_label") + "\n" + prompt.recipient;
    prompt.request.review = {{"Asset", prompt.symbol + " (" + prompt.coin + ")"},
                             {"Amount", amount},
                             {"To", prompt.recipient}};
    sign_connection_ = prompt.request.connection;
    if (!signing_.begin(std::move(prompt.request), lv_tick_get()))
        return;
    wallet_.cancel();
    page_ = WalletPage::ReviewTransaction;
    // The original allows confirmation without scrolling; all fields remain
    // available for inspection on the touchscreen.
    signing_.mark_reviewed(signing_.token());
    view_.text(t("sign", "title"), body.c_str(), choice("Confirm", "确定"), false, true,
               choice("Reject", "拒绝"));
}
void WalletApp::sign_finished(SignOutcome outcome, std::string_view error) {
    const auto reason = outcome.reason;
    if (reason == SignEnd::Rejected)
        error = t("sign", "rejected_msg");
    if (reason == SignEnd::TimedOut)
        error = t("sign", "timeout_msg");
    const std::string message(
        error.empty() && reason == SignEnd::Failed ? t("sign", "unknown_error") : error);
    device_->respond_transaction(std::move(outcome), message);
    if (reason == SignEnd::Completed || reason == SignEnd::Failed) {
        page_ = WalletPage::SignFeedback;
        feedback_tick_ = lv_tick_get();
        view_.status(t("sign", reason == SignEnd::Completed ? "success_title" : "fail_title"),
                     reason == SignEnd::Completed ? t("sign", "success_msg") : message.c_str(),
                     false);
    } else
        menu();
}
void WalletApp::poll_device() {
    if (!device_)
        return;
    if (page_ == WalletPage::SignFeedback) {
        if (lv_tick_elaps(feedback_tick_) >= 3000)
            menu();
        else
            return;
    }
    if (page_ == WalletPage::Busy)
        return;
    if (page_ == WalletPage::Connections && device_->devices_revision() != devices_revision_)
        connections();
    if (page_ == WalletPage::Pairing && pairing_ && !device_->pairing_active(pairing_->token)) {
        pairing_.reset();
        menu();
    }
    if (signing_.stage() != SignStage::Idle) {
        if (!device_->connected(sign_connection_)) {
            if (auto outcome = signing_.disconnect(sign_connection_))
                sign_finished(std::move(*outcome));
        } else if (auto outcome = signing_.poll(lv_tick_get()))
            sign_finished(std::move(*outcome));
        if (auto result = device_->poll_signature()) {
            if (auto outcome = signing_.completed(result->token, result->connection,
                                                  std::move(result->encoded), result->success))
                sign_finished(std::move(*outcome), result->error);
        }
        return;
    }
    if (auto request = device_->poll_pairing()) {
        wallet_.cancel();
        pairing_ = *request;
        page_ = WalletPage::Pairing;
        const auto body = numbered(t("ble", "passkey"), request->passkey);
        view_.text(t("ble", "pair_title"), body.c_str(), choice("Confirm", "确定"), false, true,
                   choice("Reject", "拒绝"));
    }
    if (page_ != WalletPage::Pairing)
        if (auto request = device_->poll_transaction())
            review_transaction(std::move(*request));
}
} // namespace airlock
