// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "ui/touch_view.hpp"

namespace airlock {
// Test-image adapter only: never link this into a production wallet firmware.
// Runs on the LVGL task and dispatches the real widget callbacks. No screen dump
// or reply includes textarea contents. One command per UI iteration.
class BoardSerialControl {
  public:
    std::string execute_agent(std::string_view request, TouchView& view);
    std::string execute(std::string_view command, TouchView& view);

  private:
    std::uint64_t screen_ = 0;
    lv_obj_t* focused_ = nullptr;
    void sync(TouchView& view);
    void focus(lv_obj_t* object);
    std::string key(std::string_view command, TouchView& view);
    std::string focus_description(TouchView& view) const;
};
} // namespace airlock
