"""Inventory all V16 new/modified Kotlin tests. Structural audit only, never a test execution claim."""
import hashlib, json, pathlib, re, subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = "245e1475cd1d47a4205ab49f75d5e06d22de701c"
def names(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, encoding="utf-8").splitlines()

paths = set(names("diff", "--name-only", BASE, "--", "app/src/test")) | set(names("ls-files", "--others", "--exclude-standard", "--", "app/src/test"))
rows, errors = [], []
for relative in sorted(paths):
    file = ROOT / relative
    if file.suffix != ".kt" or not file.is_file():
        continue
    raw = file.read_bytes()
    source = raw.decode("utf-8-sig")
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    tests = re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)", code)
    bypass = re.findall(r"@(?:Ignore|Disabled)\b|\bAssume\.|\bassumeTrue\(|return@runTest\b", code)
    if bypass:
        errors.append({"file": relative, "bypass": bypass})
    rows.append({"file": relative, "sha256": hashlib.sha256(raw).hexdigest(), "tests": tests,
                 "assertionCalls": len(re.findall(r"\b(?:assert\w+|fail)\s*\(", code)),
                 "realRoom": "FinanceRoomTest()" in code or "Room.inMemoryDatabaseBuilder" in code or "MigrationTestHelper" in code,
                 "runtime": "UNVERIFIED", "bypass": bypass})
result = {"check": "V16-test-source-inventory", "comparisonBase": BASE, "testFiles": len(rows),
          "testMethods": sum(len(row["tests"]) for row in rows), "bypassFindings": errors, "files": rows,
          "semanticAudit": "See TEST-AUDIT.md; counts and flags cannot prove test correctness", "execution": "UNVERIFIED"}
output = ROOT / "engineering/v16/review-fix-evidence/test-inventory.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))
raise SystemExit(bool(errors))
