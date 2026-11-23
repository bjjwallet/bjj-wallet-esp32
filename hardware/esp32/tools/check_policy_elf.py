#!/usr/bin/env python3
"""Check the linked ESP32 ELF, not only a build flag or unreferenced source file."""

import argparse
import pathlib
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--nm", required=True)
parser.add_argument("--usb-power-only", action="store_true")
parser.add_argument("elf", type=pathlib.Path)
args = parser.parse_args()
policy = (
    pathlib.Path(__file__).resolve().parents[1] / "idf_components/airlock_policy/CMakeLists.txt"
)
block = re.search(r"set\(AIRLOCK_FORBIDDEN_ENTRY_POINTS\s+([^)]*)\)", policy.read_text())
if block is None:
    raise SystemExit("Cannot read mandatory peripheral policy")
entries = block.group(1).split()
if len(entries) < 10:
    raise SystemExit("Incomplete peripheral policy")
listing = subprocess.check_output(
    [args.nm, "--defined-only", "--extern-only", str(args.elf)], text=True
)
symbols = {line.split()[-1] for line in listing.splitlines() if line.split()}
missing = [name for name in entries if "__wrap_" + name not in symbols]
unblocked = [name for name in entries if name in symbols]
if missing or unblocked:
    raise SystemExit(
        f"Peripheral policy failed: missing denials={missing}, unblocked SDK functions={unblocked}"
    )
if args.usb_power_only:
    usb_drivers = symbols.intersection(
        {"usb_serial_jtag_driver_install", "usb_new_phy", "tinyusb_driver_install", "tusb_init"}
    )
    if "bjj_usb_power_only_init" not in symbols or usb_drivers:
        raise SystemExit(
            f"USB power-only policy failed: init missing or USB drivers linked: {sorted(usb_drivers)}"
        )
    print("ELF USB policy passed: disconnect initializer linked, no USB application driver.")
print(
    f"ELF policy passed: {len(entries)} Wi-Fi/microSD denials retained, no original entry points linked."
)
