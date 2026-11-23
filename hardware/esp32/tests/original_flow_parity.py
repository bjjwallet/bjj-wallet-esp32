#!/usr/bin/env python3
"""Execute unmodified original state methods, compare their visible flow to C++.

Python/Pygame are NOT a runtime dependency. Only rendering and wallet services
are test doubles. All decisions, validation, callbacks and retry destinations
come from the original classes, not a second hand-written expected-state model.
Public fixtures only; never reads or writes an actual wallet directory.
"""

import ast
import gc
import pathlib
import re
import subprocess
import sys
import types

root = pathlib.Path(__file__).resolve().parents[2]
ns = types.SimpleNamespace
pygame = ns(
    **{
        name: i
        for i, name in enumerate(
            ["K_RETURN", "K_KP_ENTER", "K_ESCAPE", "K_UP", "K_DOWN", "K_BACKSPACE"]
        )
    }
)


class State:
    def __init__(self, ctx):
        self.ctx, self.hw = ctx, ctx.hw

    def fill_screen(self):
        pass

    def draw_text(self, *args, **kwargs):
        pass


class MenuState(State):
    pass


package = types.ModuleType("parity_ui")
package.__path__ = []
sys.modules["parity_ui"] = package
module = types.ModuleType("parity_ui.states_menu")
module.MenuState = MenuState
sys.modules["parity_ui.states_menu"] = module
scope = dict(
    State=State,
    MenuState=MenuState,
    pygame=pygame,
    gc=gc,
    re=re,
    __package__="parity_ui",
    config=ns(
        PASSPHRASE_RULES=ns(
            min_len=16,
            require_upper=True,
            require_lower=True,
            require_digit=True,
            require_special=True,
        ),
        PASSWORD_RULES=ns(
            min_len=12,
            require_upper=True,
            require_lower=True,
            require_digit=True,
            require_special=True,
        ),
        MNEMONIC_LEN=24,
    ),
)
components = ast.parse((root / "ui/components.py").read_text())
input_class = next(
    n for n in components.body if isinstance(n, ast.ClassDef) and n.name == "InputState"
)
scroll_class = next(
    n for n in components.body if isinstance(n, ast.ClassDef) and n.name == "ScrollableTextState"
)
scroll_keydown = next(
    n for n in scroll_class.body if isinstance(n, ast.FunctionDef) and n.name == "on_keydown"
)
exec(
    compile(
        ast.Module(body=[input_class, scroll_keydown], type_ignores=[]),
        "original/ui/components.py",
        "exec",
    ),
    scope,
)


class ScrollableTextState(State):
    def __init__(self, ctx, lines, title=None, key_handlers=None, **kwargs):
        super().__init__(ctx)
        self.lines, self.title, self.key_handlers = lines, title, key_handlers or {}

    on_keydown = scope["on_keydown"]


scope["ScrollableTextState"] = ScrollableTextState
wallet = ast.parse((root / "ui/states_wallet.py").read_text())
wallet.body = [n for n in wallet.body if not isinstance(n, (ast.Import, ast.ImportFrom))]
exec(compile(wallet, "original/ui/states_wallet.py", "exec"), scope)


class Service:
    def __init__(self, present):
        self.present, self.fail = present, False

    def wallet_exists(self):
        return self.present

    def generate_mnemonic(self, **kwargs):
        self.present = not self.fail
        self.fail = False
        return ["abandon"] * 23 + ["art"] if self.present else None

    def import_wallet(self, mnemonic_list, **kwargs):
        self.present = not self.fail and mnemonic_list == ["abandon"] * (len(mnemonic_list) - 1) + [
            "about"
        ]
        self.fail = False
        return self.present


class Context:
    language = "en"
    hw = ns(render=lambda: None)

    def __init__(self, present=False):
        self.svc = Service(present)
        self.state = MenuState(self)

    def change_state(self, state):
        self.state = state
        if hasattr(state, "on_enter"):
            state.on_enter()


def record(ctx):
    s = ctx.state
    kind, text, hidden = "text", "", 0
    if isinstance(s, MenuState):
        kind = "menu"
    elif isinstance(s, scope["WordCountSelectorState"]):
        kind = "count"
    elif isinstance(s, scope["InputState"]):
        kind, text, hidden = "input", s.prompt, int(s.is_pwd)
    elif isinstance(s, scope["ReviewListState"]):
        kind, text = "backup", "\n".join(s.items[:-1])
    else:
        text = "\n".join(x for x in s.lines if not x.startswith(("[Enter]", "[Esc]", "[#]", "[*]")))
    return f"{kind}\t{int(ctx.svc.present)}\t{hidden}\t{text.rstrip(chr(10)).encode().hex()}"


phrase, password = "Public-Passphrase1!", "Public-Password1!"
steps = []


def add(action, value=None):
    steps.append(action + ("\t" + value if value is not None else ""))


def inp(value):
    add("input", value)


def conf():
    add("confirm")


def credentials():
    conf()
    inp(phrase)
    inp(phrase)
    conf()
    inp(password)
    inp(password)


# Error lists, mismatched confirmation restarts, same credentials checked only
# after confirmation, original Back destination, generation failure and retry.
add("reset")
add("create")
conf()
inp("bad")
conf()
inp(phrase)
inp("different")
conf()
inp(phrase)
inp(phrase)
add("cancel")
credentials()
add("cancel")  # Escape on backup intro returns to the menu, wallet already exists.
add("create")
conf()
conf()
inp(phrase)
inp(phrase)
conf()
inp(phrase)
inp(phrase)
conf()
inp(password)
inp("different")
conf()
inp(password)
add("fail")
inp(password)
conf()
credentials()
conf()
add("cancel")  # Backup list ignores Escape.
conf()
inp("wrong")
conf()
for _ in range(23):
    inp("\u00a0ABANDON\u3000")
inp("art")
inp("wrong")
conf()
inp(phrase)
inp("wrong")
conf()
inp(password)
conf()
# Import accepts a password without special chars, accepts nonempty misspelled
# words until final validation, preserves that password across retry, and clears
# the old active wallet when processing starts.
add("import")
conf()
add("count", "0")
conf()
inp("bad")
conf()
inp("PublicPassword1")
inp("different")
conf()
inp("PublicPassword1")
inp("PublicPassword1")
conf()
inp("  ")
conf()
inp("not-a-bip39-word-at-all")
for _ in range(11):
    inp("abandon")
conf()
inp("")
conf()
conf()
for _ in range(11):
    inp(" ABANDON ")
inp("about")
conf()
inp("")
conf()
# Every original word-count selection and empty/weak recovery passphrase.
for index, count in enumerate([12, 15, 18, 21, 24]):
    add("reset")
    add("import")
    add("count", str(index))
    conf()
    inp("PublicPassword1")
    inp("PublicPassword1")
    conf()
    for _ in range(count - 1):
        inp("abandon")
    inp("about")
    conf()
    inp("legacy phrase")
    conf()
    add("create")
    add("cancel")
    add("import")
    conf()
    add("cancel")

ctx = Context()
expected = []
for step in steps:
    action, _, value = step.partition("\t")
    if action == "reset":
        ctx = Context(value == "1")
    elif action == "fail":
        ctx.svc.fail = True
    elif action in ("create", "import"):
        assert isinstance(ctx.state, MenuState), (step, type(ctx.state).__name__)
        ctx.change_state(
            scope["CreateWalletState" if action == "create" else "ImportWalletState"](ctx)
        )
    elif action == "count":
        ctx.state.idx = int(value)
        ctx.state.on_keydown(ns(key=pygame.K_RETURN))
    elif action == "input":
        assert isinstance(ctx.state, scope["InputState"]), (step, type(ctx.state).__name__)
        ctx.state.text = value
        ctx.state.on_keydown(ns(key=pygame.K_RETURN))
    else:
        if action == "confirm" and isinstance(ctx.state, scope["ReviewListState"]):
            ctx.state.idx = len(ctx.state.items) - 1
        ctx.state.on_keydown(ns(key=pygame.K_ESCAPE if action == "cancel" else pygame.K_RETURN))
    expected.append(record(ctx))
result = subprocess.run(
    [sys.argv[1]], input="\n".join(steps) + "\n", text=True, capture_output=True, check=True
)
actual = result.stdout.splitlines()
assert len(actual) == len(expected), (len(actual), len(expected), result.stderr)
for i, (a, b) in enumerate(zip(actual, expected)):
    if a != b:

        def readable(line):
            fields = line.split("\t")
            fields[-1] = bytes.fromhex(fields[-1]).decode()
            return fields

        raise AssertionError(
            f"Public fixture step {i}: {steps[i]!r}\nC++: {readable(a)}\nOriginal: {readable(b)}"
        )
print(
    f"{len(steps)} original Python/C++ flow observations match: screens, inputs, errors, backup, retry, cancellation and save timing."
)
