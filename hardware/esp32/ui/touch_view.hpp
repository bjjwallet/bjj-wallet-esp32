// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/secret.hpp"
#include "ui/language.hpp"
#include "lvgl.h"
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace airlock {
enum class TouchAction { Select, Submit, Confirm, Cancel, InputTooLong, Back };
struct TouchEvent {
    TouchAction action;
    std::uint64_t screen;
    std::size_t selection = 0;
    Credential value;
};

// All calls and event polling belong to the LVGL/UI task. The application must
// consume events outside lv_timer_handler(), then update its state and repaint.
// This view does not initialize GPIOs or take ownership of an LCD/touch driver.
class TouchView {
  public:
    explicit TouchView(lv_obj_t* parent);
    ~TouchView();
    TouchView(const TouchView&) = delete;
    TouchView& operator=(const TouchView&) = delete;
    void menu(const char* title, const std::vector<std::string>& items, bool back = false,
              const char* coin = nullptr, const std::vector<std::string>& icons = {});
    void input(const char* title, const char* hint, bool password, bool mnemonic_word = false,
               bool digits = false, const char* coin = nullptr);
    void public_address(const char* coin, const char* address, const char* icon_name = nullptr);
    void splash(const char* notice = nullptr);
    void checkbox(const char* title, const char* description, const char* caption, bool checked);
    void directional_back(bool enabled) {
        directional_back_ = enabled;
    }
    void text(const char* title, const char* content, const char* confirm_label,
              bool require_full_review = false, bool cancellable = true,
              const char* cancel_label = nullptr, const char* coin = nullptr);
    void approval(const char* message, const char* details, const char* accept, const char* reject,
                  const char* coin = nullptr, bool full_review = false);
    void show_progress(const char* message);
    void hide_progress();
    void status(const char* title, const char* content, bool cancellable,
                const char* coin = nullptr);
    void busy(const char* title, const char* content);
    void language(Language language);
    std::optional<TouchEvent> take_event();
    std::uint64_t screen_id() const {
        return screen_id_;
    }
    lv_obj_t* root() const {
        return root_;
    }

  private:
    // Implementation is linked only by the board-validation serial adapter/tests.
    friend class BoardSerialControl;
    struct Button {
        TouchView* view;
        TouchAction action;
        std::size_t selection;
        lv_obj_t* object;
    };
    lv_obj_t* root_ = nullptr;
    lv_obj_t* textarea_ = nullptr;
    lv_obj_t* keyboard_ = nullptr;
    lv_obj_t* confirm_ = nullptr;
    lv_obj_t* scroll_ = nullptr;
    lv_obj_t* progress_ = nullptr;
    std::vector<std::unique_ptr<Button>> buttons_;
    std::optional<TouchEvent> event_;
    std::uint64_t screen_id_ = 0;
    bool review_required_ = false;
    bool rebuilding_ = false;
    bool directional_back_ = false;
    Language language_ = Language::English;
    int content_top_ = 40;
    void clear();
    void title(const char* value, const char* coin = nullptr);
    void emit(TouchAction action, std::size_t selection = 0);
    void update_review();
    lv_obj_t* button(lv_obj_t* parent, const char* text, TouchAction action,
                     std::size_t selection = 0);
    static void on_button(lv_event_t* event);
    static void on_keyboard(lv_event_t* event);
    static void on_scroll(lv_event_t* event);
};
} // namespace airlock
