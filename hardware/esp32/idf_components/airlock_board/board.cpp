// SPDX-License-Identifier: GPL-3.0-or-later
#include "airlock_board.hpp"
#include "hardware/es3c28p.hpp"
#include "hardware/ft6336.hpp"
#include "ui/touch_input.hpp"
#include "lcd_init.h"
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_flash.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_psram.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#if !CONFIG_IDF_TARGET_ESP32S3 || !CONFIG_SPIRAM_MODE_OCT
#error "ES3C28P requires ESP32-S3 with octal PSRAM"
#endif

namespace airlock::board {
namespace {
constexpr char tag[] = "es3c28p";
lv_display_t* screen = nullptr;
esp_lcd_panel_handle_t panel = nullptr;
esp_lcd_panel_io_handle_t panel_io = nullptr;
i2c_master_bus_handle_t touch_bus = nullptr;
i2c_master_dev_handle_t touch_device = nullptr;
SemaphoreHandle_t dma_done = nullptr;
Ft6336Decoder decoder;
bool started = false;

constexpr gpio_num_t pin(int value) {
    return static_cast<gpio_num_t>(value);
}

esp_err_t safe_pins() {
    // Wi-Fi e microSD desabilitados: a carteira opera offline. O slot SDIO
    // fica sem clock e sem inicialização; os dados da carteira usam flash interna.
    // Isto não corta fisicamente a alimentação elétrica do slot.
    gpio_config_t inputs{};
    for (int p : es3c28p::sd_command_data)
        inputs.pin_bit_mask |= 1ULL << p;
    inputs.mode = GPIO_MODE_INPUT;
    ESP_RETURN_ON_ERROR(gpio_config(&inputs), tag, "SD pins");
    ESP_RETURN_ON_ERROR(gpio_set_level(pin(es3c28p::sd_clock), 0), tag, "SD clock low");
    ESP_RETURN_ON_ERROR(gpio_set_level(pin(es3c28p::lcd_backlight), 0), tag, "backlight low");
    ESP_RETURN_ON_ERROR(gpio_set_level(pin(es3c28p::audio_enable), 1), tag, "audio idle");
    gpio_config_t outputs{};
    outputs.pin_bit_mask = (1ULL << es3c28p::sd_clock) | (1ULL << es3c28p::lcd_backlight) |
                           (1ULL << es3c28p::audio_enable);
    outputs.mode = GPIO_MODE_OUTPUT;
    return gpio_config(&outputs);
}

bool color_done(esp_lcd_panel_io_handle_t, esp_lcd_panel_io_event_data_t*, void*) {
    BaseType_t awakened = pdFALSE;
    xSemaphoreGiveFromISR(dma_done, &awakened);
    return awakened == pdTRUE;
}

void flush(lv_display_t*, const lv_area_t* area, std::uint8_t* pixels) {
    const auto count =
        static_cast<std::uint32_t>((area->x2 - area->x1 + 1) * (area->y2 - area->y1 + 1));
    lv_draw_sw_rgb565_swap(pixels, count); // ILI9341 pixel bytes are big-endian.
    ESP_ERROR_CHECK(
        esp_lcd_panel_draw_bitmap(panel, area->x1, area->y1, area->x2 + 1, area->y2 + 1, pixels));
}

void wait_flush(lv_display_t*) {
    // All LVGL calls stay on one task. The SPI ISR only signals DMA completion.
    // Never reuse a buffer while hardware still reads it or hang indefinitely.
    ESP_ERROR_CHECK(xSemaphoreTake(dma_done, pdMS_TO_TICKS(1000)) == pdTRUE ? ESP_OK
                                                                            : ESP_ERR_TIMEOUT);
}

void read_touch(lv_indev_t* input, lv_indev_data_t* data) {
    std::array<std::uint8_t, 5> report{};
    constexpr std::uint8_t first_register = 0x02;
    const auto status = i2c_master_transmit_receive(touch_device, &first_register, 1, report.data(),
                                                    report.size(), 20);
    // Poll even when INT is high: a missed IRQ must not leave a button pressed.
    apply_touch_sample(input, data, decoder.read(report, status == ESP_OK));
}

[[maybe_unused]] esp_err_t start_touch() {
    gpio_config_t reset{};
    reset.pin_bit_mask = 1ULL << es3c28p::touch_reset;
    reset.mode = GPIO_MODE_OUTPUT;
    ESP_RETURN_ON_ERROR(gpio_set_level(pin(es3c28p::touch_reset), 0), tag, "touch reset low");
    ESP_RETURN_ON_ERROR(gpio_config(&reset), tag, "touch reset pin");
    vTaskDelay(pdMS_TO_TICKS(20));
    ESP_RETURN_ON_ERROR(gpio_set_level(pin(es3c28p::touch_reset), 1), tag, "touch reset high");
    // FT6336G datasheet: at least 300 ms before reporting after reset.
    vTaskDelay(pdMS_TO_TICKS(500));
    gpio_config_t irq{};
    irq.pin_bit_mask = 1ULL << es3c28p::touch_interrupt;
    irq.mode = GPIO_MODE_INPUT;
    ESP_RETURN_ON_ERROR(gpio_config(&irq), tag, "touch interrupt pin");
    i2c_master_bus_config_t bus{};
    bus.i2c_port = I2C_NUM_0;
    bus.sda_io_num = pin(es3c28p::touch_sda);
    bus.scl_io_num = pin(es3c28p::touch_scl);
    bus.clk_source = I2C_CLK_SRC_DEFAULT;
    bus.glitch_ignore_cnt = 7;
    bus.flags.enable_internal_pullup = true;
    ESP_RETURN_ON_ERROR(i2c_new_master_bus(&bus, &touch_bus), tag, "I2C bus");
    i2c_device_config_t device{};
    device.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    device.device_address = es3c28p::touch_address;
    device.scl_speed_hz = 100000;
    ESP_RETURN_ON_ERROR(i2c_master_bus_add_device(touch_bus, &device, &touch_device), tag,
                        "touch device");
    // A real register read also validates devices that do not acknowledge an
    // address-only probe. Retry boundedly after reset; never fabricate touch.
    constexpr std::uint8_t chip_id_register = 0xa3;
    std::uint8_t chip_id = 0;
    esp_err_t touch_status = ESP_FAIL;
    for (unsigned attempt = 0; attempt < 3; ++attempt) {
        touch_status =
            i2c_master_transmit_receive(touch_device, &chip_id_register, 1, &chip_id, 1, 100);
        if (touch_status == ESP_OK)
            break;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    if (touch_status != ESP_OK) {
        ESP_LOGE(
            tag, "Touch read failed at 0x%02x (SDA=%d SCL=%d RST=%d). Scanning configured I2C bus:",
            es3c28p::touch_address, es3c28p::touch_sda, es3c28p::touch_scl, es3c28p::touch_reset);
        for (unsigned address = 0x08; address < 0x78; ++address)
            if (i2c_master_probe(touch_bus, address, 20) == ESP_OK)
                ESP_LOGW(tag, "I2C device responded at 0x%02x", address);
        return touch_status;
    }
    ESP_LOGI(tag, "FT6336G register read OK; chip ID 0x%02x", chip_id);
    auto* input = lv_indev_create();
    ESP_RETURN_ON_FALSE(input, ESP_ERR_NO_MEM, tag, "LVGL input");
    lv_indev_set_type(input, LV_INDEV_TYPE_POINTER);
    lv_indev_set_display(input, screen);
    lv_indev_set_read_cb(input, read_touch);
    lv_timer_set_period(lv_indev_get_read_timer(input), 16);
    return ESP_OK;
}
} // namespace

esp_err_t start() {
    if (started)
        return ESP_ERR_INVALID_STATE;
    started = true;
    ESP_RETURN_ON_ERROR(safe_pins(), tag, "safe pin setup");
    std::uint32_t flash_size = 0;
    ESP_RETURN_ON_ERROR(esp_flash_get_size(nullptr, &flash_size), tag, "flash size");
    ESP_RETURN_ON_FALSE(flash_size >= es3c28p::flash_bytes &&
                            esp_psram_get_size() >= es3c28p::psram_bytes,
                        ESP_ERR_INVALID_SIZE, tag, "Expected 16 MB flash and 8 MB PSRAM");
    dma_done = xSemaphoreCreateBinary();
    ESP_RETURN_ON_FALSE(dma_done, ESP_ERR_NO_MEM, tag, "DMA semaphore");
    spi_bus_config_t bus{};
    bus.sclk_io_num = es3c28p::lcd_sck;
    bus.mosi_io_num = es3c28p::lcd_mosi;
    bus.miso_io_num = es3c28p::lcd_miso;
    bus.quadwp_io_num = -1;
    bus.quadhd_io_num = -1;
    bus.max_transfer_sz = es3c28p::draw_buffer_bytes;
    ESP_RETURN_ON_ERROR(spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO), tag, "LCD SPI");
    esp_lcd_panel_io_spi_config_t io{};
    io.cs_gpio_num = es3c28p::lcd_cs;
    io.dc_gpio_num = es3c28p::lcd_dc;
    io.spi_mode = 0;
    io.pclk_hz = 20000000;
    io.trans_queue_depth = 2;
    io.lcd_cmd_bits = 8;
    io.lcd_param_bits = 8;
    io.on_color_trans_done = color_done;
    ESP_RETURN_ON_ERROR(esp_lcd_new_panel_io_spi(SPI2_HOST, &io, &panel_io), tag, "LCD IO");
    esp_lcd_panel_dev_config_t config{};
    config.reset_gpio_num = es3c28p::lcd_reset;
    config.rgb_ele_order = LCD_RGB_ELEMENT_ORDER_BGR;
    config.bits_per_pixel = 16;
    config.vendor_config = const_cast<ili9341_vendor_config_t*>(&airlock_lcd_config);
    ESP_RETURN_ON_ERROR(esp_lcd_new_panel_ili9341(panel_io, &config, &panel), tag, "ILI9341V");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_reset(panel), tag, "LCD reset");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_init(panel), tag, "LCD init");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_swap_xy(panel, true), tag, "landscape axes");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_mirror(panel, true, false), tag, "landscape orientation");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(panel, true), tag, "LCD display on");

    lv_init();
    lv_tick_set_cb(
        []() -> std::uint32_t { return static_cast<std::uint32_t>(esp_timer_get_time() / 1000); });
    screen = lv_display_create(es3c28p::width, es3c28p::height);
    ESP_RETURN_ON_FALSE(screen, ESP_ERR_NO_MEM, tag, "LVGL display");
    auto* first =
        heap_caps_malloc(es3c28p::draw_buffer_bytes, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    auto* second =
        heap_caps_malloc(es3c28p::draw_buffer_bytes, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    ESP_RETURN_ON_FALSE(first && second, ESP_ERR_NO_MEM, tag, "LCD draw buffers");
    lv_display_set_color_format(screen, LV_COLOR_FORMAT_RGB565);
    lv_display_set_buffers(screen, first, second, es3c28p::draw_buffer_bytes,
                           LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(screen, flush);
    lv_display_set_flush_wait_cb(screen, wait_flush);
#ifdef AIRLOCK_BOARD_NO_TOUCH
    ESP_LOGW(tag, "ES3N28P validation image: touch absent by configuration; use noninteractive USB "
                  "agent control");
#else
    ESP_RETURN_ON_ERROR(start_touch(), tag, "touch init");
#endif
    ESP_LOGI(tag, "LCD initialized; physical orientation still requires board validation");
    return ESP_OK;
}

lv_display_t* display() {
    return screen;
}
esp_err_t backlight(bool on) {
    if (on && panel_io) {
        // Fence pending pixel transfers without changing the panel registers.
        ESP_RETURN_ON_ERROR(esp_lcd_panel_io_tx_param(panel_io, -1, nullptr, 0), tag, "LCD fence");
    }
    return gpio_set_level(pin(es3c28p::lcd_backlight), on ? 1 : 0);
}
} // namespace airlock::board
