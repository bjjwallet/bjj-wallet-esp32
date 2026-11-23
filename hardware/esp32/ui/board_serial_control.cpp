// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/board_serial_control.hpp"
#include "core/rules.hpp"
#include <charconv>
#include <algorithm>
#include <limits>

namespace airlock {
namespace {
bool visible(lv_obj_t* object) {
    for (auto* current = object; current; current = lv_obj_get_parent(current))
        if (lv_obj_has_flag(current, LV_OBJ_FLAG_HIDDEN))
            return false;
    return true;
}
const char* caption(lv_obj_t* object) {
    if (lv_obj_check_type(object, &lv_checkbox_class))
        return lv_checkbox_get_text(object);
    auto* child = lv_obj_get_child(object, 0);
    return child && lv_obj_check_type(child, &lv_label_class) ? lv_label_get_text(child) : "Botao";
}
std::optional<std::size_t> number(std::string_view text) {
    std::size_t result = 0;
    const auto parsed = std::from_chars(text.data(), text.data() + text.size(), result);
    if (parsed.ec != std::errc{} || parsed.ptr != text.data() + text.size() || result == 0)
        return std::nullopt;
    return result;
}
} // namespace
void BoardSerialControl::focus(lv_obj_t* object) {
    if (focused_)
        lv_obj_remove_state(focused_, LV_STATE_FOCUSED | LV_STATE_FOCUS_KEY);
    focused_ = object;
    if (!object)
        return;
    lv_obj_set_style_outline_width(object, 3, LV_STATE_FOCUSED);
    lv_obj_set_style_outline_color(object, lv_color_hex(0xffcc55), LV_STATE_FOCUSED);
    lv_obj_add_state(object, LV_STATE_FOCUSED | LV_STATE_FOCUS_KEY);
    lv_obj_scroll_to_view_recursive(object, LV_ANIM_OFF);
}
void BoardSerialControl::sync(TouchView& view) {
    if (screen_ == view.screen_id())
        return;
    // Old widgets were destroyed by TouchView::clear; never dereference them.
    focused_ = nullptr;
    screen_ = view.screen_id();
    lv_obj_update_layout(view.root_);
    if (view.keyboard_) {
        lv_obj_add_state(view.keyboard_, LV_STATE_FOCUSED | LV_STATE_FOCUS_KEY);
        lv_obj_set_style_outline_width(view.keyboard_, 2, LV_PART_ITEMS | LV_STATE_FOCUSED);
        lv_obj_set_style_outline_color(view.keyboard_, lv_color_hex(0xffcc55),
                                       LV_PART_ITEMS | LV_STATE_FOCUSED);
        lv_buttonmatrix_set_selected_button(view.keyboard_, 0);
        return;
    }
    // Start on the primary action, so opening a dialog never defaults to Cancel.
    for (const auto& button : view.buttons_)
        if (visible(button->object) && lv_obj_check_type(button->object, &lv_checkbox_class)) {
            focus(button->object);
            return;
        }
    if (view.confirm_ && visible(view.confirm_)) {
        focus(view.confirm_);
        return;
    }
    for (const auto& button : view.buttons_)
        if (visible(button->object)) {
            focus(button->object);
            break;
        }
}
std::string BoardSerialControl::focus_description(TouchView& view) const {
    if (view.keyboard_) {
        const auto id = lv_buttonmatrix_get_selected_button(view.keyboard_);
        const auto* text = lv_buttonmatrix_get_button_text(view.keyboard_, id);
        return std::string("FOCO: tecla ") + (text ? text : "nenhuma");
    }
    if (!focused_)
        return "FOCO: nenhum botao";
    return std::string("FOCO: ") + caption(focused_);
}
std::string BoardSerialControl::key(std::string_view command, TouchView& view) {
    if (command == "back" && view.directional_back_) {
        view.emit(TouchAction::Back);
        return "OK";
    }
    if (command == "back" || command == "cancel")
        return execute("voltar", view);
#ifndef AIRLOCK_BOARD_AGENT_CONTROL
    if (view.keyboard_) {
        if (command == "erase") {
            lv_textarea_delete_char(view.textarea_);
            return "OK: caractere apagado";
        }
        if (command == "submit")
            return execute("ok", view);
        if (command == "confirm") {
            const auto id = lv_buttonmatrix_get_selected_button(view.keyboard_);
            if (id == LV_BUTTONMATRIX_BUTTON_NONE ||
                lv_buttonmatrix_has_button_ctrl(view.keyboard_, id,
                                                LV_BUTTONMATRIX_CTRL_DISABLED) ||
                lv_buttonmatrix_has_button_ctrl(view.keyboard_, id, LV_BUTTONMATRIX_CTRL_HIDDEN))
                return "ERRO: tecla indisponivel";
            // Same callback as touching a key, including mode changes, OK and X.
            lv_obj_send_event(view.keyboard_, LV_EVENT_VALUE_CHANGED, nullptr);
            if (view.event_)
                return "OK";
            if (lv_buttonmatrix_get_selected_button(view.keyboard_) == LV_BUTTONMATRIX_BUTTON_NONE)
                lv_buttonmatrix_set_selected_button(view.keyboard_, 0);
            return focus_description(view);
        }
        std::uint32_t code = command == "up"      ? LV_KEY_UP
                             : command == "down"  ? LV_KEY_DOWN
                             : command == "left"  ? LV_KEY_LEFT
                             : command == "right" ? LV_KEY_RIGHT
                                                  : 0;
        if (!code)
            return "ERRO: tecla desconhecida";
        lv_obj_send_event(view.keyboard_, LV_EVENT_KEY, &code);
        return focus_description(view);
    }
    if (command == "confirm") {
        if (!focused_)
            return "ERRO: nenhum botao selecionado";
        if (lv_obj_has_state(focused_, LV_STATE_DISABLED))
            return "ERRO: botao bloqueado; leia e role o conteudo";
        lv_obj_send_event(focused_, LV_EVENT_CLICKED, nullptr);
        return "OK";
    }
    const bool vertical = command == "up" || command == "down";
    if (!vertical && command != "left" && command != "right")
        return "ERRO: tecla desconhecida";
    const bool has_selection =
        std::any_of(view.buttons_.begin(), view.buttons_.end(), [](const auto& button) {
            return button->action == TouchAction::Select && visible(button->object);
        });
    if (vertical && view.scroll_ && has_selection && focused_ &&
        lv_obj_check_type(focused_, &lv_checkbox_class)) {
        const bool can_scroll = command == "down" ? lv_obj_get_scroll_bottom(view.scroll_) > 0
                                                  : lv_obj_get_scroll_y(view.scroll_) > 0;
        if (can_scroll) {
            lv_obj_scroll_by(view.scroll_, 0, command == "up" ? 80 : -80, LV_ANIM_OFF);
            return focus_description(view);
        }
    }
    if (vertical && view.scroll_ && !has_selection) {
        lv_obj_scroll_by(view.scroll_, 0, command == "up" ? 80 : -80, LV_ANIM_OFF);
        return focus_description(view);
    }
    if (!focused_)
        return focus_description(view);
    lv_obj_update_layout(view.root_);
    lv_area_t current{};
    lv_obj_get_coords(focused_, &current);
    lv_obj_t* next = nullptr;
    auto best = std::numeric_limits<std::int64_t>::max();
    for (const auto& button : view.buttons_) {
        if (!visible(button->object) || button->object == focused_)
            continue;
        lv_area_t area{};
        lv_obj_get_coords(button->object, &area);
        const std::int64_t dx = (area.x1 + area.x2) - (current.x1 + current.x2);
        const std::int64_t dy = (area.y1 + area.y2) - (current.y1 + current.y2);
        const auto primary = vertical ? dy : dx;
        const auto perpendicular = vertical ? dx : dy;
        if ((command == "up" || command == "left") ? primary >= 0 : primary <= 0)
            continue;
        const auto score = primary * primary + 4 * perpendicular * perpendicular;
        if (score < best) {
            best = score;
            next = button->object;
        }
    }
    if (next)
        focus(next);
    return focus_description(view);
#else
    return "ERRO: navegacao por teclado removida";
#endif
}
std::string BoardSerialControl::execute_agent(std::string_view request, TouchView& view) {
    constexpr std::string_view prefix = "agent ";
    if (request.substr(0, prefix.size()) != prefix)
        return "ERRO: canal exclusivo de automacao";
    const auto command = request.substr(prefix.size());
    if (command == "back")
        return execute("key back", view);
    if (command == "cancel")
        return execute("key cancel", view);
    const bool allowed = command == "tela" || command == "ok" || command == "rolar" ||
                         command == "fim" || command.substr(0, 6) == "texto " ||
                         (command.substr(0, 6) == "click " && number(command.substr(6)));
    if (!allowed)
        return "ERRO: comando de automacao invalido";
    return execute(command, view);
}
std::string BoardSerialControl::execute(std::string_view command, TouchView& view) {
    if (command.empty() || !valid_text(command))
        return "ERRO: comando invalido";
    if (view.progress_) {
        if (command == "tela")
            return std::string(lv_label_get_text(lv_obj_get_child(view.progress_, 0))) +
                   "\nFOCO: nenhum botao";
        return "ERRO: aguarde o fim da operacao";
    }
    if (view.event_)
        return "ERRO: aguarde a atualizacao da tela";
    sync(view);
    if (command.substr(0, 4) == "key ")
        return key(command.substr(4), view);
#ifndef AIRLOCK_BOARD_AGENT_CONTROL
    if (command.substr(0, 5) == "type ") {
        if (!view.textarea_)
            return "ERRO: esta tela nao tem campo de texto";
        const auto hex = command.substr(5);
        if (hex.empty() || hex.size() > 8 || hex.size() % 2)
            return "ERRO: caractere invalido";
        std::array<char, 5> text{};
        for (std::size_t i = 0; i < hex.size() / 2; ++i) {
            unsigned byte = 0;
            const auto parsed =
                std::from_chars(hex.data() + i * 2, hex.data() + i * 2 + 2, byte, 16);
            if (parsed.ec != std::errc{} || parsed.ptr != hex.data() + i * 2 + 2)
                return "ERRO: caractere invalido";
            text[i] = static_cast<char>(byte);
        }
        const std::string_view character(text.data(), hex.size() / 2);
        const auto first = static_cast<unsigned char>(text[0]);
        const std::size_t length = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
        if (character.size() != length || !valid_text(character))
            return "ERRO: caractere invalido";
        lv_textarea_add_text(view.textarea_, text.data());
        wipe(text.data(), text.size());
        return "OK: caractere inserido";
    }
#endif
    if (command == "ajuda" || command == "help")
        return "Comandos internos: tela | 1..N (menu) | click N (botao) | ok | voltar | texto "
               "VALOR | rolar | fim\n"
               "Somente teste da placa. Nao digite senhas ou palavras reais.";
    if (command == "tela") {
        auto* heading = lv_obj_get_child(view.root_, 0);
        std::string description = heading && lv_obj_check_type(heading, &lv_label_class)
                                      ? lv_label_get_text(heading)
                                      : "Tela";
        std::size_t index = 0;
        for (const auto& button : view.buttons_) {
            if (!visible(button->object))
                continue;
            description += "\nclick " + std::to_string(++index) + ": " + caption(button->object);
            if (lv_obj_check_type(button->object, &lv_checkbox_class))
                description += lv_obj_has_state(button->object, LV_STATE_CHECKED) ? " [X]" : " [ ]";
            if (lv_obj_has_state(button->object, LV_STATE_DISABLED))
                description += " [bloqueado]";
        }
        if (view.textarea_)
            description += "\nCampo disponivel: texto VALOR, depois ok";
        return description + "\n" + focus_description(view);
    }
    if (command.substr(0, 6) == "texto ") {
        if (!view.textarea_)
            return "ERRO: esta tela nao tem campo de texto";
        Credential text;
        if (!text.assign(command.substr(6)))
            return "ERRO: texto longo demais";
        // The command parser is bounded before reaching LVGL; accepted character
        // filtering and length limits still belong to the existing textarea.
        lv_textarea_set_text(view.textarea_, text.view().data());
        return "OK: texto atualizado";
    }
    if (command == "rolar" || command == "fim") {
        if (!view.scroll_)
            return "ERRO: esta tela nao tem conteudo rolavel";
        lv_obj_update_layout(view.root_);
        const auto amount = command == "fim" ? lv_obj_get_scroll_bottom(view.scroll_) : 100;
        lv_obj_scroll_by(view.scroll_, 0, -amount, LV_ANIM_OFF);
        return "OK: rolagem";
    }
    if ((command == "ok" || command == "voltar") && view.keyboard_) {
        lv_obj_send_event(view.keyboard_, command == "ok" ? LV_EVENT_READY : LV_EVENT_CANCEL,
                          nullptr);
        return "OK";
    }
    const bool click = command.substr(0, 6) == "click ";
    const auto selected = number(click ? command.substr(6) : command);
    std::size_t index = 0;
    for (const auto& button : view.buttons_) {
        if (!visible(button->object))
            continue;
        ++index;
        const bool matches = (command == "ok" && button->action == TouchAction::Confirm) ||
                             (command == "voltar" && button->action == TouchAction::Cancel) ||
                             (selected && (click ? index == *selected
                                                 : button->action == TouchAction::Select &&
                                                       button->selection + 1 == *selected));
        if (!matches)
            continue;
        if (lv_obj_has_state(button->object, LV_STATE_DISABLED))
            return "ERRO: botao bloqueado; leia e role o conteudo";
        lv_obj_send_event(button->object, LV_EVENT_CLICKED, nullptr);
        return "OK";
    }
    return "ERRO: comando ou botao indisponivel; use tela ou ajuda";
}
} // namespace airlock
