set(AIRLOCK_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
set(AIRLOCK_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${AIRLOCK_GENERATED}")
file(GLOB AIRLOCK_LANGUAGE_CATALOGS "${AIRLOCK_ROOT}/ui/locales/*.json")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${AIRLOCK_LANGUAGE_CATALOGS}
    "${AIRLOCK_ROOT}/ui/translations.json"
    "${AIRLOCK_ROOT}/tools/generate_ui_strings.py"
    "${AIRLOCK_ROOT}/../ui/states_menu.py"
    "${AIRLOCK_ROOT}/../ui/states_wallet.py"
    "${AIRLOCK_ROOT}/../ui/states_ble.py"
    "${AIRLOCK_ROOT}/../ui/state_sign.py")
execute_process(COMMAND "${PYTHON}" "${AIRLOCK_ROOT}/tools/generate_ui_strings.py"
    "${AIRLOCK_ROOT}/.." "${AIRLOCK_GENERATED}/original_strings.hpp"
    RESULT_VARIABLE generation_status)
if(NOT generation_status EQUAL 0)
    message(FATAL_ERROR "Could not generate wallet UI translations")
endif()
idf_component_register(SRCS "../../ui/touch_view.cpp" "../../ui/lvgl_memory.cpp"
    "../../ui/wallet_app.cpp" "../../ui/first_login.cpp" "../../ui/assets/splash_image.c" "../../ui/fonts/airlock_chinese_16.c"
    INCLUDE_DIRS "../.." PRIV_INCLUDE_DIRS "${AIRLOCK_GENERATED}" REQUIRES airlock_core airlock_crypto lvgl)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
target_compile_definitions(${COMPONENT_LIB} PRIVATE LV_LVGL_H_INCLUDE_SIMPLE)
# LVGL calls back into our allocator. Pull its object into the final link even
# when static archive ordering would otherwise discard it before LVGL is read.
target_link_options(${COMPONENT_LIB} INTERFACE "-Wl,--undefined=lv_mem_init")
