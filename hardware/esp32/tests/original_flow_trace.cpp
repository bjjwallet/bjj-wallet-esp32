#include "hardware/es3c28p.hpp"
// SPDX-License-Identifier: GPL-3.0-or-later
// Test-only LVGL driver. Public fixtures only; never instantiated by the simulator.
#include "ui/wallet_app.hpp"
#include <array>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <string>
using namespace airlock;
struct FixtureService final : WalletService {
    bool present = false, fail = false;
    std::optional<WalletResult> result;
    bool available() const override {
        return true;
    }
    bool exists() const override {
        return present;
    }
    bool busy() const override {
        return false;
    }
    bool submit(WalletJob job) override {
        present = false;
        WalletResult r;
        r.kind = job.kind;
        r.token = job.token;
        if (fail) {
            r.error = WalletError::Crypto;
            fail = false;
        } else if (job.kind == WalletWork::Generate) {
            for (std::size_t i = 0; i < 24; ++i)
                r.words[i].assign(i == 23 ? "art" : "abandon");
            present = true;
        } else {
            bool valid = true;
            for (std::size_t i = 0; i < job.word_count; ++i)
                valid &= job.words[i].view() == (i + 1 == job.word_count ? "about" : "abandon");
            present = valid;
            if (!valid)
                r.error = WalletError::InvalidMnemonic;
        }
        result.emplace(std::move(r));
        return true;
    }
    std::optional<WalletResult> poll() override {
        auto r = std::move(result);
        result.reset();
        return r;
    }
};
lv_obj_t* find(lv_obj_t* root, const lv_obj_class_t* type) {
    if (lv_obj_check_type(root, type))
        return root;
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(root); ++i)
        if (auto* r = find(lv_obj_get_child(root, i), type))
            return r;
    return nullptr;
}
std::string hex(std::string text) {
    while (!text.empty() && text.back() == '\n')
        text.pop_back();
    const char* digits = "0123456789abcdef";
    std::string result;
    for (unsigned char c : text) {
        result += digits[c >> 4];
        result += digits[c & 15];
    }
    return result;
}
int main() {
    lv_init();
    auto* display = lv_display_create(airlock::es3c28p::width, airlock::es3c28p::height);
    lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
    std::array<std::uint16_t, airlock::es3c28p::width * 20> pixels{};
    lv_display_set_buffers(display, pixels.data(), nullptr, sizeof(pixels),
                           LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display,
                            [](auto* d, const auto*, auto*) { lv_display_flush_ready(d); });
    FixtureService service;
    auto app = std::make_unique<WalletApp>(lv_screen_active(), service);
    std::string line;
    while (std::getline(std::cin, line)) {
        const auto tab = line.find('\t');
        const auto action = line.substr(0, tab),
                   value = tab == std::string::npos ? "" : line.substr(tab + 1);
        if (action == "reset") {
            app.reset();
            service.present = value == "1";
            service.fail = false;
            app = std::make_unique<WalletApp>(lv_screen_active(), service);
        } else if (action == "fail")
            service.fail = true;
        else if (action == "cancel")
            app->cancel();
        else if (action == "create" || action == "import" || action == "count") {
            auto* list = lv_obj_get_child(app->view().root(), 1);
            const int index = action == "create" ? 2 : action == "import" ? 3 : std::stoi(value);
            lv_obj_send_event(lv_obj_get_child(list, index), LV_EVENT_CLICKED, nullptr);
        } else if (action == "confirm") {
            if (app->page() == WalletPage::Backup)
                lv_obj_scroll_to_y(lv_obj_get_child(app->view().root(), 1), 10000, LV_ANIM_OFF);
            lv_obj_send_event(lv_obj_get_child(lv_obj_get_child(app->view().root(), -1), 1),
                              LV_EVENT_CLICKED, nullptr);
        } else if (action == "input") {
            auto* area = find(app->view().root(), &lv_textarea_class);
            auto* keyboard = find(app->view().root(), &lv_keyboard_class);
            if (!area || !keyboard)
                return 2;
            lv_textarea_set_text(area, value.c_str());
            lv_obj_send_event(keyboard, LV_EVENT_READY, nullptr);
        } else
            return 3;
        app->consume();
        std::string kind = "text", text;
        int hidden = 0;
        if (app->page() == WalletPage::Menu)
            kind = "menu";
        else if (app->page() == WalletPage::WordCount)
            kind = "count";
        else if (app->page() == WalletPage::Input) {
            kind = "input";
            text = lv_label_get_text(lv_obj_get_child(app->view().root(), 0));
            hidden = lv_textarea_get_password_mode(find(app->view().root(), &lv_textarea_class));
        } else {
            if (app->page() == WalletPage::Backup)
                kind = "backup";
            auto* label = find(lv_obj_get_child(app->view().root(), 1), &lv_label_class);
            if (label)
                text = lv_label_get_text(label);
        }
        std::cout << kind << '\t' << service.present << '\t' << hidden << '\t' << hex(text) << '\n';
    }
    app.reset();
    lv_display_delete(display);
    lv_deinit();
}
