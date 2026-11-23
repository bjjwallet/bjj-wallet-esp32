#!/usr/bin/env python3
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools"))
from board_agent import encode_command


class AgentProtocol(unittest.TestCase):
    def test_commands(self):
        for command in ["tela", "ok", "back", "cancel", "click 2", "texto Public test", "fim"]:
            self.assertEqual(encode_command(command), ("agent " + command + "\n").encode())

    def test_keyboard_and_injection_rejected(self):
        for command in [
            "1",
            "key confirm",
            "type 61",
            "click 0",
            "click -1",
            "click ٢",
            "texto a\nok",
            "texto a\0",
            "texto " + "a" * 1024,
        ]:
            with self.assertRaises(ValueError):
                encode_command(command)


if __name__ == "__main__":
    unittest.main()
