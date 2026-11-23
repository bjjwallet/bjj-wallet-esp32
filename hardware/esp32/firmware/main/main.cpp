// SPDX-License-Identifier: GPL-3.0-or-later
#include "airlock_board.hpp"
#include "airlock_ble.hpp"
#include "airlock_random.hpp"
#include "airlock_setup.hpp"
#include "core/hd_wallet.hpp"
#include "ui/first_login.hpp"
#include "esp_app_desc.h"
#include "esp_system.h"
#include "driver/temperature_sensor.h"
#include "esp_mac.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#ifdef AIRLOCK_BOARD_AGENT_CONTROL
#include "ui/board_serial_control.hpp"
#include <unistd.h>
#endif

#ifdef AIRLOCK_USB_POWER_ONLY
extern "C" void bjj_usb_power_only_init();
#endif

namespace {
#ifdef AIRLOCK_BOARD_NO_TOUCH
constexpr char board_model[] = "ESP32-S3 ES3N28P";
#else
constexpr char board_model[] = "ESP32-S3 ES3C28P";
#endif

class BoardTemperature {
    temperature_sensor_handle_t sensor_ = nullptr;

  public:
    BoardTemperature() {
        temperature_sensor_config_t config{};
        config.range_min = -10;
        config.range_max = 80;
        config.clk_src = TEMPERATURE_SENSOR_CLK_SRC_DEFAULT;
        auto error = temperature_sensor_install(&config, &sensor_);
        if (error == ESP_OK)
            error = temperature_sensor_enable(sensor_);
        if (error != ESP_OK) {
            ESP_LOGW("airlock_device", "Temperature unavailable: %s", esp_err_to_name(error));
            if (sensor_)
                temperature_sensor_uninstall(sensor_);
            sensor_ = nullptr;
        }
    }
    ~BoardTemperature() {
        if (sensor_) {
            temperature_sensor_disable(sensor_);
            temperature_sensor_uninstall(sensor_);
        }
    }
    std::string read() const {
        float celsius = 0;
        if (!sensor_ || temperature_sensor_get_celsius(sensor_, &celsius) != ESP_OK ||
            !std::isfinite(celsius))
            return {};
        char text[32];
        std::snprintf(text, sizeof text, "%.1f °C", static_cast<double>(celsius));
        return text;
    }
};

} // namespace
extern "C" void app_main() {
#ifdef AIRLOCK_USB_POWER_ONLY
    bjj_usb_power_only_init();
#endif
    airlock::crypto::BoardRandom random;
    ESP_ERROR_CHECK(random.start_before_bluetooth() ? ESP_OK : ESP_FAIL);
    ESP_ERROR_CHECK(airlock::board::start());
    airlock::TouchView view(lv_display_get_screen_active(airlock::board::display()));
    view.splash();
    lv_refr_now(airlock::board::display());
    ESP_ERROR_CHECK(airlock::board::backlight(true));
#ifdef AIRLOCK_CRYPTO_CHECK
    const auto started = esp_timer_get_time();
    ESP_ERROR_CHECK(airlock::crypto::self_test() ? ESP_OK : ESP_FAIL);
    ESP_ERROR_CHECK(airlock::crypto::hd_self_test(random) ? ESP_OK : ESP_FAIL);
    ESP_LOGI("airlock_crypto", "Crypto/HD self-tests passed (%lld ms)",
             static_cast<long long>((esp_timer_get_time() - started) / 1000));
#endif
    airlock::NvsSetupStore store;
    airlock::BoardSetupService service(store, random);
    std::string stored_device_name;
    service.load_device_name(stored_device_name);
    std::string published_device_name = airlock::full_device_name(stored_device_name);
    BoardTemperature temperature;
    const auto platform =
        std::string("ESP-IDF ") + esp_get_idf_version() + " / FreeRTOS " + tskKERNEL_VERSION_NUMBER;
    char screen[24];
    std::snprintf(
        screen, sizeof screen, "%ux%u",
        static_cast<unsigned>(lv_display_get_horizontal_resolution(airlock::board::display())),
        static_cast<unsigned>(lv_display_get_vertical_resolution(airlock::board::display())));
    const auto measured_temperature = temperature.read();
    ESP_LOGI("airlock_device", "name=%s; version=%s; platform=%s; hardware=%s; screen=%s; CPU=%s",
             published_device_name.c_str(), esp_app_get_description()->version, platform.c_str(),
             board_model, screen,
             measured_temperature.empty() ? "unavailable" : measured_temperature.c_str());
    airlock::NimbleDeviceService bluetooth(service,
                                           {published_device_name,
                                            esp_app_get_description()->version, board_model, screen,
                                            measured_temperature, platform},
                                           [&temperature] { return temperature.read(); });
    ESP_ERROR_CHECK(bluetooth.start());
#ifdef AIRLOCK_SETUP_SELFTEST
    ESP_ERROR_CHECK(airlock::setup_storage_self_test(random) ? ESP_OK : ESP_FAIL);
#endif
    auto application = std::make_unique<airlock::FirstLogin>(view, service);
    application->start(esp_timer_get_time() / 1000);
#ifdef AIRLOCK_BOARD_AGENT_CONTROL
    airlock::BoardSerialControl serial_control;
    std::array<char, 1025> serial_line{};
    std::size_t serial_size = 0;
    bool serial_discard = false;
    std::uint64_t serial_screen = view.screen_id(), reported_screen = 0;
    std::puts("AIRLOCK AGENT: controle por solicitacoes no chat.");
#endif
    std::optional<airlock::PairingRequest> pairing;
    std::uint64_t prompt_connection = 0, declined_connection = 0;
    std::size_t selected_wallet = 0;
    for (;;) {
        const auto delay = lv_timer_handler();
        application->poll_signing(bluetooth, esp_timer_get_time() / 1000);
        application->tick(esp_timer_get_time() / 1000);
        service.signing_session(application->signing_session());
        const auto peer = bluetooth.public_peer();
        service.connection_state(peer.connected ? peer.connection : 0);
        if (!peer.connected) {
            pairing.reset();
            prompt_connection = declined_connection = 0;
            application->cancel_mobile();
        }
        if (auto request = bluetooth.poll_pairing())
            pairing = request;
        if (pairing && !bluetooth.pairing_active(pairing->token)) {
            pairing.reset();
            application->cancel_mobile();
        }
        if (!prompt_connection && peer.connected && declined_connection != peer.connection &&
            (pairing || (peer.authenticated && peer.encrypted &&
                         !service.connection_approved(peer.connection)))) {
            const auto token = pairing ? pairing->token : peer.connection;
            if (application->prompt_mobile(token, bluetooth.connection_identity(),
                                           pairing ? std::optional<std::uint32_t>(pairing->passkey)
                                                   : std::nullopt))
                prompt_connection = peer.connection;
        }
        application->show_public_sync(bluetooth.public_sync_active());
#ifdef AIRLOCK_BOARD_AGENT_CONTROL
        for (unsigned count = 0; count < 128; ++count) {
            char byte = 0;
            if (::read(STDIN_FILENO, &byte, 1) != 1)
                break;
            if (serial_size == 0 && !serial_discard)
                serial_screen = view.screen_id();
            if (byte == '\r' || byte == '\n') {
                if (serial_discard || serial_screen != view.screen_id())
                    std::puts("ERRO: comando descartado (limite ou mudanca de tela)");
                else if (serial_size)
                    std::puts(serial_control.execute_agent({serial_line.data(), serial_size}, view)
                                  .c_str());
                const bool had_line = serial_size || serial_discard;
                airlock::wipe(serial_line.data(), serial_line.size());
                serial_size = 0;
                serial_discard = false;
                if (had_line)
                    break;
            } else if (!serial_discard) {
                if (serial_size == serial_line.size() - 1)
                    serial_discard = true;
                else
                    serial_line[serial_size++] = byte;
            }
        }
#endif
        application->consume();
        if (application->device_name() != published_device_name) {
            published_device_name = application->device_name();
            bluetooth.set_device_name(published_device_name);
        }
        if (auto decision = application->take_mobile_decision()) {
            if (peer.connected && peer.connection == prompt_connection) {
                const bool accepted =
                    decision->second && bluetooth.accept_current_device(peer.connection);
                if (!decision->second)
                    bluetooth.reject_current_device(peer.connection);
                if (pairing && decision->first == pairing->token) {
                    bluetooth.respond_pairing(pairing->token, accepted);
                    pairing.reset();
                }
                if (accepted) {
                    service.approve_connection(peer.connection);
                } else {
                    service.reject_connection(peer.connection);
                    declined_connection = peer.connection;
                }
            }
            prompt_connection = 0;
        }
        if (selected_wallet != application->selected_wallet()) {
            selected_wallet = application->selected_wallet();
            service.select_public_wallet(selected_wallet);
        }
        if (!application->mobile_allowed())
            service.connection_state(0);
        bluetooth.set_unlocked(application->mobile_allowed());
#ifdef AIRLOCK_BOARD_AGENT_CONTROL
        if (reported_screen != view.screen_id()) {
            reported_screen = view.screen_id();
            std::puts(serial_control.execute_agent("agent tela", view).c_str());
        }
#endif
        vTaskDelay(pdMS_TO_TICKS(std::clamp<std::uint32_t>(delay, 1, 16)));
    }
}
