cmake_minimum_required(VERSION 3.20)
set(IDF_TARGET esp32s3 CACHE STRING "ES3C28P SoC")
set(PROJECT_VER "2026.11.21-allassets1" CACHE STRING "Firmware release version" FORCE)
set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/../idf_components")
set(COMPONENTS main)
option(AIRLOCK_BOARD_NO_TOUCH "ES3N28P board validation without a touch controller" OFF)
option(AIRLOCK_BOARD_AGENT_CONTROL "Noninteractive USB agent control for board validation" OFF)
option(AIRLOCK_USB_POWER_ONLY "Disconnect USB data when the wallet application starts" ON)
if(AIRLOCK_USB_POWER_ONLY AND AIRLOCK_BOARD_AGENT_CONTROL)
    message(FATAL_ERROR "USB power-only firmware cannot expose agent control")
endif()
option(AIRLOCK_SETUP_SELFTEST "Exercise only an isolated public-fixture NVS namespace at boot" OFF)
option(AIRLOCK_MONERO_STAGENET_SYNC "Enable approved Monero stagenet sync over BLE" OFF)
if(AIRLOCK_BOARD_NO_TOUCH AND NOT AIRLOCK_BOARD_AGENT_CONTROL)
    message(FATAL_ERROR "The no-touch validation image requires agent control")
endif()
# LVGL stringifies this macro itself; embedding quotes makes an invalid filename.
add_compile_definitions(LV_CONF_PATH=${CMAKE_CURRENT_LIST_DIR}/../ui/lv_conf.h)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(airlock_board_check)
if(AIRLOCK_USB_POWER_ONLY AND NOT CONFIG_ESP_CONSOLE_NONE)
    message(FATAL_ERROR "USB power-only firmware requires CONFIG_ESP_CONSOLE_NONE=y")
endif()
if(AIRLOCK_USB_POWER_ONLY AND CONFIG_USJ_ENABLE_USB_SERIAL_JTAG)
    message(FATAL_ERROR "USB power-only firmware requires CONFIG_USJ_ENABLE_USB_SERIAL_JTAG disabled")
endif()

# Refuse to package a receive menu that differs from the mobile catalog.
add_custom_target(airlock_check_catalog ALL
    COMMAND "${PYTHON}" "${CMAKE_CURRENT_LIST_DIR}/../tools/generate_address_catalog.py" --check
    COMMAND "${PYTHON}" "${CMAKE_CURRENT_LIST_DIR}/../tools/generate_signing_catalog.py" --check
    VERBATIM)
add_dependencies(${CMAKE_PROJECT_NAME}.elf airlock_check_catalog)

# An image with any unblocked forbidden entry point is not a successful build.
set(AIRLOCK_USB_AUDIT_ARGS)
if(AIRLOCK_USB_POWER_ONLY)
    list(APPEND AIRLOCK_USB_AUDIT_ARGS --usb-power-only)
endif()
add_custom_target(airlock_check_policy ALL
    COMMAND "${PYTHON}" "${CMAKE_CURRENT_LIST_DIR}/../tools/check_policy_elf.py"
        --nm "${CMAKE_NM}" ${AIRLOCK_USB_AUDIT_ARGS} "$<TARGET_FILE:${CMAKE_PROJECT_NAME}.elf>"
    DEPENDS ${CMAKE_PROJECT_NAME}.elf VERBATIM)
