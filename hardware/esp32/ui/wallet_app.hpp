// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/wallet_service.hpp"
#include "core/device_service.hpp"
#include "ui/touch_view.hpp"
#include "ui/language.hpp"

namespace airlock {
enum class WalletPage {
    Menu,
    Readme,
    Connections,
    Language,
    Info,
    Site,
    Shutdown,
    Reboot,
    Overwrite,
    WordCount,
    Intro,
    Input,
    Busy,
    BackupIntro,
    Backup,
    Error,
    Success,
    Unavailable,
    Pairing,
    RemoveDevice,
    ReviewTransaction,
    Signing,
    SignFeedback,
    TransactionError
};
enum class AppAction { None, Shutdown, Reboot };

// Shared navigation port of ui/states_menu.py and ui/states_wallet.py.
// Platform services, windowing and board drivers remain outside the UI layer.
class WalletApp {
  public:
    WalletApp(lv_obj_t* screen, WalletService& service, DeviceService* device = nullptr);
    void consume();
    void cancel();
    AppAction take_action();
    void set_language(Language language);
    void choose_language(bool required = false);
    bool needs_language_selection() const {
        return require_language_;
    }
    Language language() const {
        return language_;
    }
    WalletPage page() const {
        return page_;
    }
    WalletStage stage() const {
        return wallet_.stage();
    }
    TouchView& view() {
        return view_;
    }

  private:
    TouchView view_;
    WalletService& service_;
    WalletStates wallet_;
    WalletPage page_ = WalletPage::Menu;
    AppAction action_ = AppAction::None;
    Language language_ = Language::English;
    bool retry_intro_ = false, require_language_ = false;
    DeviceService* device_ = nullptr;
    std::vector<TrustedDevice> devices_;
    std::uint64_t devices_revision_ = 0;
    std::size_t selected_device_ = 0;
    std::optional<PairingRequest> pairing_;
    TransactionSignState signing_;
    std::uint64_t sign_connection_ = 0;
    std::uint32_t feedback_tick_ = 0;
    const char* t(const char* group, const char* key) const;
    const char* choice(const char* english, const char* chinese) const;
    void menu();
    void begin(WalletFlow flow);
    void draw_wallet(bool intro = false);
    void handle(TouchEvent event);
    void input_error(InputError error, std::string_view value = {},
                     WalletStage previous = WalletStage::Idle);
    void service_result(WalletResult result);
    void launch_job();
    void connections();
    void poll_device();
    void review_transaction(TransactionPrompt prompt);
    void sign_finished(SignOutcome outcome, std::string_view error = {});
};
} // namespace airlock
