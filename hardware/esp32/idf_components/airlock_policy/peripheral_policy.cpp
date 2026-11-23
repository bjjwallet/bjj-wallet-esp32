// SPDX-License-Identifier: GPL-3.0-or-later
#include "peripheral_policy.hpp"
#include "esp_err.h"
#include "esp_wifi.h"
#include "driver/sdmmc_host.h"
#include "driver/sdspi_host.h"
#include "sdmmc_cmd.h"

// Wi-Fi e SD/microSD foram desabilitados porque esta carteira opera totalmente
// offline. Por decisão do projeto, os dados ficam na flash interna, sem usar o
// cartão. O Bluetooth é mantido somente para trocar dados com o aplicativo;
// quem acessa a internet e transmite a transação assinada é o aplicativo.
// Não existe fallback __real_*: as operações são recusadas antes de inicializar
// Wi-Fi/cartão. As APIs de Bluetooth não são afetadas por este bloqueio.
extern "C" {
esp_err_t __wrap_esp_wifi_init(const wifi_init_config_t*) {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_esp_wifi_start() {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_esp_wifi_connect() {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_esp_wifi_scan_start(const wifi_scan_config_t*, bool) {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_esp_wifi_set_promiscuous(bool) {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_esp_wifi_80211_tx(wifi_interface_t, const void*, int, bool) {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_sdmmc_host_init() {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_sdspi_host_init() {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_sdspi_host_init_device(const sdspi_device_config_t*, sdspi_dev_handle_t*) {
    return ESP_ERR_NOT_SUPPORTED;
}
esp_err_t __wrap_sdmmc_card_init(const sdmmc_host_t*, sdmmc_card_t*) {
    return ESP_ERR_NOT_SUPPORTED;
}
}
