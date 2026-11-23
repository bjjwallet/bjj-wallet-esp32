#include "hardware/es3c28p.hpp"
// SPDX-License-Identifier: GPL-3.0-or-later
// Public fixtures only. No user's device/store is accessed by this executable.
#include "ui/first_login.hpp"
#include "ui/board_serial_control.hpp"
#include "ui/address_catalog.hpp"
#include <array>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <utility>

using namespace airlock;
namespace {
unsigned checks = 0;
#define CHECK(value)                                                                               \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(value)) {                                                                            \
            std::cerr << "First login check failed: line " << __LINE__ << '\n';                    \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
class FixtureRandom final : public crypto::RandomSource {
  public:
    unsigned counter = 0;
    bool fill(crypto::Output output) override {
        for (std::size_t i = 0; i < output.size; ++i)
            output.data[i] = static_cast<std::uint8_t>(++counter);
        return true;
    }
};
class Store final : public SetupStore {
  public:
    std::vector<std::uint8_t> data;
    bool fail = false;
    bool cache_enabled = false;
    std::map<std::string, std::vector<std::uint8_t>> cached;
    bool public_cache_enabled() const override {
        return cache_enabled;
    }
    StoreRead read_public(std::string_view key, std::vector<std::uint8_t>& output) override {
        const auto found = cached.find(std::string(key));
        if (found == cached.end())
            return StoreRead::Missing;
        output = found->second;
        return StoreRead::Found;
    }
    bool write_public(std::string_view key, crypto::Bytes encoded) override {
        cached[std::string(key)].assign(encoded.data, encoded.data + encoded.size);
        return true;
    }
    std::map<std::string, bool> monero;
    StoreRead read_monero_enabled(std::string_view id, bool& enabled) override {
        const auto it = monero.find(std::string(id));
        enabled = it == monero.end() || it->second;
        return it == monero.end() ? StoreRead::Missing : StoreRead::Found;
    }
    bool write_monero_enabled(std::string_view id, bool enabled) override {
        monero[std::string(id)] = enabled;
        return true;
    }
    StoreRead read(std::vector<std::uint8_t>& output) override {
        output = data;
        return data.empty() ? StoreRead::Missing : StoreRead::Found;
    }
    bool replace(crypto::Bytes expected, crypto::Bytes input) override {
        if (fail || expected.size != data.size() ||
            !std::equal(data.begin(), data.end(), expected.data))
            return false;
        data.assign(input.data, input.data + input.size);
        return true;
    }
    bool commit_if_empty(crypto::Bytes input) override {
        if (fail || !data.empty())
            return false;
        data.assign(input.data, input.data + input.size);
        return true;
    }
};
class Service final : public SetupService {
  public:
    Store store;
    FixtureRandom random;
    SetupVault vault{store, random};
    std::string language_code = "pt-BR", device_name = "Fixture";
    bool name_write_fail = false, name_read_fail = false;
    StoreRead load_device_name(std::string& name) override {
        name = device_name;
        return name_read_fail ? StoreRead::Error
               : name.empty() ? StoreRead::Missing
                              : StoreRead::Found;
    }
    bool save_device_name(std::string_view name) override {
        if (name_write_fail)
            return false;
        device_name = name;
        return true;
    }
    bool load_monero_enabled(std::size_t index, bool& enabled) override {
        return vault.monero_enabled(index, enabled) == VaultError::None;
    }
    bool save_monero_enabled(const UnlockSession& session, std::size_t index,
                             bool enabled) override {
        return vault.set_monero_enabled(session, index, enabled) == VaultError::None;
    }
    bool language_write_fail = false, language_read_fail = false;
    StoreRead load_language(std::string& code) override {
        code = language_code;
        return language_read_fail ? StoreRead::Error
               : code.empty()     ? StoreRead::Missing
                                  : StoreRead::Found;
    }
    bool save_language(std::string_view code) override {
        if (language_write_fail)
            return false;
        language_code = code;
        return true;
    }
    std::unique_ptr<SetupJob> job;
    bool legacy_cardano_cache = false;
    VaultError inspect(WalletProfile& profile) override {
        return vault.inspect(profile);
    }
    bool submit(std::unique_ptr<SetupJob> next) override {
        if (job)
            return false;
        job = std::move(next);
        return true;
    }
    std::unique_ptr<SetupResult> poll() override {
        if (!job)
            return nullptr;
        auto result = std::make_unique<SetupResult>();
        run_setup_job(vault, *job, *result);
        if (legacy_cardano_cache && result->error == VaultError::None) {
            if (job->work == SetupWork::PublicAddresses)
                result->addresses.erase(
                    std::remove_if(result->addresses.begin(), result->addresses.end(),
                                   [](const auto& item) { return item.network == "Cardano"; }),
                    result->addresses.end());
            else if (job->work == SetupWork::ReceivingAddresses)
                legacy_cardano_cache = false;
        }
        job.reset();
        return result;
    }
};
class SigningDevice final : public DeviceService {
  public:
    std::optional<TransactionPrompt> prompt;
    std::optional<SigningJob> job;
    std::optional<SigningResult> result;
    std::optional<SignOutcome> outcome;
    bool link_up = true;
    std::vector<TrustedDevice> peers;
    std::uint64_t access_revision = 0;
    std::vector<TrustedDevice> trusted_devices() override {
        return peers;
    }
    std::uint64_t devices_revision() const override {
        return access_revision;
    }
    bool remove_device(std::string_view id) override {
        peers.erase(
            std::remove_if(peers.begin(), peers.end(), [&](const auto& d) { return d.id == id; }),
            peers.end());
        ++access_revision;
        return true;
    }
    bool block_device(std::string_view id) override {
        for (auto& d : peers)
            if (d.id == id) {
                d.blocked = true;
                d.connected = false;
                link_up = false;
                ++access_revision;
                return true;
            }
        return false;
    }
    std::optional<PairingRequest> poll_pairing() override {
        return {};
    }
    bool pairing_active(std::uint64_t) const override {
        return false;
    }
    void respond_pairing(std::uint64_t, bool) override {}
    bool connected(std::uint64_t) const override {
        return link_up;
    }
    std::optional<TransactionPrompt> poll_transaction() override {
        return std::exchange(prompt, std::nullopt);
    }
    bool sign(SigningJob next) override {
        job = std::move(next);
        return true;
    }
    std::optional<SigningResult> poll_signature() override {
        return std::exchange(result, std::nullopt);
    }
    void respond_transaction(SignOutcome next, std::string_view) override {
        outcome = std::move(next);
    }
    void request_transfer() {
        auto& next = prompt.emplace();
        next.request.connection = 7;
        CHECK(next.request.payload.assign("public fixture", 1024));
        next.symbol = "BTC";
        next.coin = "Bitcoin";
        next.amount = "12345678";
        next.decimals = 8;
        next.recipient = "public-test-recipient";
        next.fee = "1200";
        next.change = "234500";
        next.change_address = "public-test-return";
    }
};
lv_obj_t* find(lv_obj_t* object, const lv_obj_class_t* type) {
    if (lv_obj_check_type(object, type))
        return object;
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(object); ++i)
        if (auto* found = find(lv_obj_get_child(object, i), type))
            return found;
    return nullptr;
}
bool contains_text(lv_obj_t* object, std::string_view expected) {
    if (lv_obj_check_type(object, &lv_checkbox_class) &&
        std::string_view(lv_checkbox_get_text(object)).find(expected) != std::string_view::npos)
        return true;
    if (lv_obj_check_type(object, &lv_label_class) &&
        std::string_view(lv_label_get_text(object)).find(expected) != std::string_view::npos)
        return true;
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(object); ++i)
        if (contains_text(lv_obj_get_child(object, i), expected))
            return true;
    return false;
}
void snapshot(TouchView& view, const char* path) {
    lv_obj_update_layout(view.root());
    auto* buffer = lv_snapshot_take(view.root(), LV_COLOR_FORMAT_RGB888);
    CHECK(buffer);
    std::ofstream file(path, std::ios::binary);
    file << "P6\n" << buffer->header.w << ' ' << buffer->header.h << "\n255\n";
    for (unsigned y = 0; y < buffer->header.h; ++y)
        for (unsigned x = 0; x < buffer->header.w; ++x) {
            const auto* bgr = buffer->data + y * buffer->header.stride + x * 3;
            const char rgb[] = {static_cast<char>(bgr[2]), static_cast<char>(bgr[1]),
                                static_cast<char>(bgr[0])};
            file.write(rgb, 3);
        }
    CHECK(file.good());
    lv_draw_buf_destroy(buffer);
}
struct Driver {
    TouchView& view;
    FirstLogin& app;
    BoardSerialControl controls;
    std::uint64_t now = 0;
    bool acknowledge_seed_notice = true;
    void settle() {
        for (unsigned i = 0; i < 6; ++i) {
            app.tick(++now);
            if (acknowledge_seed_notice && app.page() == FirstLoginPage::SeedNotice) {
                CHECK(contains_text(view.root(), "nunca mais"));
                CHECK(!contains_text(view.root(), "1. "));
                CHECK(controls.execute("ok", view) == "OK");
                app.consume();
            }
        }
        controls.execute("tela", view);
    }
    void action(std::string_view command) {
        const auto response = controls.execute(command, view);
        if (response.substr(0, 4) == "ERRO")
            std::cerr << "Command failed: " << command << " page " << static_cast<int>(app.page())
                      << " " << response << '\n';
        CHECK(response.substr(0, 4) != "ERRO");
        app.consume();
        settle();
    }
    void text(std::string_view value) {
        action(std::string("texto ") + std::string(value));
        action("key submit");
    }
};
void flow(unsigned words, bool imported, bool pin, bool pass, bool mobile_pin, bool captures) {
    Service service;
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver driver{view, app, {}, 0};
    app.start(0);
    CHECK(app.page() == FirstLoginPage::Splash);
    if (captures)
        snapshot(view, "onboarding-splash.ppm");
    app.tick(1999);
    CHECK(app.page() == FirstLoginPage::Splash);
    driver.now = 2000;
    app.tick(driver.now);
    driver.settle();
    CHECK(app.page() == FirstLoginPage::Choice);
    if (captures)
        snapshot(view, "onboarding-choice.ppm");
    driver.action(imported ? "2" : "1");
    CHECK(app.page() == FirstLoginPage::Name);
    driver.text("   ");
    CHECK(app.page() == FirstLoginPage::Name);
    driver.text("Wallet publica de teste");
    CHECK(app.page() == FirstLoginPage::WordCount);
    const auto menu = driver.controls.execute("tela", view);
    CHECK(menu.find("15 palavras") == std::string::npos &&
          menu.find("21 palavras") == std::string::npos);
    driver.action(words == 12 ? "1" : words == 18 ? "2" : "3");
    if (imported) {
        CHECK(app.page() == FirstLoginPage::ImportWord);
        driver.text("notaword");
        CHECK(app.page() == FirstLoginPage::ImportWord);
        std::array<std::uint8_t, 32> entropy{};
        Credential mnemonic;
        CHECK(crypto::mnemonic_from_entropy({entropy.data(), words / 3 * 4}, mnemonic) ==
              crypto::HdError::None);
        auto remaining = mnemonic.view();
        while (!remaining.empty()) {
            const auto end = remaining.find(' ');
            const auto word = remaining.substr(0, end);
            driver.text(word);
            if (end == remaining.npos)
                break;
            remaining.remove_prefix(end + 1);
        }
    }
    CHECK(app.page() == FirstLoginPage::WordReview);
    CHECK(service.store.data.empty());
    CHECK(contains_text(view.root(), "1. "));
    CHECK(driver.controls.execute("tela", view).find("1. ") == std::string::npos);
    CHECK(driver.controls.execute("key confirm", view).substr(0, 4) == "ERRO");
    // Reject the review, then return to review the same seed before credentials.
    driver.action("key cancel");
    CHECK(app.page() == (imported ? FirstLoginPage::ImportWord : FirstLoginPage::WordCount));
    if (imported) {
        std::array<std::uint8_t, 32> entropy{};
        Credential mnemonic;
        CHECK(crypto::mnemonic_from_entropy({entropy.data(), words / 3 * 4}, mnemonic) ==
              crypto::HdError::None);
        driver.text(mnemonic.view().substr(mnemonic.view().rfind(' ') + 1));
    } else {
        driver.action("key back");
        driver.text("Wallet publica de teste");
        driver.action(words == 12 ? "1" : words == 18 ? "2" : "3");
    }
    CHECK(app.page() == FirstLoginPage::WordReview);
    Credential reviewed;
    if (!imported) {
        // The fixture RNG generated twice after returning to word count.
        std::array<std::uint8_t, 32> entropy{};
        for (std::size_t i = 0; i < words / 3 * 4; ++i)
            entropy[i] = words / 3 * 4 + i + 1;
        CHECK(crypto::mnemonic_from_entropy({entropy.data(), words / 3 * 4}, reviewed) ==
              crypto::HdError::None);
    }
    driver.action("fim");
    driver.action("ok");
    CHECK(app.page() == FirstLoginPage::PassChoice);
    driver.action("key back");
    CHECK(app.page() == FirstLoginPage::WordReview);
    driver.action("fim");
    driver.action("ok");
    if (pass) {
        driver.action("ok");
        CHECK(app.page() == FirstLoginPage::PassEntry);
        CHECK(lv_textarea_get_password_mode(find(view.root(), &lv_textarea_class)));
        if (words == 12 && !pin) {
            std::string oversized;
            for (unsigned i = 0; i < 600; ++i)
                oversized += u8"é";
            lv_textarea_set_text(find(view.root(), &lv_textarea_class), oversized.c_str());
            driver.action("key submit");
            CHECK(app.page() == FirstLoginPage::PassEntry);
            CHECK(contains_text(view.root(), "Texto longo demais"));
        }
        driver.text("Public passphrase");
        CHECK(app.page() == FirstLoginPage::PassConfirm);
        driver.text("Mismatch");
        CHECK(app.page() == FirstLoginPage::PassEntry);
        driver.text("Public passphrase");
        driver.text("Public passphrase");
    } else
        driver.action("key cancel");
    CHECK(app.page() == FirstLoginPage::PinChoice);
    if (captures) {
        snapshot(view, "onboarding-pin-explanation.ppm");
        driver.action("fim");
        snapshot(view, "onboarding-pin-explanation-bottom.ppm");
    }
    driver.action("ok");
    CHECK(app.page() == FirstLoginPage::PinEntry);
    driver.text("34");
    CHECK(app.page() == FirstLoginPage::PinEntry);
    driver.text("Aa345678");
    driver.text("Aa345678");
    CHECK(app.page() == FirstLoginPage::TransactionIntro);
    driver.action("ok");
    driver.text("short");
    CHECK(app.page() == FirstLoginPage::TransactionEntry);
    driver.text("Public transaction phrase");
    driver.text("Mismatch");
    CHECK(app.page() == FirstLoginPage::TransactionEntry);
    driver.text("Public transaction phrase");
    driver.text("Public transaction phrase");
    {
        CHECK(app.page() == FirstLoginPage::Backup && service.store.data.empty());
        if (!imported)
            CHECK(contains_text(view.root(), reviewed.view().substr(0, reviewed.view().find(' '))));
        CHECK(contains_text(view.root(), "1. "));
        CHECK(contains_text(view.root(), pass ? "Public passphrase" : "Não configurada"));
        CHECK(driver.controls.execute("tela", view).find("Public passphrase") == std::string::npos);
        CHECK(driver.controls.execute("tela", view).find("1. ") == std::string::npos);
        CHECK(driver.controls.execute("key confirm", view).substr(0, 4) == "ERRO");
        CHECK(contains_text(view.root(), "Cancelar"));
        driver.action("fim");
        driver.action("ok");
    }
    CHECK(app.page() == FirstLoginPage::Success);
    CHECK(service.store.data.size() == wallet_record_bytes);
    if (!imported) {
        UnlockSession session;
        crypto::Entropy saved, expected;
        Credential saved_pass;
        CHECK(service.vault.unlock(pin ? "Aa345678" : "", session) == VaultError::None);
        CHECK(service.vault.recover(session, "Public transaction phrase", saved, saved_pass) ==
              VaultError::None);
        CHECK(crypto::mnemonic_entropy(reviewed.view(), expected) == crypto::HdError::None);
        CHECK(saved.size == expected.size && saved.data.value == expected.data.value);
    }
    driver.action("ok");
    CHECK(app.page() == FirstLoginPage::Home);
    CHECK(app.mobile_allowed());
    CHECK(app.profile().pin_enabled == pin && app.profile().passphrase_enabled == pass &&
          app.profile().word_count == words);
    CHECK(app.profile().mobile_access ==
          (mobile_pin ? MobileAccess::Pin : MobileAccess::DeviceApproval));
    CHECK(contains_text(view.root(), "Wallet publica de teste"));
    if (captures)
        snapshot(view, "onboarding-ready.ppm");
    FirstLogin rebooted(view, service);
    Driver reboot{view, rebooted, {}, 0};
    rebooted.start(0);
    reboot.now = 2000;
    rebooted.tick(2000);
    reboot.settle();
    if (pin) {
        CHECK(rebooted.page() == FirstLoginPage::Unlock);
        reboot.text("999999");
        CHECK(rebooted.page() == FirstLoginPage::Cooldown);
        rebooted.tick(reboot.now + 1000);
        CHECK(rebooted.page() == FirstLoginPage::Cooldown);
        reboot.now += 30000;
        rebooted.tick(reboot.now);
        CHECK(rebooted.page() == FirstLoginPage::Unlock);
        reboot.text("Aa345678");
    }
    CHECK(rebooted.page() == FirstLoginPage::Main);
    CHECK(rebooted.mobile_allowed());
    // Corrupt storage must never be treated as an empty/new wallet.
    service.store.data[150] ^= 1;
    FirstLogin corrupt(view, service);
    corrupt.start(0);
    corrupt.tick(2000);
    corrupt.tick(2001);
    CHECK(corrupt.page() == FirstLoginPage::Fault);
    CHECK(driver.controls.execute("tela", view).find("Criar carteira") == std::string::npos);
}
void saved_wallet_flow() {
    Service service;
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Wallet A";
    request.pin.assign("Aa345678");
    request.transaction_phrase.assign("Public Phrase A");
    SetupCandidate candidate;
    CHECK(service.vault.prepare(request, candidate) == VaultError::None);
    CHECK(service.vault.commit(candidate.record) == VaultError::None);
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 0};
    app.start(0);
    d.now = 2000;
    d.settle();
    CHECK(app.page() == FirstLoginPage::Unlock && !app.mobile_allowed());
    d.text("Aa345678");
    CHECK(app.page() == FirstLoginPage::Main && app.mobile_allowed());
    snapshot(view, "device-main.ppm");
    CHECK(!contains_text(view.root(), "Meus saldos"));
    // Change the shared PIN with one failed old-PIN attempt.
    d.action("2");
    CHECK(contains_text(view.root(), "Deletar carteira"));
    snapshot(view, "device-settings.ppm");
    d.action("fim");
    d.action("4");
    CHECK(app.page() == FirstLoginPage::AuthenticatePin);
    d.text("999999");
    CHECK(app.page() == FirstLoginPage::Cooldown && !app.mobile_allowed());
    snapshot(view, "device-cooldown.ppm");
    CHECK(contains_text(view.root(), "30 s"));
    d.now += 30000;
    d.settle();
    CHECK(app.page() == FirstLoginPage::AuthenticatePin);
    d.text("Aa345678");
    CHECK(app.page() == FirstLoginPage::PinChoice);
    d.action("ok");
    d.text("Bb654321");
    d.text("Bb654321");
    CHECK(app.page() == FirstLoginPage::Home);
    CHECK(app.page() == FirstLoginPage::Home && app.mobile_allowed());
    // Both creation paths add a wallet and reuse the device PIN.
    for (bool imported : {false, true}) {
        d.action("key back");
        d.action("2");
        d.action(imported ? "2" : "1");
        d.text(imported ? "Wallet C" : "Wallet B");
        d.action("1");
        if (imported) {
            for (unsigned i = 0; i < 11; ++i)
                d.text("abandon");
            d.text("about");
        }
        CHECK(app.page() == FirstLoginPage::WordReview);
        d.action("fim");
        d.action("ok");
        CHECK(app.page() == FirstLoginPage::PassChoice);
        d.action("key cancel");
        CHECK(app.page() == FirstLoginPage::TransactionIntro);
        d.action("ok");
        d.text(imported ? "Public Phrase C" : "Public Phrase B");
        d.text(imported ? "Public Phrase C" : "Public Phrase B");
        CHECK(app.page() == FirstLoginPage::Backup);
        d.action("fim");
        d.action("ok");
        CHECK(app.page() == FirstLoginPage::Success);
        d.action("ok");
        CHECK(app.page() == FirstLoginPage::Home);
    }
    snapshot(view, "device-wallets.ppm");
    CHECK(contains_text(view.root(), "Wallet A") && contains_text(view.root(), "Wallet B") &&
          contains_text(view.root(), "Wallet C"));
    d.action("2");
    CHECK(app.page() == FirstLoginPage::AddressPhrase);
    d.text("Public Phrase B");
    CHECK(app.page() == FirstLoginPage::AddressList);
    d.action("1");
    CHECK(app.page() == FirstLoginPage::AddressDetail);
    snapshot(view, "device-public-qr.ppm");
    CHECK(contains_text(view.root(), "Bitcoin"));
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressList);
    snapshot(view, "device-address-networks.ppm");
    d.action("2");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    CHECK(contains_text(view.root(), "Ethereum") && contains_text(view.root(), "ETH") &&
          contains_text(view.root(), "USDT"));
    auto* network_icon = lv_obj_get_child(view.root(), 1);
    CHECK(lv_obj_has_class(network_icon, &lv_image_class));
    auto* rows = lv_obj_get_child(view.root(), 2);
    auto* native_icon = lv_obj_get_child(lv_obj_get_child(rows, 0), 1);
    auto* token_icon = lv_obj_get_child(lv_obj_get_child(rows, 1), 1);
    CHECK(lv_obj_has_class(native_icon, &lv_image_class) &&
          lv_obj_has_class(token_icon, &lv_image_class));
    CHECK(lv_image_get_src(network_icon) == lv_image_get_src(native_icon));
    CHECK(lv_image_get_src(token_icon) != lv_image_get_src(native_icon));
    snapshot(view, "device-address-currencies.ppm");
    d.action("1");
    CHECK(app.page() == FirstLoginPage::AddressDetail && contains_text(view.root(), "Ethereum"));
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    const auto select_token = [&](std::string_view network, std::string_view symbol) {
        for (const auto& row : address_catalog)
            if (row.firmware_name == network) {
                for (std::size_t i = 0; i < row.tokens.size(); ++i)
                    if (row.tokens[i].symbol == symbol) {
                        d.action(std::to_string(i + 2));
                        return;
                    }
            }
        CHECK(false);
    };
    select_token("Ethereum", "USDT");
    CHECK(app.page() == FirstLoginPage::AddressDetail && contains_text(view.root(), "USDT"));
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressList);
    d.action("3");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    CHECK(contains_text(view.root(), "USDC") && !contains_text(view.root(), "Em breve"));
    select_token("TRON", "USDC");
    CHECK(app.page() == FirstLoginPage::AddressDetail && contains_text(view.root(), "USDC"));
    CHECK(find(view.root(), &lv_qrcode_class));
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressList);
    d.action("8");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    for (const auto* symbol : {"ADA", "SNEK", "MIN", "IAG", "WMTX", "DJED"})
        CHECK(contains_text(view.root(), symbol));
    snapshot(view, "device-cardano-currencies.ppm");
    d.action("2");
    CHECK(app.page() == FirstLoginPage::AddressDetail && contains_text(view.root(), "SNEK"));
    CHECK(lv_obj_has_class(lv_obj_get_child(view.root(), 2), &lv_qrcode_class));
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressList);
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::Home);
    CHECK(app.prompt_mobile(42, "0:11:22:33:44:55", 123456));
    CHECK(app.page() == FirstLoginPage::MobileApproval && contains_text(view.root(), "123456"));
    CHECK(contains_text(view.root(), "Wallet A") && contains_text(view.root(), "Wallet B") &&
          contains_text(view.root(), "Wallet C"));
    CHECK(d.controls.execute("tela", view).find("click 1: Aceitar\nclick 2: Recusar") !=
          std::string::npos);
    snapshot(view, "device-mobile-approval.ppm");
    d.action("ok");
    const auto approval = app.take_mobile_decision();
    CHECK(approval && approval->first == 42 && approval->second);
    CHECK(app.page() == FirstLoginPage::Home);
    CHECK(app.prompt_mobile(43, "0:11:22:33:44:55"));
    d.action("key back");
    const auto rejected = app.take_mobile_decision();
    CHECK(rejected && rejected->first == 43 && !rejected->second);
    app.show_public_sync(true);
    CHECK(d.controls.execute("tela", view).rfind("Sincronizando...", 0) == 0);
    CHECK(d.controls.execute("click 1", view).rfind("ERRO", 0) == 0);
    snapshot(view, "device-sync-progress.ppm");
    app.show_public_sync(false);
    CHECK(d.controls.execute("tela", view).rfind("Minhas carteiras", 0) == 0);
    SigningDevice device;
    device.request_transfer();
    app.poll_signing(device, d.now);
    CHECK(contains_text(view.root(), "App: assinar transferência?"));
    CHECK(contains_text(view.root(), "Valor que receberá (Bitcoin)\n0.12345678 BTC"));
    CHECK(contains_text(view.root(), "Destinatário\npublic-test-recipient"));
    CHECK(contains_text(view.root(), "Valor devolvido à sua carteira\n0.002345 BTC"));
    CHECK(contains_text(view.root(), "Endereço de devolução\npublic-test-return"));
    snapshot(view, "device-transfer-approval.ppm");
    d.action("fim");
    snapshot(view, "device-transfer-return-labels.ppm");
    d.action("click 2");
    CHECK(device.outcome && device.outcome->reason == SignEnd::Rejected && !device.job);
    device.request_transfer();
    app.poll_signing(device, d.now);
    d.action("click 1");
    CHECK(device.job && d.controls.execute("tela", view).rfind("Assinando...", 0) == 0);
    device.result = SigningResult{device.job->token, 7, "fixture-signature", {}, true};
    app.poll_signing(device, d.now);
    CHECK(device.outcome && device.outcome->reason == SignEnd::Completed);
    CHECK(d.controls.execute("tela", view).rfind("Minhas carteiras", 0) == 0);
    d.action("key back");
    d.action("2");
    d.action("8");
    CHECK(app.page() == FirstLoginPage::MoneroWallet);
    CHECK(lv_obj_check_type(lv_obj_get_child(view.root(), 1), &lv_image_class));
    d.action("2");
    CHECK(app.page() == FirstLoginPage::MoneroConfig && contains_text(view.root(), "Wallet B"));
    d.action("ok");
    CHECK(contains_text(view.root(), "Monero desabilitado"));
    bool enabled = false;
    CHECK(service.load_monero_enabled(0, enabled) && enabled);
    CHECK(service.load_monero_enabled(1, enabled) && !enabled);
    d.action("key back");
    d.action("key back");
    d.action("key back");
    d.action("1");

    device.job.reset();
    auto& watch = device.prompt.emplace();
    watch.coin = "Wallet A";
    watch.request.connection = 7;
    watch.request.monero = MoneroOperation::WatchWallet;
    app.poll_signing(device, d.now);
    CHECK(contains_text(view.root(), "Wallet A") && contains_text(view.root(), "STANDARD"));
    snapshot(view, "device-monero-approval.ppm");
    d.action("ok");
    CHECK(!device.job);
    d.text("Public Phrase A");
    CHECK(device.job && device.job->request.monero == MoneroOperation::WatchWallet &&
          device.job->request.password.equals("Public Phrase A"));
    device.result = SigningResult{device.job->token, 7, "fixture-view-response", {}, true};
    app.poll_signing(device, d.now);
    CHECK(device.outcome && device.outcome->reason == SignEnd::Completed &&
          app.page() == FirstLoginPage::Home);
    // Change only B's transaction phrase, requiring current PIN and old phrase.
    d.action("key back");
    d.action("2");
    d.action("5");
    d.action("2");
    CHECK(app.page() == FirstLoginPage::AuthenticatePin);
    d.text("Bb654321");
    CHECK(app.page() == FirstLoginPage::OldPhrase);
    d.text("Wrong Phrase B");
    CHECK(app.page() == FirstLoginPage::OldPhrase &&
          contains_text(view.root(), "Resta uma tentativa"));
    d.text("Public Phrase B");
    CHECK(app.page() == FirstLoginPage::TransactionIntro);
    CHECK(contains_text(view.root(), "Usar em todas as carteiras"));
    snapshot(view, "device-phrase-scope.ppm");
    d.action("ok");
    d.text("New Public Phrase B");
    d.text("New Public Phrase B");
    CHECK(app.page() == FirstLoginPage::Home);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(service.vault.device_unlock("Bb654321", d.now, session, retry) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "Public Phrase A", 0) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "Public Phrase B", 1) == VaultError::WrongPhrase);
    CHECK(service.vault.verify_phrase(session, "New Public Phrase B", 1) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "Public Phrase C", 2) == VaultError::None);
    // Two incorrect phrases close the device session and return to initial PIN.
    d.action("key back");
    d.action("2");
    d.action("5");
    d.action("1");
    d.text("Bb654321");
    d.text("Wrong phrase A");
    d.text("Wrong phrase A");
    CHECK(app.page() == FirstLoginPage::Unlock && !app.mobile_allowed());
    CHECK(contains_text(view.root(), "Frase incorreta duas vezes"));
    d.text("Bb654321");
    CHECK(app.page() == FirstLoginPage::Main);
    // Two incorrect old PINs also return to initial PIN after the saved wait.
    d.action("2");
    d.action("4");
    d.text("999999");
    CHECK(app.page() == FirstLoginPage::Cooldown);
    d.now += 30000;
    d.settle();
    CHECK(app.page() == FirstLoginPage::AuthenticatePin);
    d.text("999999");
    CHECK(app.page() == FirstLoginPage::Cooldown);
    d.now += 30000;
    d.settle();
    CHECK(app.page() == FirstLoginPage::Unlock && !app.mobile_allowed());
    d.text("Bb654321");
    CHECK(app.page() == FirstLoginPage::Main);
    auto begin_all = [&] {
        d.action("2");
        d.action("5");
        d.action("2");
        d.text("Bb654321");
        d.text("New Public Phrase B");
        CHECK(app.page() == FirstLoginPage::TransactionIntro);
        d.action("key confirm");
        CHECK(lv_obj_has_state(find(view.root(), &lv_checkbox_class), LV_STATE_CHECKED));
        d.action("ok");
        d.text("Shared Public Phrase");
        d.text("Shared Public Phrase");
        CHECK(app.page() == FirstLoginPage::OldPhrase && contains_text(view.root(), "Wallet A"));
    };
    // Cancelling while authenticating another wallet must save nothing.
    begin_all();
    snapshot(view, "device-other-wallet-phrase.ppm");
    d.action("key cancel");
    CHECK(app.page() == FirstLoginPage::Settings);
    d.action("key back");
    CHECK(service.vault.verify_phrase(session, "Public Phrase A", 0) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "New Public Phrase B", 1) == VaultError::None);
    // Correct A, then fail C twice: B and A must still retain their old phrases.
    begin_all();
    d.text("Public Phrase A");
    CHECK(app.page() == FirstLoginPage::OldPhrase && contains_text(view.root(), "Wallet C"));
    d.text("Wrong current phrase C");
    d.text("Wrong current phrase C");
    CHECK(app.page() == FirstLoginPage::Unlock && !app.mobile_allowed());
    d.text("Bb654321");
    CHECK(service.vault.verify_phrase(session, "Public Phrase A", 0) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "New Public Phrase B", 1) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "Public Phrase C", 2) == VaultError::None);
    // Selected wallet is in the middle: each other wallet is authenticated once.
    begin_all();
    d.text("Public Phrase A");
    d.text("Public Phrase C");
    CHECK(app.page() == FirstLoginPage::Home);
    d.action("key back");
    CHECK(service.vault.verify_phrase(session, "Shared Public Phrase", 0) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "Shared Public Phrase", 1) == VaultError::None);
    CHECK(service.vault.verify_phrase(session, "Shared Public Phrase", 2) == VaultError::None);
    // Cancelling a PIN edit preserves the mandatory device PIN.
    d.action("2");
    d.action("4");
    d.text("Bb654321");
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::Settings && app.profile().pin_enabled);
    FirstLogin rebooted(view, service);
    Driver reboot{view, rebooted, {}, d.now};
    rebooted.start(d.now);
    reboot.now += 2000;
    reboot.settle();
    CHECK(rebooted.page() == FirstLoginPage::Unlock);
    reboot.text("Bb654321");
    CHECK(rebooted.page() == FirstLoginPage::Main && rebooted.mobile_allowed());
}
void delete_wallet_flow(bool pin) {
    Service service;
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Keep Wallet";
    request.transaction_phrase.assign("Keep public phrase");
    if (pin)
        request.pin.assign("Aa345678");
    SetupCandidate candidate;
    CHECK(service.vault.prepare(request, candidate) == VaultError::None);
    CHECK(service.vault.commit(candidate.record) == VaultError::None);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(service.vault.device_unlock(pin ? "Aa345678" : "", 0, session, retry) ==
          VaultError::None);
    SetupRequest second;
    second.name = "Remove Wallet";
    second.transaction_phrase.assign("Remove public phrase");
    CHECK(service.vault.prepare_additional(second, session, candidate) == VaultError::None);
    CHECK(service.vault.append(candidate.record, session) == VaultError::None);
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 2000};
    app.start(0);
    d.settle();
    if (pin)
        d.text("Aa345678");
    CHECK(app.page() == FirstLoginPage::Main);
    d.action("2");
    d.action("3");
    CHECK(app.page() == FirstLoginPage::DeleteWallet);
    CHECK(contains_text(view.root(), "Keep Wallet") && contains_text(view.root(), "Remove Wallet"));
    const auto saved = service.store.data;
    d.action("key cancel");
    CHECK(app.page() == FirstLoginPage::Settings && service.store.data == saved);
    auto select = [&](unsigned index) {
        d.action("3");
        d.action(std::to_string(index));
        if (pin) {
            CHECK(app.page() == FirstLoginPage::AuthenticatePin);
            d.text("Aa345678");
        }
        CHECK(app.page() == FirstLoginPage::OldPhrase);
    };
    if (pin) {
        d.action("3");
        d.action("2");
        d.text("999999");
        CHECK(app.page() == FirstLoginPage::Cooldown);
        d.now += 30000;
        d.settle();
        CHECK(app.page() == FirstLoginPage::AuthenticatePin);
        d.text("999999");
        d.now += 30000;
        d.settle();
        CHECK(app.page() == FirstLoginPage::Unlock && !app.mobile_allowed());
        d.text("Aa345678");
        d.action("2");
        select(2);
        d.text("Wrong valid phrase");
        d.text("Wrong valid phrase");
        CHECK(app.page() == FirstLoginPage::Unlock && !app.mobile_allowed());
        d.text("Aa345678");
        d.action("2");
        CHECK(service.store.data == saved);
    }
    select(2);
    d.text("Remove public phrase");
    CHECK(app.page() == FirstLoginPage::DeleteConfirm &&
          contains_text(view.root(), "Remove Wallet"));
    CHECK(contains_text(view.root(), "outras carteiras"));
    if (pin)
        snapshot(view, "device-delete-confirm.ppm");
    CHECK(service.store.data == saved);
    d.action("key cancel");
    CHECK(app.page() == FirstLoginPage::Settings && service.store.data == saved);
    select(2);
    d.text("Remove public phrase");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::DeleteDone);
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Home && contains_text(view.root(), "Keep Wallet") &&
          !contains_text(view.root(), "Remove Wallet"));
    CHECK(service.vault.verify_phrase(session, "Keep public phrase", 0) == VaultError::None);
    if (pin)
        snapshot(view, "device-delete-home.ppm");
    // Deleting the final wallet keeps device credentials and supports an empty Home.
    d.action("key back");
    d.action("2");
    select(1);
    d.text("Keep public phrase");
    d.action("ok");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Home && contains_text(view.root(), "Nenhuma carteira"));
    if (pin)
        snapshot(view, "device-delete-empty.ppm");
    FirstLogin rebooted(view, service);
    Driver reboot{view, rebooted, {}, d.now};
    rebooted.start(d.now);
    reboot.now += 2000;
    reboot.settle();
    if (pin)
        reboot.text("Aa345678");
    CHECK(rebooted.page() == FirstLoginPage::Main);
    reboot.action("1");
    CHECK(rebooted.page() == FirstLoginPage::Home &&
          contains_text(view.root(), "Nenhuma carteira"));
    reboot.action("ok");
    reboot.action("2");
    reboot.action("1");
    CHECK(rebooted.page() == FirstLoginPage::Name);
    reboot.action("key cancel");
    CHECK(rebooted.page() == FirstLoginPage::Settings);
}
void seed_notice_flow() {
    Service service;
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 2000};
    d.acknowledge_seed_notice = false;
    app.start(0);
    d.settle();
    d.action("1");
    d.text("Public notice fixture");
    d.action("1");
    CHECK(app.page() == FirstLoginPage::SeedNotice && service.store.data.empty());
    CHECK(contains_text(view.root(), "nunca mais") && !contains_text(view.root(), "1. "));
    snapshot(view, "device-seed-notice.ppm");
    d.action("key cancel");
    CHECK(app.page() == FirstLoginPage::WordCount && service.store.data.empty());
    d.action("1");
    CHECK(app.page() == FirstLoginPage::SeedNotice);
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::WordReview && contains_text(view.root(), "1. "));
}
void create_common_flow(bool imported) {
    Service service;
    SetupRequest initial;
    initial.pin.assign("Aa345678");
    initial.mobile_access = MobileAccess::Pin;
    initial.name = "A";
    initial.pin.assign("Aa345678");
    initial.transaction_phrase.assign("A original phrase");
    SetupCandidate candidate;
    CHECK(service.vault.prepare(initial, candidate) == VaultError::None);
    CHECK(service.vault.commit(candidate.record) == VaultError::None);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(service.vault.device_unlock("Aa345678", 0, session, retry) == VaultError::None);
    SetupRequest second;
    second.name = "B";
    second.transaction_phrase.assign("B original phrase");
    CHECK(service.vault.prepare_additional(second, session, candidate) == VaultError::None);
    CHECK(service.vault.append(candidate.record, session) == VaultError::None);
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 2000};
    app.start(0);
    d.settle();
    d.text("Aa345678");
    auto start = [&](const char* name) {
        d.action("2");
        d.action(imported ? "2" : "1");
        d.text(name);
        d.action("1");
        if (imported) {
            for (unsigned i = 0; i < 11; ++i)
                d.text("abandon");
            d.text("about");
        }
        CHECK(app.page() == FirstLoginPage::WordReview);
        d.action("fim");
        d.action("ok");
        d.action("key cancel");
        CHECK(app.page() == FirstLoginPage::TransactionIntro &&
              contains_text(view.root(), "Usar em todas as carteiras"));
    };
    auto create_shared = [&] {
        start("C");
        CHECK(!lv_obj_has_state(find(view.root(), &lv_checkbox_class), LV_STATE_CHECKED));
        snapshot(view, "device-create-common-option.ppm");
        d.action("key confirm");
        snapshot(view, "device-create-common-checked.ppm");
        d.action("ok");
        d.text("Public common phrase");
        d.text("Public common phrase");
        CHECK(app.page() == FirstLoginPage::OldPhrase && contains_text(view.root(), "A"));
        d.text("A original phrase");
        CHECK(app.page() == FirstLoginPage::OldPhrase && contains_text(view.root(), "B"));
        d.text("B original phrase");
        CHECK(app.page() == FirstLoginPage::Backup);
    };
    const auto before = service.store.data;
    create_shared();
    CHECK(service.store.data == before);
    d.action("key cancel");
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::Main && service.store.data == before);
    create_shared();
    d.action("fim");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Success);
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Home && app.profile().shared_transaction_phrase);
    d.action("key back");
    std::vector<WalletProfile> profiles;
    CHECK(service.vault.device_status(d.now, profiles, retry) == VaultError::None &&
          profiles.size() == 3);
    const auto a_id = profiles[0].record_id, b_id = profiles[1].record_id,
               c_id = profiles[2].record_id;
    // Existing policy reuses the known phrase; it never asks to create/confirm another.
    start("D");
    CHECK(lv_obj_has_state(find(view.root(), &lv_checkbox_class), LV_STATE_CHECKED));
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::CommonPhrase);
    d.text("Wrong common phrase");
    CHECK(app.page() == FirstLoginPage::CommonPhrase);
    d.text("Wrong common phrase");
    CHECK(app.page() == FirstLoginPage::Unlock);
    d.text("Aa345678");
    start("D");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::CommonPhrase);
    snapshot(view, "device-reuse-common-phrase.ppm");
    d.text("Public common phrase");
    CHECK(app.page() == FirstLoginPage::Backup);
    d.action("fim");
    d.action("ok");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Home && app.profile().shared_transaction_phrase);
    CHECK(service.vault.device_status(d.now, profiles, retry) == VaultError::None &&
          profiles.size() == 4);
    CHECK(profiles[0].record_id == a_id && profiles[1].record_id == b_id &&
          profiles[2].record_id == c_id);
    CHECK(service.vault.verify_common_phrase(session, "Public common phrase") == VaultError::None);
    // Unchecking while editing affects only A and disables future common reuse.
    d.action("key back");
    d.action("2");
    d.action("5");
    d.action("1");
    d.text("Aa345678");
    d.text("Public common phrase");
    CHECK(app.page() == FirstLoginPage::TransactionIntro &&
          lv_obj_has_state(find(view.root(), &lv_checkbox_class), LV_STATE_CHECKED));
    d.action("key confirm");
    d.action("ok");
    d.text("A independent phrase");
    d.text("A independent phrase");
    CHECK(app.page() == FirstLoginPage::Home && !app.profile().shared_transaction_phrase);
    CHECK(service.vault.verify_phrase(session, "Public common phrase", 1) == VaultError::None);
    FirstLogin rebooted(view, service);
    Driver reboot{view, rebooted, {}, d.now};
    rebooted.start(d.now);
    reboot.now += 2000;
    reboot.settle();
    reboot.text("Aa345678");
    CHECK(rebooted.page() == FirstLoginPage::Main && !rebooted.profile().shared_transaction_phrase);
}
void language_flow() {
    Service service;
    service.language_code.clear();
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 0};
    app.start(0);
    CHECK(app.page() == FirstLoginPage::Language && !app.mobile_allowed());
    snapshot(view, "language-first-step.ppm");
    CHECK(contains_text(view.root(), "Português") && contains_text(view.root(), "日本語"));
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::Language);
    service.language_write_fail = true;
    d.action("2");
    CHECK(app.page() == FirstLoginPage::Language && service.language_code.empty() &&
          service.store.data.empty());
    service.language_write_fail = false;
    d.action("2");
    CHECK(app.page() == FirstLoginPage::Splash && app.language() == Language::English &&
          service.language_code == "en");
    d.now += 2000;
    d.settle();
    CHECK(app.page() == FirstLoginPage::Choice && contains_text(view.root(), "Create wallet"));
    // The selected language remains active through seed preparation and cancellation.
    d.acknowledge_seed_notice = false;
    d.action("1");
    d.text("Public language fixture");
    d.action("1");
    CHECK(app.page() == FirstLoginPage::SeedNotice &&
          contains_text(view.root(), "Keep your words safe"));
    d.action("key cancel");
    d.action("key back");
    d.action("key cancel");
    CHECK(service.language_code == "en" && service.store.data.empty());
    // Stored device credentials are independent of the language preference.
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Carteira Teste";
    request.transaction_phrase.assign("Public language phrase");
    SetupCandidate candidate;
    CHECK(service.vault.prepare(request, candidate) == VaultError::None);
    CHECK(service.vault.commit(candidate.record) == VaultError::None);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(service.vault.device_unlock("Aa345678", 0, session, retry) == VaultError::None);
    const auto saved_wallets = service.store.data;
    constexpr const char* settings[] = {"Configurações", "Settings",     "Configuración",
                                        "Paramètres",    "设置",         "設定",
                                        "Impostazioni",  "Einstellungen"};
    constexpr const char* notice[] = {"Guarde suas palavras",   "Keep your words safe",
                                      "Guarda tus palabras",    "Conservez vos mots",
                                      "妥善保管助记词",         "単語を大切に保管",
                                      "Conserva le tue parole", "Wörter sicher aufbewahren"};
    for (std::size_t i = 0; i < language_order.size(); ++i) {
        FirstLogin current(view, service);
        Driver drive{view, current, {}, 2000};
        current.start(0);
        drive.settle();
        drive.text("Aa345678");
        CHECK(current.page() == FirstLoginPage::Main);
        drive.action("2");
        drive.action("6");
        CHECK(current.page() == FirstLoginPage::Language);
        drive.action(std::to_string(i + 1));
        CHECK(current.language() == language_order[i]);
        CHECK(current.page() == FirstLoginPage::Settings &&
              contains_text(view.root(), settings[i]));
        CHECK(service.language_code == language_codes[static_cast<std::size_t>(language_order[i])]);
        snapshot(view,
                 (std::string("language-settings-") + service.language_code + ".ppm").c_str());
        // A new setup flow uses the same language without exposing seed before the notice.
        drive.acknowledge_seed_notice = false;
        drive.action("1");
        drive.text("Public language preview");
        drive.action("1");
        CHECK(current.page() == FirstLoginPage::SeedNotice &&
              contains_text(view.root(), notice[i]));
        snapshot(view,
                 (std::string("language-seed-notice-") + service.language_code + ".ppm").c_str());
        drive.action("key cancel");
        drive.action("key back");
        drive.action("key cancel");
        FirstLogin rebooted(view, service);
        Driver reboot{view, rebooted, {}, 2000};
        rebooted.start(0);
        reboot.settle();
        reboot.text("Aa345678");
        CHECK(rebooted.language() == language_order[i] && rebooted.page() == FirstLoginPage::Main);
        CHECK(service.store.data == saved_wallets);
        CHECK(service.vault.verify_phrase(session, "Public language phrase", 0) ==
              VaultError::None);
    }
    service.language_read_fail = true;
    FirstLogin read_error(view, service);
    read_error.start(0);
    CHECK(read_error.page() == FirstLoginPage::Fault && service.store.data == saved_wallets);
    service.language_read_fail = false;
    service.language_code = "unsupported";
    FirstLogin invalid(view, service);
    invalid.start(0);
    CHECK(invalid.page() == FirstLoginPage::Fault && service.store.data == saved_wallets);
}
void device_name_flow() {
    CHECK(!valid_device_name(""));
    CHECK(!valid_device_name("abcdefghijklmnop"));
    CHECK(!valid_device_name(" Flips"));
    CHECK(!valid_device_name("Flips\n"));
    CHECK(valid_device_name("Flips") && valid_device_name("家族"));
    CHECK(valid_device_name("ABCDEFGHIJKLM") && !valid_device_name("ABCDEFGHIJKLMN"));
    CHECK(full_device_name("ABCDEFGHIJKLM").size() == 29);
    CHECK(full_device_name("Flips") == "BJJ-Cold-Wallet-Flips");
    Service service;
    service.language_code.clear();
    service.device_name.clear();
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 0};
    app.start(0);
    CHECK(app.page() == FirstLoginPage::Language);
    d.action("1");
    CHECK(app.page() == FirstLoginPage::DeviceName && !app.mobile_allowed());
    snapshot(view, "device-name-initial.ppm");
    d.text("EsteNomeMuitoLongo");
    CHECK(app.page() == FirstLoginPage::DeviceName && service.device_name.empty());
    service.name_write_fail = true;
    d.text("Flips");
    CHECK(app.page() == FirstLoginPage::DeviceName && service.device_name.empty());
    service.name_write_fail = false;
    d.text("Flips");
    CHECK(app.page() == FirstLoginPage::Splash && app.device_name() == "BJJ-Cold-Wallet-Flips");
    d.now += 2000;
    d.settle();
    CHECK(app.page() == FirstLoginPage::Choice);
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Wallet A";
    request.pin.assign("Aa345678");
    request.transaction_phrase.assign("Public Name Phrase");
    SetupCandidate candidate;
    CHECK(service.vault.prepare(request, candidate) == VaultError::None);
    CHECK(service.vault.commit(candidate.record) == VaultError::None);
    const auto wallet_before = service.store.data;
    FirstLogin rebooted(view, service);
    Driver r{view, rebooted, {}, 0};
    rebooted.start(0);
    r.now = 2000;
    r.settle();
    CHECK(rebooted.page() == FirstLoginPage::Unlock && !rebooted.mobile_allowed());
    CHECK(rebooted.device_name() == "BJJ-Cold-Wallet-Flips");
    r.text("Aa345678");
    r.action("2");
    r.action("fim");
    r.action("7");
    CHECK(rebooted.page() == FirstLoginPage::DeviceName &&
          contains_text(view.root(), "BJJ-Cold-Wallet-Flips"));
    snapshot(view, "device-name-settings.ppm");
    const auto unlocked_wallet = service.store.data;
    service.name_write_fail = true;
    r.text("Outro");
    CHECK(rebooted.page() == FirstLoginPage::DeviceName &&
          rebooted.device_name() == "BJJ-Cold-Wallet-Flips");
    CHECK(service.store.data == unlocked_wallet);
    service.name_write_fail = false;
    r.text("Outro");
    CHECK(rebooted.page() == FirstLoginPage::Settings &&
          rebooted.device_name() == "BJJ-Cold-Wallet-Outro");
    CHECK(service.store.data == unlocked_wallet);
    r.action("fim");
    r.action("7");
    r.action("key back");
    CHECK(rebooted.page() == FirstLoginPage::Settings && service.device_name == "Outro");
    r.action("fim");
    r.action("7");
    r.text("Flips");
    CHECK(rebooted.device_name() == "BJJ-Cold-Wallet-Flips");
    // Existing installations with no stored name retain their identity and PIN gate.
    service.device_name.clear();
    FirstLogin legacy(view, service);
    Driver old{view, legacy, {}, 0};
    legacy.start(0);
    old.now = 2000;
    old.settle();
    CHECK(legacy.page() == FirstLoginPage::Unlock && !legacy.mobile_allowed() &&
          legacy.device_name() == "BJJ-Cold-Wallet");
    service.name_read_fail = true;
    FirstLogin broken(view, service);
    broken.start(0);
    CHECK(broken.page() == FirstLoginPage::Fault);
    CHECK(!wallet_before.empty());
}
void legacy_cardano_cache_flow() {
    Service service;
    service.store.cache_enabled = true;
    service.legacy_cardano_cache = true;
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Legacy Wallet";
    request.transaction_phrase.assign("Public Legacy Phrase");
    SetupCandidate candidate;
    CHECK(service.vault.prepare(request, candidate) == VaultError::None);
    CHECK(service.vault.commit(candidate.record) == VaultError::None);
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 0};
    app.start(0);
    d.now = 2000;
    d.settle();
    d.text("Aa345678");
    CHECK(app.page() == FirstLoginPage::Main);
    const auto saved_wallet = service.store.data;
    d.action("1");
    d.action("1");
    CHECK(app.page() == FirstLoginPage::AddressList);
    d.action("8");
    d.action("1");
    CHECK(app.page() == FirstLoginPage::AddressPhrase);
    d.action("key back");
    CHECK(app.page() == FirstLoginPage::AddressNetwork);
    d.action("2");
    d.text("Wrong Public Phrase");
    CHECK(app.page() == FirstLoginPage::AddressPhrase);
    d.text("Public Legacy Phrase");
    CHECK(app.page() == FirstLoginPage::AddressDetail);
    CHECK(contains_text(view.root(), "SNEK") && find(view.root(), &lv_qrcode_class));

    std::string generation;
    std::vector<PublicAccount> accounts;
    CHECK(service.vault.public_accounts(0, generation, accounts) == VaultError::None);
    const auto cardano = std::find_if(accounts.begin(), accounts.end(), [](const auto& row) {
        return row.coin == "cardano" && row.mode == PublicWalletMode::Hidden;
    });
    CHECK(cardano != accounts.end() && cardano->address.rfind("addr1", 0) == 0);
    CHECK(contains_text(view.root(), cardano->address));

    // Reopen the wallet to verify that every asset can use the refreshed cache.
    d.action("key back");
    d.action("key back");
    d.action("key back");
    d.action("1");
    d.action("8");
    const std::array<const char*, 6> headings = {"Cardano", "SNEK", "MIN", "IAG", "WMTX", "DJED"};
    for (std::size_t i = 0; i < headings.size(); ++i) {
        d.action(std::to_string(i + 1));
        CHECK(app.page() == FirstLoginPage::AddressDetail);
        CHECK(contains_text(view.root(), headings[i]) &&
              contains_text(view.root(), cardano->address));
        CHECK(find(view.root(), &lv_qrcode_class));
        d.action("key back");
    }
    CHECK(saved_wallet == service.store.data);
}
void selected_language_through_setup() {
    Service service;
    service.language_code.clear();
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 0};
    d.acknowledge_seed_notice = false;
    app.start(0);
    d.action("3");
    CHECK(app.language() == Language::Spanish && service.language_code == "es");
    d.now += 2000;
    d.settle();
    d.action("2");
    d.text("Prueba pública");
    d.action("1");
    for (unsigned i = 0; i < 11; ++i)
        d.text("abandon");
    d.text("about");
    CHECK(app.page() == FirstLoginPage::SeedNotice &&
          contains_text(view.root(), "Guarda tus palabras"));
    d.action("ok");
    d.action("fim");
    d.action("ok");
    d.action("key cancel");
    d.action("ok");
    d.text("Aa345678");
    d.text("Aa345678");
    CHECK(app.page() == FirstLoginPage::TransactionIntro && app.language() == Language::Spanish);
    d.action("ok");
    d.text("Public language phrase");
    d.text("Public language phrase");
    CHECK(app.page() == FirstLoginPage::SeedNotice);
    d.action("ok");
    d.action("fim");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Success &&
          contains_text(view.root(), "Cartera configurada"));
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Home && contains_text(view.root(), "Mis carteras") &&
          service.language_code == "es");
    FirstLogin rebooted(view, service);
    Driver reboot{view, rebooted, {}, d.now};
    rebooted.start(d.now);
    reboot.now += 2000;
    reboot.settle();
    reboot.text("Aa345678");
    CHECK(rebooted.language() == Language::Spanish && rebooted.page() == FirstLoginPage::Main);
}
void hardware_access_flow() {
    Service service;
    SetupRequest request;
    request.pin.assign("Aa345678");
    request.mobile_access = MobileAccess::Pin;
    request.name = "Access fixture";
    request.pin.assign("Aa1234");
    request.mobile_access = MobileAccess::Pin;
    request.transaction_phrase.assign("Public Access Phrase");
    SetupCandidate candidate;
    CHECK(service.vault.prepare(request, candidate) == VaultError::None);
    CHECK(service.vault.commit(candidate.record) == VaultError::None);
    UnlockSession session;
    std::uint64_t retry = 0;
    CHECK(service.vault.device_unlock("Aa1234", 0, session, retry) == VaultError::None);
    TouchView view(lv_screen_active());
    FirstLogin app(view, service);
    Driver d{view, app, {}, 2000};
    app.start(0);
    d.settle();
    d.text("Aa1234");
    CHECK(app.page() == FirstLoginPage::Main);
    SigningDevice device;
    device.peers = {{"Phone A", "0:11:22:33:44:55:66", true, false}};
    app.poll_signing(device, d.now);
    d.action("2");
    d.action("fim");
    d.action("9");
    CHECK(app.page() == FirstLoginPage::Devices &&
          contains_text(view.root(), "0:11:22:33:44:55:66"));
    snapshot(view, "access-devices.ppm");
    d.action("1");
    CHECK(app.page() == FirstLoginPage::DeviceActions);
    snapshot(view, "access-device-actions.ppm");
    d.action("2");
    CHECK(app.page() == FirstLoginPage::DeviceBlock);
    snapshot(view, "access-device-block.ppm");
    d.action("key cancel");
    CHECK(!device.peers[0].blocked);
    d.action("2");
    d.action("fim");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Devices && device.peers[0].blocked && !device.link_up);
    CHECK(contains_text(view.root(), "Bloqueado"));
    snapshot(view, "access-device-blocked.ppm");
    d.action("1");
    d.action("1");
    CHECK(app.page() == FirstLoginPage::DeviceDelete);
    snapshot(view, "access-device-delete.ppm");
    d.action("fim");
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Devices && device.peers.empty());
    d.action("ok");
    CHECK(app.page() == FirstLoginPage::Settings);
    CHECK(app.prompt_mobile(77, "0:aa:bb:cc:dd:ee:ff", 123456));
    d.action("key cancel");
    const auto decision = app.take_mobile_decision();
    CHECK(decision && !decision->second);
    // Rewrap the public fixture as a legacy numeric device PIN, preserving the
    // wallet ciphertexts and base key, to exercise the mandatory upgrade.
    auto& data = service.store.data;
    data[8] = 1;
    data[9] = 0;
    data[10] = 1;
    crypto::Salt salt{};
    std::copy_n(data.begin() + 24, 16, salt.begin());
    crypto::Nonce nonce{};
    std::copy_n(data.begin() + 40, 12, nonce.begin());
    crypto::Key key;
    crypto::Tag tag{};
    CHECK(crypto::keystore_key(crypto::bytes("001234"), salt, key));
    CHECK(crypto::seal(key, nonce, {session.pin_key.value.data(), 32}, {data.data() + 52, 32}, tag,
                       {data.data() + 8, 3}));
    std::copy(tag.begin(), tag.end(), data.begin() + 84);
    std::array<std::uint8_t, 32> checksum{};
    CHECK(crypto::sha256({data.data(), data.size() - 32}, checksum));
    std::copy(checksum.begin(), checksum.end(), data.end() - 32);
    FirstLogin upgraded(view, service);
    Driver u{view, upgraded, {}, 2000};
    upgraded.start(0);
    u.settle();
    u.text("001234");
    CHECK(upgraded.page() == FirstLoginPage::PinChoice && !upgraded.mobile_allowed());
    u.action("key back");
    CHECK(upgraded.page() == FirstLoginPage::PinChoice);
    u.action("ok");
    u.text("123456");
    CHECK(upgraded.page() == FirstLoginPage::PinEntry);
    u.text("Bb1234");
    u.text("Bb1234");
    CHECK(upgraded.page() == FirstLoginPage::Home && upgraded.mobile_allowed());
    CHECK(service.vault.inspect(candidate.profile) == VaultError::None &&
          candidate.profile.pin_policy_current);
    crypto::Entropy entropy;
    Credential pass;
    CHECK(service.vault.device_unlock("Bb1234", 1, session, retry) == VaultError::None);
    CHECK(service.vault.recover(session, "Public Access Phrase", entropy, pass) ==
          VaultError::None);
}

} // namespace
int main() {
    lv_init();
    auto* display = lv_display_create(es3c28p::width, es3c28p::height);
    std::array<std::uint8_t, es3c28p::width * 20 * 2> pixels{};
    lv_display_set_buffers(display, pixels.data(), nullptr, pixels.size(),
                           LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display,
                            [](auto* disp, const auto*, auto*) { lv_display_flush_ready(disp); });
    if (std::getenv("AIRLOCK_TEST_ACCESS_ONLY")) {
        hardware_access_flow();
        lv_display_delete(display);
        lv_deinit();
        std::cout << checks << " hardware access UI and legacy PIN upgrade checks passed\n";
        return 0;
    }
    const bool language_only = std::getenv("AIRLOCK_TEST_LANGUAGE_ONLY") != nullptr;
    const bool common_only = std::getenv("AIRLOCK_TEST_COMMON_ONLY") != nullptr;
    if (!common_only && !language_only)
        for (auto words : {12U, 18U, 24U})
            for (bool imported : {false, true})
                for (bool pin : {true})
                    for (bool pass : {false, true})
                        for (bool mobile_pin : {true}) {
                            if (!pin && mobile_pin)
                                continue;
                            flow(words, imported, pin, pass, mobile_pin,
                                 words == 12 && !imported && pin && pass && !mobile_pin);
                        }
    if (!common_only && !language_only) {
        saved_wallet_flow();
        legacy_cardano_cache_flow();
    }
    device_name_flow();
    if (!language_only) {
        seed_notice_flow();
        create_common_flow(false);
        create_common_flow(true);
    }
    language_flow();
    selected_language_through_setup();
    if (!common_only && !language_only) {
        delete_wallet_flow(true);
    }
    lv_display_delete(display);
    lv_deinit();
    std::cout << checks
              << " first-login checks passed: 12 create/import combinations, splash, backup, "
                 "mandatory PIN, optional passphrase, restart, wrong PIN and corruption.\n";
}
