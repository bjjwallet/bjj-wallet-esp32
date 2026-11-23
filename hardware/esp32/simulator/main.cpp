// SPDX-License-Identifier: GPL-3.0-or-later
#include "hardware/es3c28p.hpp"
#include "ui/wallet_app.hpp"
#include "simulator/native_wallet.hpp"
#include "ui/touch_input.hpp"
#include "core/rules.hpp"
#include "core/config.hpp"
#include <SDL.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <string_view>
#include <cstdlib>
#include <unistd.h>

namespace {
using namespace airlock;
constexpr int width = es3c28p::width, height = es3c28p::height;
lv_obj_t* find(lv_obj_t* root, const lv_obj_class_t* type) {
    if (lv_obj_check_type(root, type))
        return root;
    for (std::uint32_t i = 0; i < lv_obj_get_child_count(root); ++i) {
        if (auto* object = find(lv_obj_get_child(root, i), type))
            return object;
    }
    return nullptr;
}

struct Surface {
    SDL_Window* window = nullptr;
    SDL_Renderer* renderer = nullptr;
    SDL_Texture* texture = nullptr;
    std::array<std::uint16_t, width * height> frame{};
    std::array<std::uint16_t, width * 20> draw{};
    std::array<std::uint8_t, 5> report{};
    Ft6336Decoder decoder;
    bool input_ok = true;
    bool pressed = false;
};

void flush(lv_display_t* display, const lv_area_t* area, std::uint8_t* data) {
    auto& s = *static_cast<Surface*>(lv_display_get_user_data(display));
    const int row_width = area->x2 - area->x1 + 1;
    for (int y = area->y1; y <= area->y2; ++y) {
        std::memcpy(s.frame.data() + y * width + area->x1, data, row_width * sizeof(std::uint16_t));
        data += row_width * sizeof(std::uint16_t);
    }
    if (lv_display_flush_is_last(display)) {
        SDL_UpdateTexture(s.texture, nullptr, s.frame.data(), width * sizeof(std::uint16_t));
        SDL_RenderClear(s.renderer);
        SDL_RenderCopy(s.renderer, s.texture, nullptr, nullptr);
        SDL_RenderPresent(s.renderer);
    }
    lv_display_flush_ready(display);
}

void pointer(lv_indev_t* input, lv_indev_data_t* data) {
    auto& s = *static_cast<Surface*>(lv_indev_get_user_data(input));
    apply_touch_sample(input, data, s.decoder.read(s.report, s.input_ok));
}

void mouse(Surface& s, lv_indev_t* input, int raw_x, int raw_y) {
    int window_width = 0, window_height = 0;
    SDL_GetWindowSize(s.window, &window_width, &window_height);
    const int x = raw_x * width / std::max(1, window_width);
    const int y = raw_y * height / std::max(1, window_height);
    s.input_ok = raw_x >= 0 && raw_x < window_width && raw_y >= 0 && raw_y < window_height;
    const int panel_x = es3c28p::touch_width - 1 - y;
    const int panel_y = x;
    s.report = {
        static_cast<std::uint8_t>(s.pressed ? 1 : 0),
        static_cast<std::uint8_t>((panel_x >> 8) & 0x0f), static_cast<std::uint8_t>(panel_x),
        static_cast<std::uint8_t>((panel_y >> 8) & 0x0f), static_cast<std::uint8_t>(panel_y)};
    // Process every edge, including fast clicks queued within one render frame.
    lv_indev_read(input);
}

bool handle_event(Surface& surface, lv_indev_t* input, WalletApp& preview, SDL_Event& event) {
    if (event.type == SDL_QUIT)
        return false;
    if (event.type == SDL_MOUSEBUTTONDOWN || event.type == SDL_MOUSEBUTTONUP) {
        if (event.button.button == SDL_BUTTON_LEFT) {
            surface.pressed = event.type == SDL_MOUSEBUTTONDOWN;
            mouse(surface, input, event.button.x, event.button.y);
        }
    } else if (event.type == SDL_MOUSEMOTION) {
        mouse(surface, input, event.motion.x, event.motion.y);
    } else if (event.type == SDL_WINDOWEVENT && (event.window.event == SDL_WINDOWEVENT_FOCUS_LOST ||
                                                 event.window.event == SDL_WINDOWEVENT_LEAVE)) {
        surface.pressed = false;
        surface.input_ok = false;
        lv_indev_read(input); // Cancel; never synthesize a confirmation on focus loss.
        surface.report = {};
        surface.input_ok = true;
        lv_indev_read(input);
    } else if (event.type == SDL_TEXTINPUT) {
        if (auto* area = find(preview.view().root(), &lv_textarea_class))
            lv_textarea_add_text(area, event.text.text);
        wipe(event.text.text, sizeof(event.text.text));
    } else if (event.type == SDL_KEYDOWN) {
        auto* keyboard = find(preview.view().root(), &lv_keyboard_class);
        auto* area = find(preview.view().root(), &lv_textarea_class);
        if (event.key.keysym.sym == SDLK_ESCAPE)
            preview.cancel();
        else if (event.key.keysym.sym == SDLK_BACKSPACE && area)
            lv_textarea_delete_char(area);
        else if (event.key.keysym.sym == SDLK_RETURN && keyboard)
            lv_obj_send_event(keyboard, LV_EVENT_READY, nullptr);
    }
    preview.consume();
    return true;
}

bool pump(Surface& surface, lv_indev_t* input, WalletApp& preview) {
    SDL_Event event{};
    while (SDL_PollEvent(&event)) {
        if (!handle_event(surface, input, preview, event))
            return false;
    }
    lv_timer_handler();
    preview.consume();
    return true;
}

bool snapshot(const Surface& surface) {
    std::ofstream file("simulator-preview.ppm", std::ios::binary);
    file << "P6\n" << width << ' ' << height << "\n255\n";
    for (auto pixel : surface.frame) {
        const char rgb[] = {static_cast<char>(((pixel >> 11) & 31) * 255 / 31),
                            static_cast<char>(((pixel >> 5) & 63) * 255 / 63),
                            static_cast<char>((pixel & 31) * 255 / 31)};
        file.write(rgb, 3);
    }
    return file.good();
}

bool smoke(Surface& s, lv_display_t* display, lv_indev_t* input, WalletApp& app) {
    // Exercise actual menu -> New Wallet -> introduction -> masked input.
    auto* list = lv_obj_get_child(app.view().root(), 1);
    auto* button = lv_obj_get_child(list, 2);
    lv_obj_scroll_to_view(button, LV_ANIM_OFF);
    lv_refr_now(display);
    lv_area_t bounds{};
    lv_obj_get_coords(button, &bounds);
    SDL_Event event{};
    event.type = SDL_MOUSEBUTTONDOWN;
    event.button.button = SDL_BUTTON_LEFT;
    event.button.state = SDL_PRESSED;
    event.button.windowID = SDL_GetWindowID(s.window);
    event.button.x = bounds.x1 + bounds.x2;
    event.button.y = bounds.y1 + bounds.y2;
    if (SDL_PushEvent(&event) != 1)
        return false;
    event.type = SDL_MOUSEBUTTONUP;
    event.button.state = SDL_RELEASED;
    if (SDL_PushEvent(&event) != 1 || !pump(s, input, app) || app.page() != WalletPage::Intro)
        return false;
    auto* footer = lv_obj_get_child(app.view().root(), -1);
    lv_obj_send_event(lv_obj_get_child(footer, 1), LV_EVENT_CLICKED, nullptr);
    app.consume();
    if (app.page() != WalletPage::Input || app.stage() != WalletStage::Passphrase)
        return false;
    event = {};
    event.type = SDL_TEXTINPUT;
    std::strcpy(event.text.text, "Public-Passphrase1!");
    // SDL2-compat cannot enqueue synthetic SDL_TEXTINPUT; use the real dispatcher.
    if (!handle_event(s, input, app, event) || !pump(s, input, app))
        return false;
    lv_refr_now(display);
    auto* area = find(app.view().root(), &lv_textarea_class);
    if (!area || lv_textarea_get_password_mode(area) ||
        std::strcmp(lv_textarea_get_text(area), "Public-Passphrase1!") != 0 || !snapshot(s))
        return false;
    event = {};
    event.type = SDL_KEYDOWN;
    event.key.state = SDL_PRESSED;
    event.key.windowID = SDL_GetWindowID(s.window);
    event.key.keysym.sym = SDLK_RETURN;
    if (SDL_PushEvent(&event) != 1 || !pump(s, input, app) ||
        app.stage() != WalletStage::ConfirmPassphrase)
        return false;
    app.cancel();
    lv_refr_now(display);
    std::cout << "SDL smoke passed: original menu, create flow, visible input, confirmation and "
                 "cancellation.\n";
    return app.page() == WalletPage::Menu;
}
} // namespace

int main(int argc, char** argv) {
    const bool smoke_test = argc == 2 && std::string_view(argv[1]) == "--smoke-test";
    if (argc > 1 && !smoke_test) {
        std::cerr << "Usage: airlock_simulator [--smoke-test]\n";
        return 2;
    }
    std::filesystem::path wallet_directory = AIRLOCK_SIMULATOR_DATA_DIR;
    char temporary[] = "/tmp/bjj-airlock-smoke-XXXXXX";
    if (smoke_test) {
        if (!mkdtemp(temporary))
            return 1;
        wallet_directory = std::filesystem::path(temporary) / "wallet";
    }
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_TIMER) != 0) {
        std::cerr << "SDL initialization failed: " << SDL_GetError() << '\n';
        return 1;
    }
    Surface surface;
    const auto window_title = std::string(config::product_name) + " | Native wallet - macOS";
    surface.window = SDL_CreateWindow(window_title.c_str(), SDL_WINDOWPOS_CENTERED,
                                      SDL_WINDOWPOS_CENTERED, width * 2, height * 2,
                                      smoke_test ? SDL_WINDOW_HIDDEN : SDL_WINDOW_ALLOW_HIGHDPI);
    surface.renderer =
        surface.window ? SDL_CreateRenderer(surface.window, -1, SDL_RENDERER_SOFTWARE) : nullptr;
    surface.texture = surface.renderer
                          ? SDL_CreateTexture(surface.renderer, SDL_PIXELFORMAT_RGB565,
                                              SDL_TEXTUREACCESS_STREAMING, width, height)
                          : nullptr;
    if (!surface.texture) {
        std::cerr << "SDL window setup failed: " << SDL_GetError() << '\n';
        SDL_DestroyRenderer(surface.renderer);
        SDL_DestroyWindow(surface.window);
        SDL_Quit();
        return 1;
    }
    lv_init();
    lv_tick_set_cb(SDL_GetTicks);
    auto* display = lv_display_create(width, height);
    lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
    lv_display_set_user_data(display, &surface);
    lv_display_set_buffers(display, surface.draw.data(), nullptr, sizeof(surface.draw),
                           LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display, flush);
    auto* input = lv_indev_create();
    lv_indev_set_type(input, LV_INDEV_TYPE_POINTER);
    lv_indev_set_display(input, display);
    lv_indev_set_user_data(input, &surface);
    lv_indev_set_read_cb(input, pointer);
    surface.decoder.read({});
    SDL_StartTextInput();
    bool success = true;
    {
        NativeWallet service(wallet_directory);
        WalletApp preview(lv_display_get_screen_active(display), service);
        auto selected_language = Language::PortugueseBrazil;
        const auto saved_language = service.language();
        for (std::size_t i = 0; i < language_codes.size(); ++i) {
            if (saved_language == language_codes[i])
                selected_language = static_cast<Language>(i);
        }
        bool language_persisted = parse_language(saved_language).has_value();
        if (!smoke_test) {
            preview.set_language(selected_language);
            if (!language_persisted)
                preview.choose_language(true);
        }
        if (smoke_test)
            success = smoke(surface, display, input, preview);
        else {
            std::cout << config::product_name << " - native Trust Wallet Core 4.4.2.\n"
                      << "Encrypted wallet directory: " << wallet_directory << "\n"
                      << "Mouse = touch; drag = scroll; Enter = submit; Esc = cancel.\n"
                      << std::flush;
            while (pump(surface, input, preview)) {
                if (preview.take_action() == AppAction::Shutdown)
                    break;
                if (!preview.needs_language_selection() &&
                    (!language_persisted || preview.language() != selected_language)) {
                    selected_language = preview.language();
                    language_persisted = service.save_language(
                        language_codes[static_cast<std::size_t>(selected_language)]);
                    if (!language_persisted)
                        std::cerr << "Could not persist the language setting.\n";
                }
                SDL_Delay(5);
            }
        }
    }
    SDL_StopTextInput();
    lv_indev_delete(input);
    lv_display_delete(display);
    lv_deinit();
    wipe(surface.frame.data(), sizeof(surface.frame));
    wipe(surface.draw.data(), sizeof(surface.draw));
    SDL_DestroyTexture(surface.texture);
    SDL_DestroyRenderer(surface.renderer);
    SDL_DestroyWindow(surface.window);
    SDL_Quit();
    if (smoke_test)
        std::filesystem::remove_all(temporary);
    if (!success)
        std::cerr << "SDL smoke failed\n";
    return success ? 0 : 1;
}
