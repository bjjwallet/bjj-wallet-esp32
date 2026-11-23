idf_component_register(SRCS "board.cpp" "lcd_init.c"
    INCLUDE_DIRS "include" PRIV_INCLUDE_DIRS "."
    REQUIRES airlock_core lvgl
    PRIV_REQUIRES esp_driver_gpio esp_driver_spi esp_driver_i2c esp_lcd
        esp_lcd_ili9341 esp_timer esp_psram spi_flash)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
if(AIRLOCK_BOARD_NO_TOUCH)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE AIRLOCK_BOARD_NO_TOUCH=1)
endif()
