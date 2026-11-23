#!/usr/bin/env python3
"""Package only a touch release with the mandatory USB power-only policy."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile


def setting(text, name):
    for line in text.splitlines():
        if line.startswith(name + "=") or line.startswith(name + ":"):
            return line.split("=", 1)[1]
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("package", type=Path)
    args = parser.parse_args()
    build, package = args.build.resolve(), args.package.resolve()
    cache = (build / "CMakeCache.txt").read_text()
    required = {
        "AIRLOCK_USB_POWER_ONLY": "ON",
        "AIRLOCK_BOARD_AGENT_CONTROL": "OFF",
        "AIRLOCK_BOARD_NO_TOUCH": "OFF",
        "AIRLOCK_SETUP_SELFTEST": "OFF",
    }
    for key, value in required.items():
        if setting(cache, key) != value:
            parser.error(f"Refusing to package: {key} must be {value}")
    config = Path(setting(cache, "SDKCONFIG")).read_text()
    if setting(config, "CONFIG_ESP_CONSOLE_NONE") != "y":
        parser.error("Refusing to package a console-enabled image")
    if setting(config, "CONFIG_USJ_ENABLE_USB_SERIAL_JTAG") == "y":
        parser.error("Refusing to package an enabled USB Serial/JTAG module")
    if setting(config, "CONFIG_IDF_TARGET") != '"esp32s3"':
        parser.error("Expected ESP32-S3 firmware")
    subprocess.run(
        [
            sys.executable,
            str(Path(__file__).with_name("check_policy_elf.py")),
            "--nm",
            setting(cache, "CMAKE_NM"),
            "--usb-power-only",
            str(build / "airlock_board_check.elf"),
        ],
        check=True,
    )
    images = [
        "airlock_board_check.bin",
        "bootloader/bootloader.bin",
        "partition_table/partition-table.bin",
    ]
    destination = package / "firmware"
    for name in images:
        if not (build / name).is_file():
            parser.error(f"Missing build image: {name}")
    if not (package / "instalar.py").is_file():
        parser.error("Expected an existing installer package directory")
    digests = {}
    for name in images:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build / name, target)
        digests[name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (destination / "sha256.json").write_text(json.dumps(digests, indent=2) + "\n")
    metadata = {
        "target": "ESP32-S3 ES3C28P touch",
        "usb_power_only_during_application": True,
        "console": False,
        "usb_agent_control": False,
        "permanent_efuse_changes": False,
        "physical_boot_reset_recovery": True,
    }
    signer = setting(cache, "AIRLOCK_MONERO_SIGNER_ARCHIVE")
    if signer:
        signer = Path(signer)
        if not signer.is_file():
            parser.error("Monero signer archive is missing")
        symbols = subprocess.check_output(
            [setting(cache, "CMAKE_NM"), "--defined-only", str(build / "airlock_board_check.elf")],
            text=True,
        )
        if not any(
            line.split()[-1:] == ["airlock_monero_sign_archive"] for line in symbols.splitlines()
        ):
            parser.error("Monero signer is not linked in the final firmware")
        metadata["monero_signer_sha256"] = hashlib.sha256(signer.read_bytes()).hexdigest()
        metadata["monero_signer_linked"] = True
    metadata["monero_stagenet_sync"] = setting(cache, "AIRLOCK_MONERO_STAGENET_SYNC") == "ON"
    metadata["firmware_version"] = setting(cache, "PROJECT_VER")
    (destination / "release-policy.json").write_text(json.dumps(metadata, indent=2) + "\n")
    archive = package.with_suffix(".zip")
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(package.rglob("*")):
            if path.is_file() and not any(
                part in {"__pycache__", ".venv", ".DS_Store"}
                for part in path.relative_to(package).parts
            ):
                output.write(path, path.relative_to(package.parent))
    with zipfile.ZipFile(archive) as output:
        if output.testzip() is not None:
            raise RuntimeError("ZIP integrity check failed")
        for name, digest in digests.items():
            if hashlib.sha256(output.read(f"{package.name}/firmware/{name}")).hexdigest() != digest:
                raise RuntimeError("ZIP firmware hash mismatch")
    print(f"Packaged and verified: {archive}")


if __name__ == "__main__":
    main()
