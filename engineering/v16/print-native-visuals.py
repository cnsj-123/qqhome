"""Expose bounded synthetic Android render evidence in CI logs, without downloading artifacts.

These are production Compose and native Views replayed by Robolectric's HardwareRenderer, not device captures.
No user data, external screenshots, APKs, or media sources are read by this script.
"""
import argparse
import base64
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / "app/build/reports/v16/robolectric-visuals"
NAMES = (
    "home-empty", "home-photos", "home-photo-viewer", "drawer-default", "drawer-bottom",
    "finance-accounts", "finance-ledger", "finance-ledger-empty", "finance-query", "finance-receipt",
    "finance-entry", "finance-saved-print", "finance-analysis", "settings-global", "settings-global-bottom",
    "settings-backup", "settings-backup-password", "settings-theme", "settings-color", "settings-privacy", "settings-permission",
    "finance-query-account-preview", "finance-query-account-applied",
    "finance-first-account-form", "finance-first-account-entry",
    "finance-print-visible-before", "finance-print-visible-flight", "finance-print-visible-landed",
    "external-receipt-main", "external-receipt-date-edit", "external-receipt-date-applied",
    "home-plan-canonical", "home-calendar-markers", "home-calendar-wheel",
    "home-photo-return-viewer-b", "home-photo-return-mid", "home-photo-return-stack-b",
)

parser = argparse.ArgumentParser()
parser.add_argument("--require-all", action="store_true")
if parser.parse_args().require_all:
    missing = [name for name in NAMES if not (DIRECTORY / (name + ".png")).is_file()]
    if missing:
        raise ValueError("Missing native visual evidence after successful tests: " + ", ".join(missing))

for name in NAMES:
    path = DIRECTORY / (name + ".png")
    if not path.is_file():
        continue
    payload = path.read_bytes()
    if not payload.startswith(b"\x89PNG\r\n\x1a\n") or len(payload) > 1_000_000:
        raise ValueError("Invalid or unbounded native visual evidence: " + name)
    encoded = base64.b64encode(payload).decode("ascii")
    attributes = DIRECTORY / (name + ".windows.json")
    if attributes.is_file():
        windows = json.loads(attributes.read_text(encoding="utf-8"))
        print("V16_NATIVE_WINDOWS " + name + " " + json.dumps(windows, separators=(",", ":")))
    print("V16_NATIVE_VISUAL_BEGIN " + name + " " + hashlib.sha256(payload).hexdigest())
    for offset in range(0, len(encoded), 12000):
        print("V16_NATIVE_VISUAL_DATA " + name + " " + encoded[offset:offset + 12000])
    print("V16_NATIVE_VISUAL_END " + name)
