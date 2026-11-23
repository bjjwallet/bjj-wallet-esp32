idf_component_register(SRCS "nimble_device.cpp"
    INCLUDE_DIRS "include" "../.."
    PRIV_REQUIRES airlock_core json bt nvs_flash esp_timer)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
