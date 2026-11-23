#!/usr/bin/env python3
"""Validate Compose XML catalogs and statically referenced string resources."""

import re
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "shared/src/commonMain/kotlin/pub/airlock"
RESOURCES = ROOT / "shared/src/commonMain/composeResources"
LANGUAGES = {"pt", "en", "zh", "es", "fr", "it", "de", "ja"}

catalogs = {}
for folder in RESOURCES.glob("values*"):
    code = "en" if folder.name == "values" else folder.name.removeprefix("values-")
    values = {}
    for path in folder.glob("*.xml"):
        for item in ET.parse(path).getroot():
            assert item.tag == "string", f"Unexpected resource type: {path}: {item.tag}"
            key = item.attrib["name"]
            assert re.fullmatch(r"[a-z][a-z0-9_]*", key), f"Invalid resource name: {key}"
            assert key not in values, f"Duplicate resource: {code}: {key}"
            values[key] = "".join(item.itertext())
    catalogs[code] = values

assert LANGUAGES <= set(catalogs)
reference = catalogs["en"]
for code, values in catalogs.items():
    # Selectable languages need complete catalogs. Partial catalogs use the default strings.
    assert set(values) <= set(reference), f"{code}: extra resources"
    if code in LANGUAGES:
        assert set(values) == set(reference), f"{code}: missing resources"
    if "app_name" in values:
        assert values["app_name"] == "BJJ-App-Wallet"
    for key, value in values.items():
        assert value.strip(), f"Empty string: {code}: {key}"
        placeholders = lambda text: Counter(re.findall(r"%\d+\$[sd]", text))
        assert placeholders(value) == placeholders(reference[key]), f"{code}: placeholders in {key}"
        assert not re.search(r"\{\w+\}", value), f"Legacy placeholder: {code}: {key}"

source = "\n".join(path.read_text() for path in SOURCE.rglob("*.kt"))
required = set(re.findall(r"Res\.string\.([a-z0-9_]+)", source))
assert required <= set(reference), f"Missing resources: {required - set(reference)}"
assert not list((RESOURCES / "files/i18n").glob("*.json")), "Packaged JSON catalogs must be removed"
assert (
    "LocalStrings" not in source and "translationKey" not in source
), "Legacy string lookup remains"
declared = set(re.findall(r'\("([a-z]{2})",', (SOURCE / "domain/AppLanguage.kt").read_text()))
assert declared == LANGUAGES
print(
    f"Compose XML translations OK: {len(LANGUAGES)} complete catalogs, {len(catalogs) - len(LANGUAGES)} partial; placeholders and references verified."
)
