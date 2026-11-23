#!/usr/bin/env python3
"""Flash only a reviewed application after protected backups and exact NVS readback checks."""

from pathlib import Path
import argparse, hashlib, importlib.util, json, os, struct, subprocess, sys, time
from datetime import datetime

parser = argparse.ArgumentParser()
parser.add_argument("--port", required=True)
parser.add_argument("--package", type=Path, required=True)
parser.add_argument("--backup-root", type=Path, required=True)
parser.add_argument("--dry-run", action="store_true")
args = parser.parse_args()
os.umask(0o077)
package = args.package.resolve()
firmware = package / "firmware"
spec = importlib.util.spec_from_file_location("airlock_installer", package / "instalar.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
installer.verify_files()
table = (firmware / installer.TABLE).read_bytes()
parts = {}
for i in range(0, len(table), 32):
    entry = table[i : i + 32]
    if len(entry) != 32 or entry[:2] != b"\xaa\x50":
        continue
    _, kind, subtype, offset, size, label, flags = struct.unpack("<HBBII16sI", entry)
    parts[label.split(b"\0", 1)[0].decode()] = {
        "offset": offset,
        "size": size,
        "type": kind,
        "subtype": subtype,
        "flags": flags,
    }
expected = {
    "nvs": (0x9000, 0x6000),
    "factory": (0x10000, 0x800000),
    "wallet_nvs": (0x810000, 0x20000),
}
if not all(k in parts and (parts[k]["offset"], parts[k]["size"]) == v for k, v in expected.items()):
    parser.error("Unexpected partition layout")
image = firmware / installer.APP
if not 0 < image.stat().st_size <= parts["factory"]["size"]:
    parser.error("Application does not fit the expected partition")
if args.dry_run:
    print(
        json.dumps(
            {
                "port": args.port,
                "write_offset": "0x10000",
                "image_bytes": image.stat().st_size,
                "protected_regions": expected,
                "expected_partition_layout_verified": True,
            },
            indent=2,
        )
    )
    sys.exit(0)
backup = args.backup_root.resolve() / datetime.now().strftime("%Y%m%d-%H%M%S")
backup.mkdir(parents=True, mode=0o700, exist_ok=False)
os.chmod(backup, 0o700)
report = {
    "started": datetime.now().isoformat(),
    "port": args.port,
    "backup_directory": str(backup),
    "image_sha256": hashlib.sha256(image.read_bytes()).hexdigest(),
    "write_offset": "0x10000",
    "flash_completed": False,
    "partitions_preserved": {},
    "efuse_changes": False,
}
report_file = backup / "report.json"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def save():
    report_file.write_text(json.dumps(report, indent=2) + "\n")


def event(message):
    print(message, flush=True)
    save()


def run(name, cmd):
    with (backup / (name + ".log")).open("w") as log:
        r = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT)
    if r.returncode:
        raise RuntimeError(f"{name} failed; see protected backup log")


base = [
    sys.executable,
    "-m",
    "esptool",
    "--chip",
    "esp32s3",
    "--port",
    args.port,
    "--baud",
    "921600",
    "--after",
    "no_reset",
]


def read(name, offset, size, first=False):
    target = backup / (name + ".bin")
    run(
        name,
        base
        + [
            "--before",
            "default_reset" if first else "no_reset",
            "read_flash",
            hex(offset),
            str(size),
            str(target),
        ],
    )
    return target


try:
    event("Reading and verifying the installed partition table")
    installed = read("partition-table-before", 0x8000, len(table), True)
    require(installed.read_bytes() == table, "Installed partition table differs: no write allowed")
    event("Backing up pairing/configuration NVS and encrypted wallets")
    before = {}
    for label in ["nvs", "wallet_nvs"]:
        p = parts[label]
        before[label] = read(label + "-before", p["offset"], p["size"])
    event("Backing up the current application for rollback")
    p = parts["factory"]
    read("application-before", p["offset"], p["size"])
    event("Writing only the new application at 0x10000")
    run("write-application", base + ["--before", "no_reset", "write_flash", "0x10000", str(image)])
    event("Reading back the new application and protected partitions")
    actual = read("application-after", 0x10000, image.stat().st_size)
    require(actual.read_bytes() == image.read_bytes(), "Application readback mismatch")
    for label in ["nvs", "wallet_nvs"]:
        p = parts[label]
        actual = read(label + "-after", p["offset"], p["size"])
        same = actual.read_bytes() == before[label].read_bytes()
        report["partitions_preserved"][label] = same
        save()
        require(same, f"{label} changed; keeping device in ROM recovery mode")
    actual = read("partition-table-after", 0x8000, len(table))
    require(actual.read_bytes() == table, "Partition table changed")
    report["partition_table_preserved"] = True
    report["flash_completed"] = True
    event("Flash verified; pairing NVS and encrypted wallets are byte-for-byte unchanged")
    installer.start_wallet(args.port)
    report["watchdog_restart_requested"] = True
    from serial.tools import list_ports

    report["usb_observations"] = []
    for _ in range(4):
        report["usb_observations"].append(
            [p.device for p in list_ports.comports() if p.vid == 0x303A]
        )
        save()
        time.sleep(2)
    event("Wallet started; USB power-only observations recorded")
except BaseException as e:
    report["error"] = str(e)
    save()
    raise
