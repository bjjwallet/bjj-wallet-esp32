#include "hardware/es3c28p.hpp"
// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/wallet_app.hpp"
#include <algorithm>
#include "simulator/native_wallet.hpp"
#include <array>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <thread>
#include <unistd.h>

using namespace airlock;
namespace {
int checks = 0;
#define CHECK(x)                                                                                   \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(x)) {                                                                                \
            std::cerr << "Wallet UI check failed at line " << __LINE__ << ": " #x "\n";            \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
lv_obj_t* find(lv_obj_t* root, const lv_obj_class_t* type) {
    if (lv_obj_check_type(root, type))
        return root;
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(root); ++i)
        if (auto* object = find(lv_obj_get_child(root, i), type))
            return object;
    return nullptr;
}
void select(WalletApp& app, int index) {
    auto* list = lv_obj_get_child(app.view().root(), 1);
    auto* button = lv_obj_get_child(list, index);
    CHECK(button && lv_obj_check_type(button, &lv_button_class));
    lv_obj_scroll_to_view(button, LV_ANIM_OFF);
    lv_obj_send_event(button, LV_EVENT_CLICKED, nullptr);
    app.consume();
}
void confirm(WalletApp& app) {
    auto* footer = lv_obj_get_child(app.view().root(), -1);
    auto* button = lv_obj_get_child(footer, 1);
    CHECK(button && lv_obj_check_type(button, &lv_button_class));
    lv_obj_send_event(button, LV_EVENT_CLICKED, nullptr);
    app.consume();
}
void submit(WalletApp& app, std::string_view value) {
    CHECK(app.page() == WalletPage::Input);
    auto* area = find(app.view().root(), &lv_textarea_class);
    auto* keyboard = find(app.view().root(), &lv_keyboard_class);
    CHECK(area && keyboard);
    Credential text;
    CHECK(text.assign(value));
    lv_textarea_set_text(area, text.view().data());
    lv_obj_send_event(keyboard, LV_EVENT_READY, nullptr);
    app.consume();
}
void finish_job(WalletApp& app) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    while (app.page() == WalletPage::Busy) {
        CHECK(std::chrono::steady_clock::now() < deadline);
        app.consume();
        lv_tick_inc(5);
        lv_timer_handler();
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
}
void glyphs(lv_obj_t* object) {
    if (lv_obj_check_type(object, &lv_label_class)) {
        auto* p = reinterpret_cast<const unsigned char*>(lv_label_get_text(object));
        const auto* font = lv_obj_get_style_text_font(object, LV_PART_MAIN);
        while (*p) {
            unsigned code = *p++;
            unsigned continuation = 0;
            if (code >= 0xf0) {
                continuation = 3;
                code &= 7;
            } else if (code >= 0xe0) {
                continuation = 2;
                code &= 15;
            } else if (code >= 0xc0) {
                continuation = 1;
                code &= 31;
            }
            while (continuation--)
                code = (code << 6) | (*p++ & 63);
            if (code < 32)
                continue;
            lv_font_glyph_dsc_t glyph{};
            CHECK(lv_font_get_glyph_dsc(font, &glyph, code, 0) && !glyph.is_placeholder);
        }
    }
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(object); ++i)
        glyphs(lv_obj_get_child(object, i));
}
void snapshot(lv_obj_t* root, const std::string& name) {
    lv_obj_update_layout(root);
    auto* buffer = lv_snapshot_take(root, LV_COLOR_FORMAT_RGB888);
    CHECK(buffer != nullptr);
    std::ofstream file(name, std::ios::binary);
    file << "P6\n" << buffer->header.w << ' ' << buffer->header.h << "\n255\n";
    for (unsigned y = 0; y < buffer->header.h; ++y)
        for (unsigned x = 0; x < buffer->header.w; ++x) {
            const auto* p = buffer->data + y * buffer->header.stride + 3 * x;
            const char rgb[] = {static_cast<char>(p[2]), static_cast<char>(p[1]),
                                static_cast<char>(p[0])};
            file.write(rgb, 3);
        }
    CHECK(file.good());
    lv_draw_buf_destroy(buffer);
}
bool contains(lv_obj_t* root, const char* text) {
    if (lv_obj_check_type(root, &lv_label_class) && std::strstr(lv_label_get_text(root), text))
        return true;
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(root); ++i)
        if (contains(lv_obj_get_child(root, i), text))
            return true;
    return false;
}
// Test double only: no fabricated peer/request/signature is installed in the app.
struct TestDevice : DeviceService {
    std::vector<TrustedDevice> peers;
    std::optional<PairingRequest> pairing;
    std::optional<TransactionPrompt> transaction;
    std::optional<SigningJob> job;
    std::optional<SigningResult> result;
    std::optional<SignOutcome> response;
    std::string error;
    bool active = true, online = true, accepted = false;
    unsigned replies = 0;
    std::uint64_t revision = 0;
    std::vector<TrustedDevice> trusted_devices() override {
        return peers;
    }
    std::uint64_t devices_revision() const override {
        return revision;
    }
    bool remove_device(std::string_view id) override {
        CHECK(peers.size() == 1 && peers[0].id == id);
        peers.clear();
        ++revision;
        return true;
    }
    std::optional<PairingRequest> poll_pairing() override {
        auto r = pairing;
        pairing.reset();
        return r;
    }
    bool pairing_active(std::uint64_t) const override {
        return active;
    }
    void respond_pairing(std::uint64_t, bool accept) override {
        ++replies;
        accepted = accept;
    }
    std::optional<TransactionPrompt> poll_transaction() override {
        auto r = std::move(transaction);
        transaction.reset();
        return r;
    }
    bool connected(std::uint64_t) const override {
        return online;
    }
    bool sign(SigningJob value) override {
        job.emplace(std::move(value));
        return true;
    }
    std::optional<SigningResult> poll_signature() override {
        auto r = std::move(result);
        result.reset();
        return r;
    }
    void respond_transaction(SignOutcome value, std::string_view text) override {
        response.emplace(std::move(value));
        error = text;
    }
    void request() {
        response.reset();
        error.clear();
        job.reset();
        TransactionPrompt prompt;
        prompt.symbol = "TEST";
        prompt.coin = "test";
        prompt.amount = "100";
        prompt.decimals = 2;
        prompt.recipient = "public-test-recipient";
        prompt.request.id_json = "42";
        prompt.request.connection = 7;
        CHECK(prompt.request.payload.assign("{\"public_fixture\":true}"));
        CHECK(prompt.request.password.assign("Public-Password1!"));
        transaction.emplace(std::move(prompt));
    }
};
} // namespace
int main() {
    char temporary[] = "/tmp/bjj-airlock-ui-test-XXXXXX";
    CHECK(mkdtemp(temporary));
    lv_init();
    auto* display = lv_display_create(es3c28p::width, es3c28p::height);
    lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
    std::array<std::uint16_t, es3c28p::width * 20> pixels{};
    lv_display_set_buffers(display, pixels.data(), nullptr, sizeof(pixels),
                           LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display,
                            [](auto* d, const auto*, auto*) { lv_display_flush_ready(d); });
    {
        NativeWallet service(std::filesystem::path(temporary) / "wallet");
        WalletApp app(lv_screen_active(), service);
        // All seven languages run through the same actual menu and input rules.
        // Snapshots contain only public UI text, never a mnemonic or password.
        for (std::size_t i = 0; i < language_names.size(); ++i) {
            select(app, 4);
            select(app, static_cast<int>(std::find(language_order.begin(), language_order.end(),
                                                   static_cast<Language>(i)) -
                                         language_order.begin()));
            CHECK(app.language() == static_cast<Language>(i));
            glyphs(app.view().root());
            snapshot(app.view().root(), std::string("wallet-menu-") + language_codes[i] + ".ppm");
            for (const int item : {0, 1, 5, 6, 7, 8}) {
                select(app, item);
                glyphs(app.view().root());
                app.cancel();
            }
            select(app, 2);
            CHECK(app.page() == WalletPage::Intro);
            glyphs(app.view().root());
            snapshot(app.view().root(), std::string("wallet-intro-") + language_codes[i] + ".ppm");
            confirm(app);
            CHECK(app.stage() == WalletStage::Passphrase);
            glyphs(app.view().root());
            lv_obj_update_layout(app.view().root());
            auto* area = find(app.view().root(), &lv_textarea_class);
            auto* keyboard = find(app.view().root(), &lv_keyboard_class);
            CHECK(!lv_textarea_get_password_mode(area));
            CHECK(lv_obj_get_y(keyboard) >= lv_obj_get_y(area) + lv_obj_get_height(area));
            CHECK(lv_obj_get_height(keyboard) >= 140);
            snapshot(app.view().root(), std::string("wallet-input-") + language_codes[i] + ".ppm");
            submit(app, "bad");
            CHECK(app.page() == WalletPage::Error);
            glyphs(app.view().root());
            app.cancel();
            CHECK(app.page() == WalletPage::Menu && !service.exists());
        }
        app.set_language(Language::English);
        select(app, 2);
        confirm(app);
        submit(app, "Public-Passphrase1!");
        CHECK(app.stage() == WalletStage::ConfirmPassphrase);
        submit(app, "Public-NotTheSame1!");
        CHECK(app.page() == WalletPage::Error);
        CHECK(app.stage() == WalletStage::Passphrase);
        confirm(app);
        submit(app, "Public-Passphrase1!");
        submit(app, "Public-Passphrase1!");
        CHECK(app.page() == WalletPage::Intro && app.stage() == WalletStage::Password);
        app.cancel();
        CHECK(app.page() == WalletPage::Intro && app.stage() == WalletStage::Passphrase);
        confirm(app);
        submit(app, "Public-Passphrase1!");
        submit(app, "Public-Passphrase1!");
        confirm(app);
        submit(app, "Public-Password1!");
        submit(app, "Public-Password1!");
        CHECK(app.page() == WalletPage::Busy);
        app.cancel();
        CHECK(app.page() == WalletPage::Busy);
        finish_job(app);
        CHECK(app.page() == WalletPage::BackupIntro && service.exists());
        confirm(app);
        CHECK(app.page() == WalletPage::Backup);
        app.cancel();
        CHECK(app.page() == WalletPage::Backup);
        std::array<MnemonicWord, 24> generated;
        auto* scroller = lv_obj_get_child(app.view().root(), 1);
        auto* label = find(scroller, &lv_label_class);
        std::string_view text(lv_label_get_text(label));
        for (auto& word : generated) {
            const auto end = text.find('\n');
            CHECK(end != std::string_view::npos && end > 4);
            CHECK(word.assign(text.substr(4, end - 4)) && is_mnemonic_word(word.view()));
            text.remove_prefix(end + 1);
        }
        confirm(app);
        CHECK(app.page() == WalletPage::Backup); // scroll gate enforced
        lv_obj_scroll_to_y(scroller, 10000, LV_ANIM_OFF);
        confirm(app);
        CHECK(app.stage() == WalletStage::VerifyWord);
        submit(app, "incorrect");
        CHECK(app.page() == WalletPage::Error);
        CHECK(contains(app.view().root(), "Your input: incorrect"));
        CHECK(contains(app.view().root(), "Correct: "));
        // Compare without logging or writing screenshots of the generated word.
        CHECK(contains(app.view().root(), generated[0].view().data()));
        confirm(app);
        for (const auto& word : generated)
            submit(app, word.view());
        CHECK(app.stage() == WalletStage::VerifyPassphrase);
        submit(app, "Public-wrong");
        CHECK(contains(app.view().root(), "Correct: Public-Passphrase1!"));
        confirm(app);
        submit(app, "Public-Passphrase1!");
        CHECK(app.stage() == WalletStage::VerifyPassword);
        submit(app, "Public-wrong");
        CHECK(contains(app.view().root(), "Correct: Public-Password1!"));
        confirm(app);
        submit(app, "Public-Password1!");
        CHECK(app.page() == WalletPage::Success && service.exists());
        Credential recovered, passphrase;
        bool imported = true;
        CHECK(service.unlock("Public-Password1!", true, recovered, passphrase, imported) &&
              !imported);
        CHECK(passphrase.equals("Public-Passphrase1!"));
        confirm(app);
        // Existing-wallet warning, failed import, retry with original password
        // retained, successful recovery without a passphrase, then restart.
        select(app, 3);
        CHECK(app.page() == WalletPage::Overwrite);
        confirm(app);
        CHECK(app.page() == WalletPage::WordCount);
        select(app, 0);
        confirm(app);
        submit(app, "PublicPassword1");
        submit(app, "Public-wrong");
        CHECK(app.stage() == WalletStage::Password);
        confirm(app);
        submit(app, "PublicPassword1");
        submit(app, "PublicPassword1");
        CHECK(app.page() == WalletPage::Intro);
        confirm(app);
        submit(app, "  ");
        CHECK(app.page() == WalletPage::Error &&
              contains(app.view().root(), "Word cannot be empty"));
        confirm(app);
        submit(app, "not-a-bip39-word-at-all");
        CHECK(app.page() == WalletPage::Input);
        for (int i = 1; i < 12; ++i)
            submit(app, "abandon");
        CHECK(app.page() == WalletPage::Intro);
        confirm(app);
        submit(app, "");
        finish_job(app);
        CHECK(app.page() == WalletPage::Error && !service.exists());
        CHECK(!service.unlock("Public-Password1!", true, recovered, passphrase, imported));
        confirm(app);
        CHECK(app.page() == WalletPage::Intro && app.stage() == WalletStage::ImportWord);
        confirm(app);
        for (int i = 0; i < 11; ++i)
            submit(app, "abandon");
        submit(app, "about");
        confirm(app);
        submit(app, "");
        finish_job(app);
        CHECK(app.page() == WalletPage::Success);
        CHECK(service.unlock("PublicPassword1", true, recovered, passphrase, imported) &&
              imported && passphrase.view().empty());
        CHECK(recovered.equals("abandon abandon abandon abandon abandon abandon abandon abandon "
                               "abandon abandon abandon about"));
        confirm(app);
        select(app, 8);
        confirm(app);
        CHECK(app.take_action() == AppAction::Reboot && app.page() == WalletPage::Menu &&
              service.exists());
        select(app, 7);
        app.cancel();
        CHECK(app.take_action() == AppAction::None);
        select(app, 7);
        confirm(app);
        CHECK(app.take_action() == AppAction::Shutdown);
        for (auto code : language_codes)
            CHECK(service.save_language(code) && service.language() == code);
    }
    {
        NativeWallet service(std::filesystem::path(temporary) / "events");
        TestDevice device;
        WalletApp app(lv_screen_active(), service, &device);
        for (std::size_t i = 0; i < language_names.size(); ++i) {
            app.set_language(static_cast<Language>(i));
            select(app, 1);
            glyphs(app.view().root());
            device.peers = {{"Public test phone", "test-peer", true}};
            ++device.revision;
            app.consume();
            CHECK(contains(app.view().root(), "[Y] Public test phone"));
            select(app, 0);
            CHECK(app.page() == WalletPage::RemoveDevice);
            glyphs(app.view().root());
            if (i == static_cast<std::size_t>(Language::PortugueseBrazil))
                snapshot(app.view().root(), "wallet-remove-pt-BR.ppm");
            app.cancel();
            CHECK(device.peers.size() == 1 && app.page() == WalletPage::Connections);
            select(app, 0);
            confirm(app);
            CHECK(device.peers.empty());
            app.cancel();
            device.pairing = PairingRequest{1, 123456};
            app.consume();
            CHECK(app.page() == WalletPage::Pairing);
            if (i == static_cast<std::size_t>(Language::German))
                snapshot(app.view().root(), "wallet-pair-de.ppm");
            glyphs(app.view().root());
            app.cancel();
            CHECK(!device.accepted);
            device.pairing = PairingRequest{2, 654321};
            app.consume();
            confirm(app);
            CHECK(device.accepted);
            device.pairing = PairingRequest{3, 123456};
            app.consume();
            device.active = false;
            const auto replies = device.replies;
            confirm(app);
            CHECK(app.page() == WalletPage::Menu && device.replies == replies);
            device.active = true;
            device.request();
            app.consume();
            CHECK(app.page() == WalletPage::ReviewTransaction);
            glyphs(app.view().root());
            if (i == static_cast<std::size_t>(Language::French))
                snapshot(app.view().root(), "wallet-review-fr.ppm");
            app.cancel();
            CHECK(device.response && device.response->reason == SignEnd::Rejected);
            device.request();
            app.consume();
            confirm(app);
            CHECK(app.page() == WalletPage::Signing && device.job);
            glyphs(app.view().root());
            CHECK(device.job->request.review[1].value == "1.");
            CHECK(device.job->request.payload.view() == "{\"public_fixture\":true}");
            app.cancel();
            CHECK(app.page() == WalletPage::Signing);
            device.result = SigningResult{device.job->token, 7, "public-test-signature", "", true};
            app.consume();
            CHECK(app.page() == WalletPage::SignFeedback);
            glyphs(app.view().root());
            CHECK(device.response && device.response->reason == SignEnd::Completed &&
                  device.response->encoded == "public-test-signature");
            lv_tick_inc(2999);
            app.consume();
            CHECK(app.page() == WalletPage::SignFeedback);
            lv_tick_inc(1);
            app.consume();
            CHECK(app.page() == WalletPage::Menu);
            device.request();
            app.consume();
            lv_tick_inc(60000);
            app.consume();
            CHECK(app.page() == WalletPage::ReviewTransaction);
            lv_tick_inc(1);
            app.consume();
            CHECK(device.response && device.response->reason == SignEnd::TimedOut &&
                  app.page() == WalletPage::Menu);
            device.request();
            app.consume();
            device.online = false;
            confirm(app);
            CHECK(!device.job && device.response &&
                  device.response->reason == SignEnd::Disconnected);
            device.online = true;
            device.request();
            app.consume();
            confirm(app);
            device.result = SigningResult{device.job->token, 7, "", "Public test error", false};
            app.consume();
            CHECK(app.page() == WalletPage::SignFeedback &&
                  device.response->reason == SignEnd::Failed);
            glyphs(app.view().root());
            lv_tick_inc(3000);
            app.consume();
            device.request();
            device.transaction->amount = "invalid";
            app.consume();
            CHECK(app.page() == WalletPage::TransactionError && device.response && !device.job);
            glyphs(app.view().root());
            app.cancel();
        }
    }
    lv_display_delete(display);
    lv_deinit();
    std::filesystem::remove_all(temporary);
    std::cout << checks
              << " wallet UI checks passed: native creation/recovery, backup, cancellation, "
                 "persistence and seven languages.\n";
}
