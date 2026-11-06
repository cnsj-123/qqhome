"""Expose bounded synthetic Compose render evidence in CI logs, without downloading artifacts.

These are real production Composables on Robolectric's native Canvas, not device captures.
No user data, external screenshots, APKs, or media sources are read by this script.
"""
import base64
import hashlib
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / "app/build/reports/v16/robolectric-visuals"
NAMES = ("home-empty", "home-photos", "finance-accounts", "finance-ledger", "finance-receipt", "finance-entry", "finance-analysis", "settings-theme", "settings-color", "settings-privacy", "settings-permission")

for name in NAMES:
    path = DIRECTORY / (name + ".png")
    if not path.is_file():
        continue
    payload = path.read_bytes()
    if not payload.startswith(b"\x89PNG\r\n\x1a\n") or len(payload) > 1_000_000:
        raise ValueError("Invalid or unbounded native visual evidence: " + name)
    encoded = base64.b64encode(payload).decode("ascii")
    print("V16_NATIVE_VISUAL_BEGIN " + name + " " + hashlib.sha256(payload).hexdigest())
    for offset in range(0, len(encoded), 12000):
        print("V16_NATIVE_VISUAL_DATA " + name + " " + encoded[offset:offset + 12000])
    print("V16_NATIVE_VISUAL_END " + name)
