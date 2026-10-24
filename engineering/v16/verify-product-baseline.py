"""Preserve every reviewed a86841a invocation without changing the older fixed CI contract."""
import argparse
import json
import pathlib
import sys
import xml.etree.ElementTree as ET
from v16_test_contract import baseline_rows, source_methods, git

BASELINE = "a86841afebd8d29947c0433a7cbfdcd3f15e8317"

def missing_methods(required, actual):
    return sorted(set(required) - set(actual))

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--junit",type=pathlib.Path)
    parser.add_argument("--review-round2",action="store_true")
    parser.add_argument("--review-round3",action="store_true")
    args=parser.parse_args()
    root=pathlib.Path(__file__).resolve().parents[2]
    baseline="e4d079ae72a209775b859dbb021c1b685aff7ff5" if args.review_round2 else BASELINE
    minimum=717 if args.review_round2 else 681
    if args.review_round3:
        baseline="9575acfb3c52371dd7347f8020a2500ff4ba6095"
        minimum=735
    rows=baseline_rows(root,baseline)
    required={(row["className"],method) for row in rows for method in row["tests"]}
    errors=[]
    if len(required)!=minimum:
        errors.append("Reviewed baseline invocation count changed")
    current=set()
    for row in rows:
        if not row["tests"]:
            continue
        path=root/row["file"]
        if not path.is_file():
            errors.append("Missing baseline test file: "+row["file"])
            continue
        parsed,_=source_methods(row["file"],path.read_bytes())
        current.update((parsed["className"],method) for method in parsed["tests"])
    for cls,method in missing_methods(required,current):
        errors.append("Removed baseline test: "+cls+"#"+method)
    executed=set()
    if args.junit:
        reports=list(args.junit.rglob("TEST-*.xml"))
        if not reports:
            errors.append("No actual JUnit report files")
        for report in reports:
            suite=ET.parse(report).getroot()
            for case in suite.iter("testcase"):
                key=(case.get("classname"),case.get("name"))
                if key in required:
                    if any(child.tag in ("failure","error","skipped") for child in case):
                        errors.append("Baseline test did not pass: "+"#".join(key))
                    executed.add(key)
        for cls,method in missing_methods(required,executed):
            errors.append("Not executed: "+cls+"#"+method)
    result={"check":"review-round3-baseline" if args.review_round3 else "review-round2-baseline" if args.review_round2 else "a86841a-product-baseline","status":"FAIL" if errors else "PASS",
        "baseline":baseline,"commit":git(root,"rev-parse","HEAD").strip(),
        "requiredInvocations":len(required),"preservedSourceInvocations":len(required & current),
        "executedRequiredInvocations":len(required & executed) if args.junit else None,
        "execution":"ACTUAL JUNIT" if args.junit else "SOURCE ONLY","errors":errors}
    print(json.dumps(result,ensure_ascii=False))
    return bool(errors)

if __name__=="__main__":
    sys.exit(main())
