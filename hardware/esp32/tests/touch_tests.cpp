#include "hardware/es3c28p.hpp"
// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/touch_view.hpp"
#include "core/config.hpp"
#include "ui/touch_input.hpp"
#include <array>
#include <cstdlib>
#include <fstream>
#include <iostream>

using namespace airlock;
namespace {
int checks = 0;
#define CHECK(condition)                                                                           \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(condition)) {                                                                        \
            std::cerr << "Touch check failed at line " << __LINE__ << ": " #condition "\n";        \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
lv_obj_t* find(lv_obj_t* root, const lv_obj_class_t* type) {
    if (lv_obj_check_type(root, type))
        return root;
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(root); ++i) {
        if (auto* result = find(lv_obj_get_child(root, i), type))
            return result;
    }
    return nullptr;
}
void snapshot(lv_obj_t* root, const char* filename) {
    lv_obj_update_layout(root);
    auto* buffer = lv_snapshot_take(root, LV_COLOR_FORMAT_RGB888);
    CHECK(buffer != nullptr);
    std::ofstream file(filename, std::ios::binary);
    file << "P6\n" << buffer->header.w << ' ' << buffer->header.h << "\n255\n";
    for (unsigned y = 0; y < buffer->header.h; ++y) {
        for (unsigned x = 0; x < buffer->header.w; ++x) {
            const auto* bgr = buffer->data + y * buffer->header.stride + x * 3;
            const char rgb[] = {static_cast<char>(bgr[2]), static_cast<char>(bgr[1]),
                                static_cast<char>(bgr[0])};
            file.write(rgb, 3);
        }
    }
    CHECK(file.good());
    lv_draw_buf_destroy(buffer);
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
    {
        TouchView view(lv_screen_active());
        view.menu(config::product_name, {"Create wallet", "Import wallet", "Bluetooth", "Language",
                                         "System information", "Restart"});
        lv_obj_update_layout(view.root());
        snapshot(view.root(), "touch-menu.ppm");
        auto* first = find(view.root(), &lv_button_class);
        CHECK(first != nullptr);
        CHECK(lv_obj_get_height(first) == 44);
        lv_obj_send_event(first, LV_EVENT_CLICKED, nullptr);
        auto select = view.take_event();
        CHECK(select && select->action == TouchAction::Select && select->selection == 0);
        CHECK(!view.take_event());
        const auto old_screen = view.screen_id();
        view.input("Payment password", "12+ chars: Aa, 0-9 and symbols", true);
        CHECK(view.screen_id() != old_screen);
        auto* area = find(view.root(), &lv_textarea_class);
        auto* keyboard = find(view.root(), &lv_keyboard_class);
        CHECK(area && keyboard);
        CHECK(lv_textarea_get_password_mode(area));
        lv_textarea_set_text(area, "Public-Example1!");
        snapshot(view.root(), "touch-keyboard.ppm");
        lv_obj_send_event(keyboard, LV_EVENT_READY, nullptr);
        auto input = view.take_event();
        CHECK(input && input->action == TouchAction::Submit &&
              input->value.equals("Public-Example1!"));
        CHECK(std::string(lv_textarea_get_text(area)).empty());
        lv_textarea_set_text(area, "Discard-Example1!");
        lv_obj_send_event(keyboard, LV_EVENT_CANCEL, nullptr);
        CHECK(view.take_event()->action == TouchAction::Cancel);
        CHECK(std::string(lv_textarea_get_text(area)).empty());
        view.input("Recovery word 1/24", "Enter the word from your backup", false, true);
        area = find(view.root(), &lv_textarea_class);
        CHECK(!lv_textarea_get_password_mode(area));
        CHECK(std::string(lv_textarea_get_accepted_chars(area)) == "abcdefghijklmnopqrstuvwxyz");
        CHECK(lv_textarea_get_max_length(area) == 16);
        std::string details =
            "TEST TRANSACTION\nAmount: 1 TEST\nRecipient:\nsynthetic-test-address\n";
        for (int i = 0; i < 20; ++i)
            details += "Additional test detail\n";
        view.text("Review transaction", details.c_str(), "Confirm", true);
        auto* footer = lv_obj_get_child(view.root(), -1);
        auto* confirm = lv_obj_get_child(footer, 1);
        CHECK(lv_obj_has_state(confirm, LV_STATE_DISABLED));
        lv_obj_send_event(confirm, LV_EVENT_CLICKED, nullptr);
        CHECK(!view.take_event());
        auto* scroller = lv_obj_get_child(view.root(), 1);
        lv_obj_scroll_to_y(scroller, 10000, LV_ANIM_OFF);
        CHECK(!lv_obj_has_state(confirm, LV_STATE_DISABLED));
        lv_obj_send_event(confirm, LV_EVENT_CLICKED, nullptr);
        CHECK(view.take_event()->action == TouchAction::Confirm);
        // A freshly rendered request cannot inherit approval of the previous one.
        view.text("Review transaction", details.c_str(), "Confirm", true);
        footer = lv_obj_get_child(view.root(), -1);
        confirm = lv_obj_get_child(footer, 1);
        CHECK(lv_obj_has_state(confirm, LV_STATE_DISABLED));
        view.text("Pairing request", "Confirm that both devices show:\n\n123456", "Pair");
        snapshot(view.root(), "touch-pairing.ppm");

        // Exercise the real LVGL input pipeline, not manually sent click events.
        // A failed read or a second finger must not release-click Confirm.
        view.menu("Touch input", {"Confirm"});
        lv_obj_update_layout(view.root());
        first = find(view.root(), &lv_button_class);
        lv_area_t bounds{};
        lv_obj_get_coords(first, &bounds);
        const auto x = static_cast<std::uint16_t>((bounds.x1 + bounds.x2) / 2);
        const auto y = static_cast<std::uint16_t>((bounds.y1 + bounds.y2) / 2);
        struct Reader {
            Ft6336Decoder decoder;
            std::array<std::uint8_t, 5> report{};
            bool ok = true;
        } reader;
        auto* pointer = lv_indev_create();
        lv_indev_set_type(pointer, LV_INDEV_TYPE_POINTER);
        lv_indev_set_display(pointer, display);
        lv_indev_set_user_data(pointer, &reader);
        lv_indev_set_read_cb(pointer, [](lv_indev_t* input, lv_indev_data_t* data) {
            auto* state = static_cast<Reader*>(lv_indev_get_user_data(input));
            apply_touch_sample(input, data, state->decoder.read(state->report, state->ok));
        });
        auto feed = [&](int count, bool ok = true) {
            const auto panel_x = static_cast<std::uint16_t>(es3c28p::touch_width - 1 - y);
            const auto panel_y = x;
            reader.report = {
                static_cast<std::uint8_t>(count), static_cast<std::uint8_t>(panel_x >> 8),
                static_cast<std::uint8_t>(panel_x), static_cast<std::uint8_t>(panel_y >> 8),
                static_cast<std::uint8_t>(panel_y)};
            reader.ok = ok;
            lv_tick_inc(16);
            lv_indev_read(pointer);
        };
        feed(1);
        feed(0); // held at boot
        CHECK(!view.take_event());
        feed(1);
        feed(1, false);
        feed(1);
        feed(0);
        CHECK(!view.take_event());
        feed(1);
        feed(2);
        feed(1);
        feed(0);
        CHECK(!view.take_event());
        feed(1);
        feed(0); // clean single-finger tap
        select = view.take_event();
        CHECK(select && select->action == TouchAction::Select && select->selection == 0);
        CHECK(!view.take_event());
        lv_indev_delete(pointer);

        view.splash("Sua carteira offline");
        snapshot(view.root(), "touch-landscape-splash.ppm");
        view.public_address("Bitcoin", "bc1qu5v26zawx66mlkrhr48cmlnmxxwra07sefn73g", "BTC");
        lv_obj_update_layout(view.root());
        auto* qr = find(view.root(), &lv_qrcode_class);
        auto* back = find(view.root(), &lv_button_class);
        CHECK(qr && back);
        lv_area_t qr_bounds{}, back_bounds{};
        lv_obj_get_coords(qr, &qr_bounds);
        lv_obj_get_coords(back, &back_bounds);
        CHECK(qr_bounds.y2 < back_bounds.y1);
        CHECK(qr_bounds.x2 < es3c28p::width);
        snapshot(view.root(), "touch-landscape-address.ppm");

        view.busy("Configurando carteira", "Aguarde um instante...");
        lv_obj_update_layout(view.root());
        CHECK(lv_obj_get_child_count(view.root()) == 5);
        auto* center_dot = lv_obj_get_child(view.root(), 3);
        CHECK(lv_obj_get_x(center_dot) == es3c28p::width / 2 - 4 &&
              lv_obj_get_y(center_dot) == es3c28p::height / 2 - 4);
        snapshot(view.root(), "touch-wallet-busy.ppm");
    }
    lv_display_delete(display);
    lv_deinit();
    std::cout << checks << " LVGL checks passed at 320x240 (host, no physical touch driver).\n";
}
