"""Audit V16 changes and reject deletion/reduction of the fixed source-method test floor."""
import hashlib, json, pathlib, re, subprocess
from v16_test_contract import CONTRACT, CONTRACT_SHA256, R2, MINIMUM_BASE, git, read_contract, require_floor, require_history, source_methods

ROOT = pathlib.Path(__file__).resolve().parents[2]

def audit(root, base=R2, minimum_hash=CONTRACT_SHA256):
    rows, errors, bypasses, deleted = [], [], [], []
    minimum = {"files": []}
    try:
        require_history(root, base)
        minimum, _ = read_contract(root)
        errors.extend(require_floor(minimum, minimum_hash))
        changed = set(git(root, "diff", "--name-only", base, "--", "app/src/test").splitlines())
        changed.update(git(root, "ls-files", "--others", "--exclude-standard", "--", "app/src/test").splitlines())
        original = set(git(root, "ls-tree", "-r", "--name-only", base, "--", "app/src/test").splitlines())
        tracked = set(git(root, "ls-files", "--", "app/src/test").splitlines())
        removed = set(git(root, "diff", "--name-only", "--diff-filter=D", base, "--", "app/src/test").splitlines())
        minimum_files = {row["file"]: row for row in minimum["files"]}
        removed.update(set(minimum_files) - tracked)
        for relative in sorted(changed | original | set(minimum_files)):
            if not relative.endswith(".kt"):
                continue
            file = root / relative
            if relative in removed or not file.is_file():
                deleted.append(relative)
                errors.append(f"Deleted/missing existing test source: {relative}")
                continue
            raw = file.read_bytes()
            row, code = source_methods(relative, raw)
            old = minimum_files.get(relative)
            if old:
                missing = sorted(set(old["tests"]) - set(row["tests"]))
                if old["className"] != row["className"] or missing:
                    errors.append(f"Minimum test methods missing/renamed in {relative}: {missing}")
            if relative not in changed:
                continue
            bypass = re.findall(r"@(?:[\w.]+\.)?(?:Ignore|Disabled)\b|\b(?:Assume|Assumptions)\.|\bassumeTrue\(|return@runTest\b", code)
            if bypass:
                bypasses.append({"file": relative, "bypass": bypass})
                errors.append(f"Test bypass in {relative}: {bypass}")
            rows.append({**row, "sha256": hashlib.sha256(raw).hexdigest(),
                         "assertionCalls": len(re.findall(r"\b(?:assert\w+|fail)\s*\(", code)),
                         "realRoom": "FinanceRoomTest()" in code or "Room.inMemoryDatabaseBuilder" in code or "MigrationTestHelper" in code,
                         "runtime": "UNVERIFIED", "bypass": bypass})
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        errors.append(f"Test audit/history/contract unavailable: {error}")
    return {"check": "V16-test-source-inventory", "status": "FAIL" if errors else "PASS", "comparisonBase": base,
            "minimumBase": MINIMUM_BASE, "minimumContract": CONTRACT, "minimumContractSha256": minimum_hash,
            "minimumTestFiles": len(minimum["files"]), "minimumTestMethods": sum(len(row["tests"]) for row in minimum["files"]),
            "testFiles": len(rows), "testMethods": sum(len(row["tests"]) for row in rows),
            "deletedFiles": deleted, "errors": errors, "bypassFindings": bypasses, "files": rows,
            "semanticAudit": "See TEST-AUDIT.md; fixed floor cannot be reduced by regenerating this inventory", "execution": "UNVERIFIED"}

if __name__ == "__main__":
    result = audit(ROOT)
    output = ROOT / "engineering/v16/review-fix-evidence/test-inventory.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, ensure_ascii=False))
    raise SystemExit(result["status"] != "PASS")
