// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
namespace airlock::hardware {
// Wi-Fi e SD/microSD desabilitados por decisão do projeto: a carteira opera
// totalmente offline. O armazenamento fica na flash interna; o Bluetooth serve
// apenas para trocar dados com o aplicativo, sem acesso à internet pela carteira.
enum class Peripheral { Bluetooth, Display, Touch, InternalFlash, Wifi, MicroSd };
constexpr bool allowed(Peripheral peripheral) {
    return peripheral != Peripheral::Wifi && peripheral != Peripheral::MicroSd;
}
static_assert(!allowed(Peripheral::Wifi));
static_assert(!allowed(Peripheral::MicroSd));
static_assert(allowed(Peripheral::Bluetooth));
static_assert(allowed(Peripheral::InternalFlash));
} // namespace airlock::hardware
