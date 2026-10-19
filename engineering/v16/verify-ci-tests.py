"""Validate actual CI-produced JUnit XML and synthetic fixture results, never source-only coverage."""
import json, os, pathlib, xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]

def verify(root, commit):
    errors, cases = [], []
    reports = list((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    for file in reports:
        try:
            cases.extend(ET.parse(file).getroot().iter("testcase"))
        except (OSError, ET.ParseError) as error:
            errors.append(f"Unreadable JUnit report {file.name}: {error}")
    if not cases:
        errors.append("No executed JUnit testcases were found")
    failed = [case for case in cases if case.find("failure") is not None or case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    if failed or skipped:
        errors.append(f"JUnit contains {len(failed)} failed and {len(skipped)} skipped cases")
    try:
        inventory = json.loads((root / "engineering/v16/review-fix-evidence/test-inventory.json").read_text(encoding="utf-8"))
        actual = {}
        for case in cases:
            actual[case.get("classname")] = actual.get(case.get("classname"), 0) + 1
        for row in inventory["files"]:
            if not row["tests"]:
                continue
            name = row["file"].split("/java/", 1)[1][:-3].replace("/", ".")
            if actual.get(name, 0) < len(row["tests"]):
                errors.append(f"Missing executed cases for {name}: expected at least {len(row['tests'])}, found {actual.get(name, 0)}")
    except (OSError, ValueError, KeyError) as error:
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
            "junitFiles": len(reports), "testCases": len(cases), "failed": len(failed), "skipped": len(skipped), "errors": errors}

if __name__ == "__main__":
    result = verify(ROOT, os.environ.get("GITHUB_SHA"))
    output = ROOT / "engineering/v16/ci-logs/executed-tests-check.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(result["status"] != "PASS")
