# Mandatory component in the firmware application. Header dependencies
# supply SDK types; the wrappers never initialize these peripherals.
idf_component_register(SRCS "peripheral_policy.cpp"
    INCLUDE_DIRS "../../hardware"
    PRIV_REQUIRES esp_wifi esp_driver_sdmmc esp_driver_sdspi sdmmc)

# Block calls from application AND linked board-support libraries. An upstream
# board example that tries to start Wi-Fi or mount an SD card receives an error.
set(AIRLOCK_FORBIDDEN_ENTRY_POINTS
    esp_wifi_init esp_wifi_start esp_wifi_connect esp_wifi_scan_start
    esp_wifi_set_promiscuous esp_wifi_80211_tx
    sdmmc_host_init sdspi_host_init sdspi_host_init_device sdmmc_card_init)
foreach(entry IN LISTS AIRLOCK_FORBIDDEN_ENTRY_POINTS)
    target_link_options(${COMPONENT_LIB} INTERFACE "-Wl,--wrap=${entry}")
    # Retain all denials so the ELF audit can verify the policy even if the
    # application currently makes no calls to a forbidden SDK entry point.
    target_link_options(${COMPONENT_LIB} INTERFACE "-Wl,--undefined=__wrap_${entry}")
endforeach()
