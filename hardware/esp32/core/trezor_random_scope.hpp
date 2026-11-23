// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "core/crypto.hpp"
namespace airlock::crypto {
class TrezorRandomScope {
  public:
    explicit TrezorRandomScope(RandomSource& random);
    ~TrezorRandomScope();
    TrezorRandomScope(const TrezorRandomScope&) = delete;
    TrezorRandomScope& operator=(const TrezorRandomScope&) = delete;

  private:
    RandomSource* previous_;
};
} // namespace airlock::crypto
