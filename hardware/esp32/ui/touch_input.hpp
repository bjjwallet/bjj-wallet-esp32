// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "hardware/ft6336.hpp"
#include "lvgl.h"

namespace airlock {
// Called inside the LVGL input read callback. A bus error or ambiguous gesture
// cancels the active object; a synthetic release must never approve an action.
inline void apply_touch_sample(lv_indev_t* input, lv_indev_data_t* data, TouchSample sample) {
    if (sample.cancel) {
        lv_indev_reset(input, nullptr);
        lv_indev_wait_release(input);
    }
    data->state = sample.pressed ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
    if (sample.pressed) {
        data->point.x = sample.x;
        data->point.y = sample.y;
    }
    data->continue_reading = false;
}
} // namespace airlock
