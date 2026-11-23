// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/setup_vault.hpp"
#include "core/device_name.hpp"
#include "ui/touch_view.hpp"
#include "core/device_service.hpp"

namespace airlock {
enum class Text;
enum class FirstLoginPage {
    Language,
    DeviceName,
    Splash,
    Choice,
    Name,
    WordCount,
    ImportWord,
    WordReview,
    PassChoice,
    PassEntry,
    PassConfirm,
    PinChoice,
    PinEntry,
    PinConfirm,
    MobilePolicy,
    TransactionIntro,
    TransactionEntry,
    TransactionConfirm,
    Busy,
    Backup,
    Unlock,
    Cooldown,
    Success,
    Ready,
    AddressPhrase,
    AddressList,
    AddressNetwork,
    AddressDetail,
    Main,
    Home,
    Balances,
    BalanceCoin,
    BalanceNetwork,
    Settings,
    PhraseWallet,
    AuthenticatePin,
    OldPhrase,
    CommonPhrase,
    SeedNotice,
    DeleteWallet,
    DeleteConfirm,
    DeleteDone,
    MobileApproval,
    Pending,
    Fault,
    MoneroWallet,
    MoneroConfig,
    Devices,
    DeviceActions,
    DeviceDelete,
    DeviceBlock
};
class FirstLogin : public InternalSecretAllocation {
  public:
    FirstLogin(TouchView& view, SetupService& service);
    void start(std::uint64_t now_ms);
    void tick(std::uint64_t now_ms);
    void consume();
    bool prompt_mobile(std::uint64_t token, std::string_view identity,
                       std::optional<std::uint32_t> code = std::nullopt);
    std::optional<std::pair<std::uint64_t, bool>> take_mobile_decision();
    void cancel_mobile();
    std::size_t selected_wallet() const {
        return wallet_index_;
    }
    std::string device_name() const {
        return full_device_name(device_name_);
    }
    Language language() const {
        return language_;
    }
    FirstLoginPage page() const {
        return page_;
    }
    const WalletProfile& profile() const {
        return profile_;
    }
    bool mobile_allowed() const {
        return session_ && session_->authorized && profile_.pin_policy_current &&
               page_ != FirstLoginPage::Fault;
    }
    void poll_signing(DeviceService& device, std::uint64_t now);
    void show_public_sync(bool active);
    const UnlockSession* signing_session() const {
        return mobile_allowed() ? session_.get() : nullptr;
    }

  private:
    std::vector<TrustedDevice> access_devices_;
    std::string selected_device_id_;
    std::uint64_t access_revision_ = 0;
    bool upgrading_pin_ = false;
    TransactionSignState signing_;
    bool sync_visible_ = false;
    bool monero_enabled_ = true;
    std::size_t monero_wallet_index_ = 0;
    DeviceService* signing_device_ = nullptr;
    std::uint64_t signing_connection_ = 0;
    FirstLoginPage signing_return_ = FirstLoginPage::Main;
    std::optional<MoneroOperation> signing_monero_;
    bool signing_phrase_ = false;
    std::string signing_payment_symbol_;
    void end_signing(SignOutcome outcome, std::string_view error = {});
    void submit_signing();
    std::uint64_t mobile_token_ = 0;
    std::string mobile_identity_;
    std::optional<std::uint32_t> mobile_code_;
    std::optional<std::pair<std::uint64_t, bool>> mobile_decision_;
    FirstLoginPage mobile_return_ = FirstLoginPage::Main;
    std::string device_name_;
    bool editing_device_name_ = false;
    bool load_device_name();
    Language language_ = Language::PortugueseBrazil;
    bool language_ready_ = false, initial_language_choice_ = true;
    const char* tr(Text text) const;
    TouchView& view_;
    SetupService& service_;
    FirstLoginPage page_ = FirstLoginPage::Splash;
    WalletProfile profile_;
    std::unique_ptr<SetupRequest> request_;
    std::unique_ptr<SetupCandidate> candidate_;
    std::unique_ptr<UnlockSession> session_;
    Credential first_;
    std::vector<ReceivingAddress> addresses_;
    std::size_t address_index_ = 0;
    std::size_t address_asset_index_ = 0;
    FirstLoginPage address_detail_parent_ = FirstLoginPage::AddressList;
    bool resume_address_detail_ = false;
    bool address_after_unlock_ = false;
    std::size_t import_index_ = 0;
    FirstLoginPage seed_target_ = FirstLoginPage::WordReview;
    std::uint64_t splash_until_ = 0, retry_until_ = 0, now_ = 0, token_ = 0;
    enum class Edit { None, Pin, Phrase, Delete };
    Edit edit_ = Edit::None;
    std::vector<WalletProfile> profiles_;
    Credential old_phrase_, startup_pin_, pending_shared_phrase_;
    WalletPhrases phrase_proofs_;
    bool apply_phrase_to_all_ = false, gathering_phrases_ = false;
    std::size_t phrase_target_ = 0;
    bool additional_ = false, auto_unlock_ = false;
    unsigned auth_errors_ = 0, phrase_errors_ = 0;
    std::size_t wallet_index_ = 0;
    std::uint64_t countdown_second_ = 0;
    FirstLoginPage unlock_target_ = FirstLoginPage::Main;
    FirstLoginPage cooldown_target_ = FirstLoginPage::Unlock;
    FirstLoginPage pending_return_ = FirstLoginPage::Settings;
    void lock(const char* error = nullptr);
    void copy_session(SetupJob& job);
    void begin_edit();
    void change_pin();
    void change_phrase();
    void finish_phrase_creation();
    void delete_wallet();
    void verify_phrase(std::string_view phrase);
    void verify_common(std::string_view phrase);
    void after_seed_credentials();
    void render(FirstLoginPage page, const char* error = nullptr);
    void after_splash();
    void generate_words();
    void prepare();
    void commit();
    void unlock(std::string_view pin);
    void request_addresses(std::string_view phrase);
    void fault(const char* message);
    void back();
    bool submit(std::unique_ptr<SetupJob> job);
};
} // namespace airlock
