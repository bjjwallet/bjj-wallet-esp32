#include "hardware/es3c28p.hpp"
// SPDX-License-Identifier: GPL-3.0-or-later
#include "ui/board_serial_control.hpp"
#include <array>
#include <cstdlib>
#include <iostream>

namespace {
unsigned checks = 0;
#define CHECK(value)                                                                               \
    do {                                                                                           \
        ++checks;                                                                                  \
        if (!(value)) {                                                                            \
            std::cerr << "Serial control check failed: line " << __LINE__ << '\n';                 \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
} // namespace
int main() {
    lv_init();
    auto* display = lv_display_create(airlock::es3c28p::width, airlock::es3c28p::height);
    std::array<std::uint8_t, airlock::es3c28p::width * 20 * 2> pixels{};
    lv_display_set_buffers(display, pixels.data(), nullptr, pixels.size(),
                           LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(display,
                            [](auto* disp, const auto*, auto*) { lv_display_flush_ready(disp); });
    {
        airlock::TouchView view(lv_screen_active());
        airlock::BoardSerialControl control;
        view.menu("Teste", {"Teclado", "Informacoes", "Reiniciar"});
        CHECK(control.execute_agent("key confirm", view).find("ERRO") == 0);
        CHECK(control.execute_agent("1", view).find("ERRO") == 0);
        CHECK(control.execute_agent("agent key confirm", view).find("ERRO") == 0);
        CHECK(control.execute_agent("agent type 61", view).find("ERRO") == 0);
        CHECK(!view.take_event());
        CHECK(control.execute_agent("agent tela", view).find("click 2: Informacoes") !=
              std::string::npos);
        CHECK(control.execute_agent("agent click 2", view) == "OK");
        CHECK(control.execute_agent("agent back", view).find("ERRO") == 0);
        CHECK(view.take_event()->selection == 1);
        CHECK(control.execute("tela", view).find("click 2: Informacoes") != std::string::npos);
        CHECK(control.execute("0", view).find("ERRO") == 0);
        CHECK(control.execute("4", view).find("ERRO") == 0);
        CHECK(control.execute("ok", view).find("ERRO") == 0);
        CHECK(!view.take_event());
        CHECK(control.execute("2", view) == "OK");
        CHECK(control.execute("1", view).find("ERRO") == 0); // Pending event cannot be replaced.
        auto event = view.take_event();
        CHECK(event && event->action == airlock::TouchAction::Select && event->selection == 1);
        CHECK(control.execute("click 1", view) == "OK");
        CHECK(view.take_event()->selection == 0);
        view.input("Teclado", "Texto publico de teste", true);
        CHECK(control.execute("texto Public-Test1!", view).find("OK") == 0);
        CHECK(control.execute("tela", view).find("Public-Test1!") == std::string::npos);
        CHECK(control.execute("ok", view) == "OK");
        event = view.take_event();
        CHECK(event && event->action == airlock::TouchAction::Submit &&
              event->value.equals("Public-Test1!"));
        CHECK(control.execute("voltar", view) == "OK");
        CHECK(view.take_event()->action == airlock::TouchAction::Cancel);
        view.text("Confirmacao", "Informacao", "Continuar", false, false);
        CHECK(control.execute("tela", view).find("Cancel") == std::string::npos);
        CHECK(control.execute("voltar", view).find("ERRO") == 0);
        CHECK(control.execute("click 2", view).find("ERRO") == 0);
        CHECK(!view.take_event());
        CHECK(control.execute("click 1", view) == "OK");
        CHECK(view.take_event()->action == airlock::TouchAction::Confirm);
        view.input("Digitacao direta", "Texto publico", false);
        CHECK(control.execute("type 61", view) == "OK: caractere inserido");
        CHECK(control.execute("type c3a9", view) == "OK: caractere inserido");
        CHECK(control.execute("key erase", view) == "OK: caractere apagado");
        CHECK(control.execute("type 20", view) == "OK: caractere inserido");
        CHECK(control.execute("type 5a", view) == "OK: caractere inserido");
        for (const auto* invalid : {"type ", "type 0", "type gg", "type 00", "type 0a", "type ff",
                                    "type 6162", "type c3"})
            CHECK(control.execute(invalid, view).find("ERRO") == 0);
        CHECK(control.execute("key submit", view) == "OK");
        auto typed = view.take_event();
        CHECK(typed && typed->action == airlock::TouchAction::Submit && typed->value.equals("a Z"));
        std::string long_text;
        for (unsigned i = 0; i < 50; ++i)
            long_text += "Public review detail\n";
        view.text("Revisao", long_text.c_str(), "Aprovar", true);
        CHECK(control.execute("ok", view).find("ERRO") == 0);
        CHECK(!view.take_event());
        CHECK(control.execute("fim", view).find("OK") == 0);
        CHECK(control.execute("ok", view) == "OK");
        CHECK(view.take_event()->action == airlock::TouchAction::Confirm);
        CHECK(control.execute(std::string(1025, 'a'), view).find("ERRO") == 0);
        CHECK(control.execute(std::string_view("texto a\0b", 9), view).find("ERRO") == 0);
        view.menu("Direcionais", {"Primeiro", "Segundo", "Terceiro"});
        CHECK(control.execute("tela", view).find("FOCO: Primeiro") != std::string::npos);
        CHECK(control.execute("key down", view) == "FOCO: Segundo");
        CHECK(control.execute("key up", view) == "FOCO: Primeiro");
        CHECK(control.execute("key left", view) == "FOCO: Primeiro");
        CHECK(control.execute("key right", view) == "FOCO: Primeiro");
        CHECK(control.execute("key down", view) == "FOCO: Segundo");
        CHECK(!view.take_event()); // Arrows only move focus.
        CHECK(control.execute("key confirm", view) == "OK");
        event = view.take_event();
        CHECK(event && event->action == airlock::TouchAction::Select && event->selection == 1);
        view.text("Dialogo", "Detalhes", "Continuar");
        CHECK(control.execute("tela", view).find("FOCO: Continuar") != std::string::npos);
        CHECK(control.execute("key left", view) == "FOCO: Cancel");
        CHECK(control.execute("key right", view) == "FOCO: Continuar");
        CHECK(control.execute("key back", view) == "OK");
        CHECK(view.take_event()->action == airlock::TouchAction::Cancel);
        view.input("Teclado direcional", "Teste publico", false);
        CHECK(control.execute("key right", view) == "FOCO: tecla q");
        CHECK(control.execute("key confirm", view) == "FOCO: tecla q");
        CHECK(!view.take_event());
        CHECK(control.execute("ok", view) == "OK");
        event = view.take_event();
        CHECK(event && event->action == airlock::TouchAction::Submit && event->value.equals("q"));
        view.input("Cancelar teclado", "Teste publico", false);
        CHECK(control.execute("key cancel", view) == "OK");
        CHECK(view.take_event()->action == airlock::TouchAction::Cancel);
        view.text("Revisao direcional", long_text.c_str(), "Aprovar", true);
        CHECK(control.execute("key confirm", view).find("ERRO") == 0);
        CHECK(!view.take_event());
        for (unsigned i = 0; i < 30; ++i)
            control.execute("key down", view);
        CHECK(control.execute("key confirm", view) == "OK");
        CHECK(view.take_event()->action == airlock::TouchAction::Confirm);
    }
    lv_display_delete(display);
    lv_deinit();
    std::cout << checks
              << " serial control checks passed: real LVGL events, hidden/disabled buttons, input, "
                 "pending events and review gating.\n";
}
