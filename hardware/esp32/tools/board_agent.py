#!/usr/bin/env python3
"""Noninteractive USB automation. No terminal keys, input loop or seed readback."""

import argparse
import sys
import time


def encode_command(command):
    fixed = {"tela", "ok", "back", "cancel", "rolar", "fim"}
    click = (
        command.startswith("click ")
        and command[6:].isascii()
        and command[6:].isdigit()
        and int(command[6:]) > 0
    )
    if command not in fixed and not click and not command.startswith("texto "):
        raise ValueError("Comando de automacao invalido")
    encoded = ("agent " + command).encode("utf-8")
    if len(encoded) > 1024 or any(c in command for c in "\r\n\0"):
        raise ValueError("Comando invalido ou longo demais")
    return encoded + b"\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port")
    parser.add_argument("--command", action="append", required=True)
    args = parser.parse_args()
    try:
        commands = [encode_command(command) for command in args.command]
    except ValueError as error:
        parser.error(str(error))
    import serial
    from serial.tools import list_ports

    port = args.port
    if not port:
        ports = [
            p.device
            for p in list_ports.comports()
            if p.vid == 0x303A and p.device.startswith("/dev/cu.")
        ]
        if len(ports) != 1:
            parser.error("ESP32 USB indisponivel ou ambigua; informe --port")
        port = ports[0]
    connection = serial.Serial(baudrate=115200, timeout=0.1, write_timeout=2, exclusive=True)
    connection.dtr = False
    connection.rts = False
    connection.port = port
    connection.open()
    try:
        # Opening USB can restart the board. Wait for initialization before
        # issuing a command, and keep the connection open for the whole batch.
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            data = connection.read(4096)
            if data:
                sys.stdout.buffer.write(data)
        for command in commands:
            connection.write(command)
            deadline = time.monotonic() + 3
            quiet = time.monotonic() + 1
            while time.monotonic() < min(deadline, quiet):
                data = connection.read(4096)
                if data:
                    sys.stdout.buffer.write(data)
                    quiet = time.monotonic() + 0.4
            sys.stdout.buffer.flush()
    finally:
        connection.close()


if __name__ == "__main__":
    main()
