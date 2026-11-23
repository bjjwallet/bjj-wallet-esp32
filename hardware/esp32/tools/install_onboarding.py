#!/usr/bin/env python3
"""Install the reviewed offline onboarding image; never erase wallet NVS."""

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
from serial.tools import list_ports

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--port", help="Porta USB da ESP32-S3; automatica se houver apenas uma")
args = parser.parse_args()
report = json.loads((root / "setup-flow-report.json").read_text())
build = root / report.get("build_directory", "build-crypto-board")
expected_files = {
    "bootloader/bootloader.bin",
    "partition_table/partition-table.bin",
    "airlock_board_check.bin",
}
if set(report["firmware_files_sha256"]) != expected_files:
    parser.error("Relatorio de firmware inesperado. Recompile e confira a imagem.")
for name, expected in report["firmware_files_sha256"].items():
    path = build / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        parser.error("A imagem compilada mudou ou esta ausente: " + name)
port = args.port
if not port:
    ports = [
        p.device
        for p in list_ports.comports()
        if p.vid == 0x303A and not p.device.startswith("/dev/tty.")
    ]
    if len(ports) != 1:
        parser.error("Conecte a placa USB-C ou informe --port. Feche outros monitores seriais.")
    port = ports[0]
print(
    "Instalando o fluxo inicial offline em " + port + ". Feche o console durante a gravacao.",
    flush=True,
)
try:
    subprocess.run(
        [
            sys.executable,
            "-m",
            "esptool",
            "--chip",
            "esp32s3",
            "--port",
            port,
            "--baud",
            "460800",
            "write_flash",
            "@flash_args",
        ],
        cwd=build,
        check=True,
    )
except subprocess.CalledProcessError as error:
    raise SystemExit(error.returncode)
print(
    "Gravacao concluida. A particao da carteira nao foi apagada. Solicite as proximas acoes pelo chat."
)
