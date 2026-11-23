// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "esp_err.h"
#include "lvgl.h"

namespace airlock::board {
// Call exactly once from the UI task; subsequent LVGL calls use the same task.
// On failure the application must stop (no silent fallback without touch).
// ES3N28P uses an explicit AIRLOCK_BOARD_NO_TOUCH validation build with USB input.
esp_err_t start();
lv_display_t* display();
esp_err_t backlight(bool on);
} // namespace airlock::board
