// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/touch_view.hpp"
#include <algorithm>
#include <utility>
#include <cstring>
#include "original_strings.hpp"
#include "setup_strings.hpp"
#include "ui/assets/splash_image.h"
#include "ui/assets/currency_icons/coin_icons.hpp"
#include "ui/assets/feather/approval_icons.hpp"

extern "C" {
LV_FONT_DECLARE(airlock_chinese_16);
}

namespace airlock {
namespace {
const lv_font_t latin_font = [] {
    auto font = lv_font_montserrat_14;
    font.fallback = &airlock_chinese_16;
    return font;
}();
const lv_font_t title_font = [] {
    auto font = lv_font_montserrat_18;
    font.fallback = &airlock_chinese_16;
    return font;
}();
lv_obj_t* label(lv_obj_t* parent, const char* text) {
    auto* result = lv_label_create(parent);
    lv_label_set_text(result, text);
    lv_label_set_long_mode(result, LV_LABEL_LONG_WRAP);
    lv_obj_set_width(result, lv_pct(100));
    return result;
}
void container(lv_obj_t* object) {
    lv_obj_set_style_border_width(object, 0, 0);
    lv_obj_set_style_radius(object, 0, 0);
    lv_obj_set_style_pad_all(object, 8, 0);
    lv_obj_set_style_pad_row(object, 8, 0);
    lv_obj_set_style_bg_color(object, lv_color_hex(0x111b27), 0);
    lv_obj_set_style_text_color(object, lv_color_hex(0xf3f6fb), 0);
}
void progress_dots(lv_obj_t* parent, int vertical_offset) {
    for (int i = 0; i < 3; ++i) {
        auto* dot = lv_obj_create(parent);
        lv_obj_remove_style_all(dot);
        lv_obj_set_size(dot, 8, 8);
        lv_obj_set_style_bg_color(dot, lv_color_hex(0x78d9ed), 0);
        lv_obj_set_style_bg_opa(dot, LV_OPA_30, 0);
        lv_obj_align(dot, LV_ALIGN_CENTER, (i - 1) * 18, vertical_offset);
        lv_anim_t animation;
        lv_anim_init(&animation);
        lv_anim_set_var(&animation, dot);
        lv_anim_set_exec_cb(&animation, [](void* object, int32_t opacity) {
            lv_obj_set_style_bg_opa(static_cast<lv_obj_t*>(object), opacity, 0);
        });
        lv_anim_set_values(&animation, LV_OPA_30, LV_OPA_COVER);
        lv_anim_set_duration(&animation, 320);
        lv_anim_set_playback_duration(&animation, 320);
        lv_anim_set_delay(&animation, i * 120);
        lv_anim_set_repeat_count(&animation, LV_ANIM_REPEAT_INFINITE);
        lv_anim_start(&animation);
    }
}
} // namespace
TouchView::TouchView(lv_obj_t* parent) {
    root_ = lv_obj_create(parent);
    lv_obj_set_size(root_, lv_pct(100), lv_pct(100));
    lv_obj_set_style_pad_all(root_, 0, 0);
    lv_obj_set_style_border_width(root_, 0, 0);
    lv_obj_set_style_radius(root_, 0, 0);
    lv_obj_remove_flag(root_, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_bg_color(root_, lv_color_hex(0x111b27), 0);
    lv_obj_set_style_text_color(root_, lv_color_hex(0xf3f6fb), 0);
    lv_obj_set_style_text_font(root_, &latin_font, 0);
    lv_obj_update_layout(root_);
}
TouchView::~TouchView() {
    clear();
    lv_obj_delete(root_);
}
void TouchView::clear() {
    rebuilding_ = true;
    event_.reset();
    // Custom allocator wipes all LVGL-owned text, including old textarea copies.
    if (keyboard_)
        lv_keyboard_set_textarea(keyboard_, nullptr);
    if (textarea_)
        lv_textarea_set_text(textarea_, "");
    lv_obj_clean(root_);
    progress_ = nullptr;
    buttons_.clear();
    textarea_ = keyboard_ = confirm_ = scroll_ = nullptr;
    review_required_ = false;
    ++screen_id_;
    rebuilding_ = false;
}
void TouchView::title(const char* value, const char* coin) {
    auto* heading = label(root_, value);
    lv_obj_set_style_text_font(heading, uses_cjk(language_) ? &airlock_chinese_16 : &title_font, 0);
    const auto* icon = coin ? coin_icon(coin) : nullptr;
    if (icon) {
        auto* image = lv_image_create(root_);
        lv_image_set_src(image, icon);
        lv_obj_set_pos(image, 8, 8);
        const auto text_x = 16 + icon->header.w;
        lv_obj_set_pos(heading, text_x, 8);
        lv_obj_set_width(heading, lv_obj_get_width(root_) - text_x - 8);
    } else {
        lv_obj_set_pos(heading, 8, 8);
        lv_obj_set_width(heading, lv_pct(94));
    }
    lv_obj_update_layout(heading);
    const auto text_height = lv_obj_get_height(heading);
    const auto row_height = icon ? std::max<int>(text_height, icon->header.h) : text_height;
    if (icon)
        lv_obj_set_y(heading, 8 + (row_height - text_height) / 2);
    content_top_ = 8 + row_height + 8;
}
lv_obj_t* TouchView::button(lv_obj_t* parent, const char* text, TouchAction action,
                            std::size_t selection) {
    auto* object = lv_button_create(parent);
    lv_obj_set_size(object, lv_pct(100), 44);
    lv_obj_set_style_radius(object, 0, 0);
    lv_obj_set_style_shadow_width(object, 0, 0);
    lv_obj_set_style_pad_all(object, 4, 0);
    lv_obj_set_style_bg_color(object, lv_color_hex(0x245d75), 0);
    auto* caption = label(object, text);
    lv_obj_set_style_text_align(caption, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_center(caption);
    auto metadata = std::make_unique<Button>(Button{this, action, selection, object});
    lv_obj_add_event_cb(object, on_button, LV_EVENT_CLICKED, metadata.get());
    buttons_.push_back(std::move(metadata));
    return object;
}
void TouchView::menu(const char* heading, const std::vector<std::string>& items, bool back,
                     const char* coin, const std::vector<std::string>& icons) {
    clear();
    title(heading, coin);
    auto* list = lv_obj_create(root_);
    scroll_ = list;
    container(list);
    lv_obj_set_pos(list, 0, content_top_);
    lv_obj_set_size(list, lv_pct(100), lv_obj_get_height(root_) - content_top_ - (back ? 60 : 0));
    lv_obj_set_flex_flow(list, LV_FLEX_FLOW_COLUMN);
    for (std::size_t i = 0; i < items.size(); ++i) {
        auto* row = button(list, items[i].c_str(), TouchAction::Select, i);
        if (i >= icons.size())
            continue;
        const auto* icon = coin_icon(icons[i].c_str());
        if (!icon)
            continue;
        auto* image = lv_image_create(row);
        lv_image_set_src(image, icon);
        lv_obj_align(image, LV_ALIGN_LEFT_MID, 4, 0);
        auto* caption = lv_obj_get_child(row, 0);
        lv_obj_set_width(caption, lv_pct(75));
        lv_label_set_long_mode(caption, LV_LABEL_LONG_DOT);
        lv_obj_set_style_text_align(caption, LV_TEXT_ALIGN_LEFT, 0);
        lv_obj_align(caption, LV_ALIGN_LEFT_MID, 44, 0);
    }
    if (back) {
        auto* cancel = button(root_, locale_text(Text::voltar_067, language_), TouchAction::Cancel);
        lv_obj_set_size(cancel, lv_pct(94), 44);
        lv_obj_align(cancel, LV_ALIGN_BOTTOM_MID, 0, -8);
    }
}
void TouchView::public_address(const char* coin, const char* address, const char* icon_name) {
    clear();
    title(coin);
    if (const auto* icon = coin_icon(icon_name ? icon_name : coin)) {
        auto* image = lv_image_create(root_);
        lv_image_set_src(image, icon);
        lv_obj_set_pos(image, 8, 4);
        auto* heading = lv_obj_get_child(root_, 0);
        lv_obj_set_x(heading, 48);
        lv_obj_set_width(heading, lv_obj_get_width(root_) - 56);
    } else {
        auto* badge = lv_label_create(root_);
        char initial[2]{coin[0], 0};
        lv_label_set_text(badge, initial);
        lv_obj_set_size(badge, 32, 32);
        lv_obj_set_pos(badge, 8, 4);
        lv_obj_set_style_radius(badge, 16, 0);
        lv_obj_set_style_bg_opa(badge, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(badge, lv_color_hex(0x245d75), 0);
        lv_obj_set_style_text_align(badge, LV_TEXT_ALIGN_CENTER, 0);
        lv_obj_set_style_pad_top(badge, 7, 0);
        auto* heading = lv_obj_get_child(root_, 0);
        lv_obj_set_x(heading, 48);
        lv_obj_set_width(heading, lv_obj_get_width(root_) - 56);
    }
    auto* qr = lv_qrcode_create(root_);
    const bool landscape = lv_obj_get_width(root_) > lv_obj_get_height(root_);
    const int qr_size =
        landscape ? std::min<int>(156, lv_obj_get_height(root_) - content_top_ - 68) : 156;
    lv_qrcode_set_size(qr, qr_size);
    lv_qrcode_set_dark_color(qr, lv_color_black());
    lv_qrcode_set_light_color(qr, lv_color_white());
    lv_obj_set_style_border_color(qr, lv_color_white(), 0);
    lv_obj_set_style_border_width(qr, 8, 0);
    if (landscape)
        lv_obj_set_pos(qr, 8, content_top_);
    else
        lv_obj_align(qr, LV_ALIGN_TOP_MID, 0, 40);
    lv_obj_update_layout(qr);
    const int address_top =
        lv_obj_get_y(qr) + lv_obj_get_height(qr) + lv_font_get_line_height(&latin_font);
    if (lv_qrcode_update(qr, address, std::strlen(address)) != LV_RESULT_OK) {
        lv_obj_delete(qr); // Never display a stale/unrelated QR on allocation failure.
    }
    auto* value = label(root_, address);
    lv_obj_set_width(value, landscape ? lv_obj_get_width(root_) - qr_size - 40
                                      : lv_obj_get_width(root_) - 16);
    lv_obj_set_style_text_align(value, landscape ? LV_TEXT_ALIGN_LEFT : LV_TEXT_ALIGN_CENTER, 0);
    // Include the white border, then leave one complete blank text line.
    lv_obj_set_style_text_font(value, &latin_font, 0);
    lv_obj_set_pos(value, landscape ? qr_size + 32 : 8, landscape ? content_top_ + 8 : address_top);
    auto* back = button(root_, locale_text(Text::voltar_067, language_), TouchAction::Cancel);
    lv_obj_set_size(back, lv_pct(94), 36);
    lv_obj_align(back, LV_ALIGN_BOTTOM_MID, 0, -4);
}
void TouchView::input(const char* heading, const char* hint, bool hidden, bool word, bool digits,
                      const char* coin) {
    clear();
    title(heading, coin);
    auto* help = label(root_, hint);
    lv_obj_set_pos(help, 8, content_top_);
    lv_obj_set_width(help, lv_pct(94));
    lv_obj_update_layout(help);
    const bool landscape = lv_obj_get_width(root_) > lv_obj_get_height(root_);
    if (landscape) {
        lv_obj_set_height(help, 16);
        lv_label_set_long_mode(help, LV_LABEL_LONG_SCROLL_CIRCULAR);
    }
    const int input_top = content_top_ + lv_obj_get_height(help) + 6;
    textarea_ = lv_textarea_create(root_);
    lv_obj_set_pos(textarea_, 8, input_top);
    lv_obj_set_size(textarea_, lv_pct(94), landscape ? 36 : 44);
    lv_obj_set_style_radius(textarea_, 0, 0);
    lv_obj_set_style_shadow_width(textarea_, 0, 0);
    lv_textarea_set_one_line(textarea_, true);
    lv_textarea_set_password_mode(textarea_, hidden);
    lv_textarea_set_password_show_time(textarea_, 0);
    lv_textarea_set_max_length(textarea_, word ? 16 : digits ? 12 : 1024);
    if (word)
        lv_textarea_set_accepted_chars(textarea_, "abcdefghijklmnopqrstuvwxyz");
    if (digits)
        lv_textarea_set_accepted_chars(textarea_, "0123456789");
    keyboard_ = lv_keyboard_create(root_);
    lv_obj_set_style_radius(keyboard_, 0, LV_PART_ITEMS);
    lv_obj_set_style_shadow_width(keyboard_, 0, LV_PART_ITEMS);
    lv_obj_set_size(keyboard_, lv_pct(100),
                    lv_obj_get_height(root_) - input_top - (landscape ? 42 : 50));
    lv_obj_align(keyboard_, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_keyboard_set_textarea(keyboard_, textarea_);
    if (digits)
        lv_keyboard_set_mode(keyboard_, LV_KEYBOARD_MODE_NUMBER);
    lv_keyboard_set_popovers(keyboard_, false);
    lv_obj_add_event_cb(keyboard_, on_keyboard, LV_EVENT_READY, this);
    lv_obj_add_event_cb(keyboard_, on_keyboard, LV_EVENT_CANCEL, this);
}
void TouchView::splash(const char* notice) {
    clear();
    lv_obj_set_style_bg_color(root_, lv_color_hex(0x000000), 0);
    auto* image = lv_image_create(root_);
    lv_image_set_src(image, &airlock_splash_image);
    const bool landscape = lv_obj_get_width(root_) > lv_obj_get_height(root_);
    if (landscape) {
        lv_obj_set_size(image, 150, 150);
        lv_image_set_inner_align(image, LV_IMAGE_ALIGN_STRETCH);
        lv_obj_align(image, LV_ALIGN_LEFT_MID, 8, 0);
    } else
        lv_obj_align(image, LV_ALIGN_TOP_MID, 0, 12);
    auto* brand = label(root_, locale_text(Text::brand, language_));
    lv_obj_set_style_text_font(brand, &title_font, 0);
    lv_obj_set_style_text_align(brand, LV_TEXT_ALIGN_CENTER, 0);
    if (landscape) {
        lv_obj_set_width(brand, lv_obj_get_width(root_) - 176);
        lv_obj_set_pos(brand, 168, 58);
    } else
        lv_obj_align(brand, LV_ALIGN_TOP_MID, 0, 252);
    auto* tagline = label(root_, notice ? notice : locale_text(Text::splash_tagline, language_));
    lv_obj_set_style_text_align(tagline, LV_TEXT_ALIGN_CENTER, 0);
    if (landscape) {
        lv_obj_set_width(tagline, lv_obj_get_width(root_) - 176);
        lv_obj_set_pos(tagline, 168, 112);
    } else
        lv_obj_align(tagline, LV_ALIGN_TOP_MID, 0, 282);
}
void TouchView::checkbox(const char* heading, const char* description, const char* caption,
                         bool checked) {
    text(heading, description, locale_text(Text::continue_, language_), false, true,
         locale_text(Text::voltar_067, language_));
    // Place the checkbox before the explanatory text inside the scroll content.
    auto* option = lv_checkbox_create(scroll_);
    lv_obj_remove_style_all(option);
    lv_obj_set_style_pad_column(option, 8, 0);
    lv_checkbox_set_text(option, caption);
    lv_obj_set_width(option, lv_pct(100));
    lv_obj_set_style_border_width(option, 2, LV_PART_INDICATOR);
    lv_obj_set_style_border_opa(option, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_border_side(option, LV_BORDER_SIDE_FULL, LV_PART_INDICATOR);
    lv_obj_set_style_opa(option, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_border_color(option, lv_color_hex(0xf3f6fb), LV_PART_INDICATOR);
    lv_obj_set_style_radius(option, 2, LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(option, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(option, lv_color_hex(0x111b27), LV_PART_INDICATOR);
    const auto selected = static_cast<lv_style_selector_t>(LV_PART_INDICATOR) | LV_STATE_CHECKED;
    lv_obj_set_style_bg_color(option, lv_color_hex(0x245d75), selected);
    lv_obj_set_style_bg_image_src(option, LV_SYMBOL_OK, selected);
    lv_obj_set_style_bg_image_opa(option, LV_OPA_COVER, selected);
    lv_obj_set_style_bg_image_recolor(option, lv_color_hex(0xffffff), selected);
    lv_obj_set_style_bg_image_recolor_opa(option, LV_OPA_COVER, selected);
    lv_obj_move_to_index(option, 0);
    if (checked)
        lv_obj_add_state(option, LV_STATE_CHECKED);
    auto metadata = std::make_unique<Button>(Button{this, TouchAction::Select, 0, option});
    lv_obj_add_event_cb(option, on_button, LV_EVENT_CLICKED, metadata.get());
    buttons_.push_back(std::move(metadata));
    lv_obj_update_layout(root_);
}
void TouchView::text(const char* heading, const char* content, const char* confirm_label,
                     bool full_review, bool cancellable, const char* cancel_label,
                     const char* coin) {
    clear();
    title(heading, coin);
    review_required_ = full_review;
    scroll_ = lv_obj_create(root_);
    container(scroll_);
    lv_obj_set_pos(scroll_, 0, content_top_);
    lv_obj_set_size(scroll_, lv_pct(100), lv_obj_get_height(root_) - content_top_ - 60);
    lv_obj_set_flex_flow(scroll_, LV_FLEX_FLOW_COLUMN);
    label(scroll_, content);
    lv_obj_add_event_cb(scroll_, on_scroll, LV_EVENT_SCROLL, this);
    auto* footer = lv_obj_create(root_);
    container(footer);
    lv_obj_set_size(footer, lv_pct(100), 60);
    lv_obj_align(footer, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_obj_remove_flag(footer, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_flex_flow(footer, LV_FLEX_FLOW_ROW);
    auto* cancel =
        button(footer, cancel_label ? cancel_label : locale_text(Text::cancelar_058, language_),
               TouchAction::Cancel);
    lv_obj_set_width(cancel, lv_pct(44));
    if (!cancellable)
        lv_obj_add_flag(cancel, LV_OBJ_FLAG_HIDDEN);
    confirm_ = button(footer, confirm_label, TouchAction::Confirm);
    lv_obj_set_width(confirm_, lv_pct(cancellable ? 52 : 100));
    lv_obj_update_layout(root_);
    update_review();
}
void TouchView::status(const char* heading, const char* content, bool cancellable,
                       const char* coin) {
    clear();
    title(heading, coin);
    auto* body = label(root_, content);
    lv_obj_set_pos(body, 8, content_top_ + 8);
    lv_obj_set_width(body, lv_pct(94));
    if (cancellable) {
        auto* cancel =
            button(root_, locale_text(Text::cancelar_058, language_), TouchAction::Cancel);
        lv_obj_set_size(cancel, lv_pct(94), 44);
        lv_obj_align(cancel, LV_ALIGN_BOTTOM_MID, 0, -8);
    }
}
void TouchView::busy(const char* heading, const char* content) {
    status(heading, content, false);
    progress_dots(root_, 0);
}
void TouchView::approval(const char* message, const char* details, const char* accept,
                         const char* reject, const char* coin, bool full_review) {
    text(message, details, accept, full_review, true, reject, coin);
    auto* footer = lv_obj_get_parent(confirm_);
    auto* cancel = buttons_.front()->object;
    lv_obj_set_height(footer, 74);
    lv_obj_set_style_pad_column(footer, 8, 0);
    lv_obj_set_height(scroll_, lv_obj_get_height(root_) - content_top_ - 74);
    lv_obj_move_to_index(confirm_, 0);
    std::swap(buttons_[0], buttons_[1]);

    const auto decorate = [](lv_obj_t* object, const lv_image_dsc_t& icon, std::uint32_t color) {
        lv_obj_set_size(object, lv_pct(48), 58);
        lv_obj_set_style_bg_color(object, lv_color_hex(color), 0);
        auto* caption = lv_obj_get_child(object, 0);
        lv_obj_align(caption, LV_ALIGN_BOTTOM_MID, 0, -2);
        auto* image = lv_image_create(object);
        lv_image_set_src(image, &icon);
        lv_obj_set_style_image_recolor(image, lv_color_white(), 0);
        lv_obj_align(image, LV_ALIGN_TOP_MID, 0, 1);
    };
    decorate(confirm_, feather_check, 0x176b46);
    decorate(cancel, feather_x, 0x9f2839);
    lv_obj_update_layout(root_);
    update_review();
}
void TouchView::show_progress(const char* message) {
    if (progress_)
        return;
    event_.reset();
    ++screen_id_;
    progress_ = lv_obj_create(root_);
    container(progress_);
    lv_obj_set_size(progress_, lv_pct(100), lv_pct(100));
    lv_obj_set_style_bg_opa(progress_, LV_OPA_COVER, 0);
    lv_obj_add_flag(progress_, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_remove_flag(progress_, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_remove_flag(progress_, LV_OBJ_FLAG_SCROLL_CHAIN);
    auto* heading = label(progress_, message);
    lv_obj_set_style_text_font(heading, uses_cjk(language_) ? &airlock_chinese_16 : &title_font, 0);
    lv_obj_set_style_text_align(heading, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(heading, LV_ALIGN_CENTER, 0, -18);
    progress_dots(progress_, 19);
}
void TouchView::hide_progress() {
    if (!progress_)
        return;
    lv_obj_delete(progress_);
    progress_ = nullptr;
    event_.reset();
    ++screen_id_;
}
void TouchView::language(Language language) {
    language_ = language;
    lv_obj_set_style_text_font(root_, uses_cjk(language_) ? &airlock_chinese_16 : &latin_font, 0);
}
void TouchView::update_review() {
    if (!confirm_)
        return;
    if (review_required_ && lv_obj_get_scroll_bottom(scroll_) > 0)
        lv_obj_add_state(confirm_, LV_STATE_DISABLED);
    else
        lv_obj_remove_state(confirm_, LV_STATE_DISABLED);
}
void TouchView::emit(TouchAction action, std::size_t selection) {
    if (rebuilding_ || progress_ || event_)
        return;
    if (action == TouchAction::Confirm && confirm_ && lv_obj_has_state(confirm_, LV_STATE_DISABLED))
        return;
    TouchEvent next{action, screen_id_, selection, {}};
    if (action == TouchAction::Submit) {
        if (!textarea_)
            return;
        if (!next.value.assign(lv_textarea_get_text(textarea_)))
            next.action = TouchAction::InputTooLong;
        // Clear only successful submissions; preserve too-long input for correction.
        else
            lv_textarea_set_text(textarea_, "");
    }
    if (action == TouchAction::Cancel && textarea_)
        lv_textarea_set_text(textarea_, "");
    event_.emplace(std::move(next));
}
void TouchView::on_button(lv_event_t* event) {
    auto* metadata = static_cast<Button*>(lv_event_get_user_data(event));
    metadata->view->emit(metadata->action, metadata->selection);
}
void TouchView::on_keyboard(lv_event_t* event) {
    auto* view = static_cast<TouchView*>(lv_event_get_user_data(event));
    view->emit(lv_event_get_code(event) == LV_EVENT_READY ? TouchAction::Submit
                                                          : TouchAction::Cancel);
}
void TouchView::on_scroll(lv_event_t* event) {
    auto* view = static_cast<TouchView*>(lv_event_get_user_data(event));
    if (!view->rebuilding_)
        view->update_review();
}
std::optional<TouchEvent> TouchView::take_event() {
    auto event = std::move(event_);
    event_.reset();
    return event;
}
} // namespace airlock
