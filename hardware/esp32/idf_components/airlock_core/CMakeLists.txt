set(AIRLOCK_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
set(AIRLOCK_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${AIRLOCK_GENERATED}")
execute_process(COMMAND "${PYTHON}" "${AIRLOCK_ROOT}/tools/generate_words.py"
    "${AIRLOCK_ROOT}/../words.txt" "${AIRLOCK_GENERATED}/wordlist.hpp"
    RESULT_VARIABLE generation_status)
if(NOT generation_status EQUAL 0)
    message(FATAL_ERROR "Could not generate original word list")
endif()
idf_component_register(SRCS
    "../../core/rules.cpp" "../../hardware/ble_framing.cpp"
    "../../core/public_wallet_rpc.cpp" "../../core/json_memory.cpp"
    "../../hardware/ft6336.cpp"
    "../../ui/states_wallet.cpp" "../../ui/state_sign.cpp"
    INCLUDE_DIRS "../.." PRIV_INCLUDE_DIRS "${AIRLOCK_GENERATED}" PRIV_REQUIRES json)
target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_17)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
