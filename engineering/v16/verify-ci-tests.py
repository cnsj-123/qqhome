"""Validate actual CI-produced JUnit XML and synthetic fixture results, never source-only coverage."""
import collections, json, os, pathlib, xml.etree.ElementTree as ET
from v16_test_contract import CONTRACT_SHA256, read_contract, require_floor

ROOT = pathlib.Path(__file__).resolve().parents[2]

def verify(root, commit, minimum_hash=CONTRACT_SHA256):
    errors, cases, expected = [], [], {}
    actual = collections.Counter()
    reports = list((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    for file in reports:
        try:
            document = ET.parse(file).getroot()
            cases.extend(document.iter("testcase"))
            for suite in document.iter("testsuite"):
                if any(int(suite.get(key, "0")) > 0 for key in ("failures", "errors", "skipped")):
                    errors.append(f"JUnit suite reports failure/error/skip: {file.name}")
        except (OSError, ValueError, ET.ParseError) as error:
            errors.append(f"Unreadable JUnit report {file.name}: {error}")
    if not cases:
        errors.append("No executed JUnit testcases were found")
    failed = [case for case in cases if case.find("failure") is not None or case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    if failed or skipped:
        errors.append(f"JUnit contains {len(failed)} failed and {len(skipped)} skipped cases")
    try:
        minimum, expected = read_contract(root)
        errors.extend(require_floor(minimum,minimum_hash))
        inventory = json.loads((root / "engineering/v16/review-fix-evidence/test-inventory.json").read_text(encoding="utf-8"))
        if inventory.get("status") != "PASS" or inventory.get("deletedFiles") or inventory.get("errors"):
            errors.append("Source audit/fixed minimum test floor did not pass")
        mappings = {(row["className"], method): row.get("parameterized", {}).get(method, [method])
                    for row in minimum["files"] for method in row["tests"]}
        for row in inventory["files"]:
            for method in row["tests"]:
                for name in mappings.get((row["className"], method), [method]):
                    expected[(row["className"], name)] = method
        actual = collections.Counter((case.get("classname"), case.get("name")) for case in cases)
        for (cls, name), count in actual.items():
            if not cls or not name:
                errors.append("JUnit testcase has no complete class/method name")
            if count > 1:
                errors.append(f"Duplicate JUnit testcase cannot replace a missing method: {cls}#{name} ({count})")
        for cls, name in sorted(expected):
            if actual[(cls, name)] != 1:
                errors.append(f"Missing/duplicate required invocation: {cls}#{name}; found {actual[(cls, name)]}")
    except (OSError, ValueError, KeyError, TypeError) as error:
        errors.append(f"Test inventory unavailable: {error}")
    try:
        metric = json.loads((root / "app/build/reports/v16/testDebugUnitTest/synthetic-history-2000.json").read_text(encoding="utf-8"))
        if not commit or metric.get("commitSha") != commit:
            errors.append("Fixture metrics are not from this CI commit")
        if metric.get("status") != "PASS" or metric.get("activeEntries") != 2000 or metric.get("netMinor") != -294950:
            errors.append("Fixture results are missing, failed or inconsistent")
        for key in ("stageNanos", "commitNanos", "projectionAndFilterNanos", "totalNanos", "heapBeforeBytes", "heapAfterBytes"):
            if not isinstance(metric.get(key), int) or metric[key] < 0:
                errors.append(f"Missing measured fixture resource field: {key}")
    except (OSError, ValueError) as error:
        errors.append(f"Actual synthetic fixture metrics unavailable: {error}")
    return {"check": "actual-ci-junit-and-fixture", "status": "FAIL" if errors else "PASS", "commit": commit,
            "junitFiles": len(reports), "testCases": len(cases), "failed": len(failed), "skipped": len(skipped),
            "requiredInvocations": len(expected), "matchedRequiredInvocations": sum(actual[key]==1 for key in expected),
            "matchingRule": "Exact full classname and JUnit invocation name; parameters require explicit contract names",
            "errors": errors}

if __name__ == "__main__":
    result = verify(ROOT, os.environ.get("GITHUB_SHA"))
    output = ROOT / "engineering/v16/ci-logs/executed-tests-check.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(result["status"] != "PASS")
