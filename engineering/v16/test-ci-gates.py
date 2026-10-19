"""Offline schema-byte, exact JUnit identity and fixed minimum-contract regressions."""
import importlib.util, json, os, pathlib, shutil, stat, subprocess, unittest, uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("room_gate", pathlib.Path(__file__).with_name("verify-room-schema.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
report_spec=importlib.util.spec_from_file_location("report_gate",pathlib.Path(__file__).with_name("verify-ci-tests.py"))
report_gate=importlib.util.module_from_spec(report_spec)
report_spec.loader.exec_module(report_gate)
audit_spec=importlib.util.spec_from_file_location("source_audit",pathlib.Path(__file__).with_name("audit-v16-tests.py"))
source_audit=importlib.util.module_from_spec(audit_spec)
audit_spec.loader.exec_module(source_audit)
from v16_test_contract import CONTRACT, contract_digest, source_methods

def remove_fixture(root):
    resolved=root.resolve()
    assert resolved.parent == (ROOT / "engineering/v16").resolve() and resolved.name.startswith(".ci-gate-test-")
    def readonly_object(function, path, error):
        candidate=pathlib.Path(path).resolve()
        assert candidate.is_relative_to(resolved)
        if not isinstance(error[1], PermissionError):
            raise error[1]
        # Git deliberately writes read-only loose objects. Windows requires clearing that flag
        # before deleting our own synthetic repository; test assertions are never suppressed.
        os.chmod(candidate, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
        function(path)
    shutil.rmtree(resolved, onerror=readonly_object)

class CommittedByteGateTest(unittest.TestCase):
    def setUp(self):
        self.root = ROOT / "engineering/v16" / (".ci-gate-test-" + uuid.uuid4().hex)
        self.root.mkdir()
        self.git("init", "--quiet")
        self.file = self.root / "sample.txt"  # This is not an official Room schema or a fabricated 5.json.
        self.file.write_bytes(b"committed\n")
        self.git("-c", "core.autocrlf=false", "add", "sample.txt")
        self.commit()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def commit(self):
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@local.invalid", "commit", "--quiet", "-m", "synthetic text")

    def tearDown(self):
        remove_fixture(self.root)

    def test_identical_tracked_and_committed_bytes_pass(self):
        self.assertEqual(b"committed\n", gate.committed_file(self.root, "sample.txt"))

    def test_existing_but_untracked_is_rejected(self):
        (self.root / "untracked.txt").write_bytes(b"not committed\n")
        with self.assertRaises(subprocess.CalledProcessError):
            gate.committed_file(self.root, "untracked.txt")

    def test_staged_new_file_without_head_is_rejected(self):
        (self.root / "staged.txt").write_bytes(b"staged only\n")
        self.git("add", "staged.txt")
        with self.assertRaises(subprocess.CalledProcessError):
            gate.committed_file(self.root, "staged.txt")

    def test_regenerated_change_and_staged_change_are_rejected(self):
        self.file.write_bytes(b"regenerated difference\n")
        with self.assertRaises(ValueError):
            gate.committed_file(self.root, "sample.txt")
        self.git("add", "sample.txt")
        with self.assertRaises(ValueError):
            gate.committed_file(self.root, "sample.txt")

    def test_missing_generated_file_is_rejected(self):
        self.file.unlink()
        with self.assertRaises(FileNotFoundError):
            gate.committed_file(self.root, "sample.txt")

class ExecutedReportGateTest(unittest.TestCase):
    def setUp(self):
        self.root=ROOT / "engineering/v16" / (".ci-gate-test-reports-"+uuid.uuid4().hex)
        self.root.mkdir()
        self.commit="a"*40
        self.xml=self.root / "app/build/test-results/testDebugUnitTest/TEST-synthetic.xml"
        self.xml.parent.mkdir(parents=True)
        self.xml.write_text('<testsuite><testcase classname="example.FixtureTest" name="works"/></testsuite>',encoding="utf-8")
        self.row={"file":"app/src/test/java/example/FixtureTest.kt","className":"example.FixtureTest","tests":["works"]}
        self.requirements()
        self.metric=self.root / "app/build/reports/v16/testDebugUnitTest/synthetic-history-2000.json"
        self.metric.parent.mkdir(parents=True)
        self.data={"status":"PASS","commitSha":self.commit,"activeEntries":2000,"netMinor":-294950,
                   **{key:1 for key in ("stageNanos","commitNanos","projectionAndFilterNanos","totalNanos","heapBeforeBytes","heapAfterBytes")}}
        self.metric.write_text(json.dumps(self.data),encoding="utf-8")

    def tearDown(self):
        remove_fixture(self.root)

    def requirements(self):
        contract=self.root / CONTRACT
        contract.parent.mkdir(parents=True,exist_ok=True)
        data={"version":1,"files":[self.row]}
        self.contract_hash=contract_digest(data)
        contract.write_text(json.dumps(data),encoding="utf-8")
        inventory=self.root / "engineering/v16/review-fix-evidence/test-inventory.json"
        inventory.parent.mkdir(parents=True,exist_ok=True)
        inventory.write_text(json.dumps({"status":"PASS","deletedFiles":[],"errors":[],"files":[self.row]}),encoding="utf-8")

    def verify(self):
        return report_gate.verify(self.root,self.commit,self.contract_hash)

    def test_complete_synthetic_report_is_accepted_by_report_parser(self):
        self.assertEqual("PASS",self.verify()["status"])

    def test_skipped_and_failed_cases_are_rejected(self):
        for tag in ("skipped","failure","error"):
            self.xml.write_text(f'<testsuite><testcase classname="example.FixtureTest" name="works"><{tag}/></testcase></testsuite>',encoding="utf-8")
            self.assertEqual("FAIL",self.verify()["status"])

    def test_wrong_commit_metrics_are_rejected(self):
        self.data["commitSha"]="b"*40
        self.metric.write_text(json.dumps(self.data),encoding="utf-8")
        self.assertEqual("FAIL",self.verify()["status"])

    def test_missing_metrics_and_missing_junit_are_rejected(self):
        self.metric.unlink()
        missing_metric=self.verify()
        self.assertEqual("FAIL",missing_metric["status"])
        self.assertTrue(any("metrics unavailable" in error for error in missing_metric["errors"]))
        self.metric.write_text(json.dumps(self.data),encoding="utf-8")
        self.xml.unlink()
        missing_xml=self.verify()
        self.assertEqual("FAIL",missing_xml["status"])
        self.assertTrue(any("No executed JUnit" in error for error in missing_xml["errors"]))

    def test_repeating_method_a_twice_never_covers_method_b(self):
        self.row["tests"]=["methodA","methodB"];self.requirements()
        self.xml.write_text('<testsuite><testcase classname="example.FixtureTest" name="methodA"/><testcase classname="example.FixtureTest" name="methodA"/></testsuite>',encoding="utf-8")
        result=self.verify()
        self.assertEqual("FAIL",result["status"])
        self.assertTrue(any("#methodB" in error for error in result["errors"]))

    def test_same_method_on_wrong_full_class_does_not_cover_requirement(self):
        self.xml.write_text('<testsuite><testcase classname="elsewhere.FixtureTest" name="works"/></testsuite>',encoding="utf-8")
        self.assertEqual("FAIL",self.verify()["status"])

    def test_complete_distinct_methods_pass(self):
        self.row["tests"]=["methodA","methodB"];self.requirements()
        self.xml.write_text('<testsuite><testcase classname="example.FixtureTest" name="methodA"/><testcase classname="example.FixtureTest" name="methodB"/></testsuite>',encoding="utf-8")
        self.assertEqual("PASS",self.verify()["status"])

    def test_parameter_suffix_is_never_implicitly_stripped(self):
        self.xml.write_text('<testsuite><testcase classname="example.FixtureTest" name="works[0]"/></testsuite>',encoding="utf-8")
        self.assertEqual("FAIL",self.verify()["status"])

    def test_explicit_parameter_invocations_must_all_execute(self):
        self.row["parameterized"]={"works":["works[0]","works[1]"]};self.requirements()
        self.xml.write_text('<testsuite><testcase classname="example.FixtureTest" name="works[0]"/></testsuite>',encoding="utf-8")
        self.assertEqual("FAIL",self.verify()["status"])
        self.xml.write_text('<testsuite><testcase classname="example.FixtureTest" name="works[0]"/><testcase classname="example.FixtureTest" name="works[1]"/></testsuite>',encoding="utf-8")
        self.assertEqual("PASS",self.verify()["status"])

    def test_suite_level_errors_and_failed_source_audit_are_rejected(self):
        self.xml.write_text('<testsuite errors="1"><testcase classname="example.FixtureTest" name="works"/></testsuite>',encoding="utf-8")
        self.assertEqual("FAIL",self.verify()["status"])
        self.xml.write_text('<testsuite><testcase classname="example.FixtureTest" name="works"/></testsuite>',encoding="utf-8")
        inventory=self.root / "engineering/v16/review-fix-evidence/test-inventory.json"
        inventory.write_text(json.dumps({"status":"FAIL","files":[self.row]}),encoding="utf-8")
        self.assertEqual("FAIL",self.verify()["status"])

class SourceMinimumGateTest(unittest.TestCase):
    def setUp(self):
        self.root=ROOT / "engineering/v16" / (".ci-gate-test-source-"+uuid.uuid4().hex)
        self.root.mkdir()
        self.git("init","--quiet")
        self.relative="app/src/test/java/example/FixtureTest.kt"
        self.file=self.root / self.relative
        self.file.parent.mkdir(parents=True)
        self.source="package example\nclass FixtureTest {\n@Test fun methodA() {}\n@Test fun methodB() {}\n}\n"
        self.file.write_text(self.source,encoding="utf-8")
        self.git("add",self.relative)
        self.git("-c","user.name=Fixture","-c","user.email=fixture@local.invalid","commit","--quiet","-m","source floor")
        self.base=self.git("rev-parse","HEAD").strip()
        self.contract=self.root / CONTRACT
        self.contract.parent.mkdir(parents=True)
        self.data={"version":1,"files":[source_methods(self.relative,self.file.read_bytes())[0]]}
        self.contract.write_text(json.dumps(self.data),encoding="utf-8")
        self.contract_hash=contract_digest(self.data)

    def git(self,*args):
        return subprocess.check_output(["git",*args],cwd=self.root,encoding="utf-8",stderr=subprocess.PIPE)

    def tearDown(self):
        remove_fixture(self.root)

    def audit(self):
        return source_audit.audit(self.root,self.base,self.contract_hash)

    def test_unchanged_pinned_source_and_floor_pass(self):
        self.assertEqual("PASS",self.audit()["status"])

    def test_deleted_existing_test_is_reported_and_rejected(self):
        self.file.unlink()
        result=self.audit()
        self.assertEqual("FAIL",result["status"])
        self.assertIn(self.relative,result["deletedFiles"])

    def test_staged_deletion_is_rejected_even_if_untracked_file_remains(self):
        self.git("rm","--cached",self.relative)
        self.assertTrue(self.file.is_file())
        result=self.audit()
        self.assertEqual("FAIL",result["status"])
        self.assertIn(self.relative,result["deletedFiles"])

    def test_removed_method_does_not_lower_dynamic_inventory_floor(self):
        self.file.write_text(self.source.replace("@Test fun methodB() {}\n",""),encoding="utf-8")
        result=self.audit()
        self.assertEqual("FAIL",result["status"])
        self.assertTrue(any("methodB" in error for error in result["errors"]))

    def test_lowering_contract_along_with_source_still_violates_pinned_floor(self):
        self.file.write_text(self.source.replace("@Test fun methodB() {}\n",""),encoding="utf-8")
        self.data["files"][0]["tests"]=["methodA"]
        self.contract.write_text(json.dumps(self.data),encoding="utf-8")
        self.assertEqual("FAIL",self.audit()["status"])

    def test_new_and_modified_tests_are_still_inventoried(self):
        self.file.write_text(self.source.replace("@Test fun methodB() {}","@Test fun methodB() {}\n@Test fun methodC() {}"),encoding="utf-8")
        extra=self.file.with_name("AddedTest.kt")
        extra.write_text("package example\nclass AddedTest { @Test fun extra() {} }",encoding="utf-8")
        result=self.audit()
        self.assertEqual("PASS",result["status"])
        self.assertEqual(2,result["testFiles"]);self.assertEqual(4,result["testMethods"])

    def test_unavailable_git_history_fails_explicitly(self):
        result=source_audit.audit(self.root,"0"*40,self.contract_hash)
        self.assertEqual("FAIL",result["status"])
        self.assertTrue(any("history" in error for error in result["errors"]))

if __name__ == "__main__":
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromModule(__import__(__name__)))
    report=ROOT / "engineering/v16/review-fix-evidence/ci-gate-unit-tests.json"
    report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps({"check":"offline-engineering-ci-gates","status":"PASS" if result.wasSuccessful() else "FAIL",
        "cases":result.testsRun,"failures":len(result.failures),"errors":len(result.errors),
        "fixtures":"Synthetic text Git repository and synthetic JUnit documents; not Android execution", "androidTests":"UNVERIFIED"},indent=2)+"\n",encoding="utf-8")
    raise SystemExit(not result.wasSuccessful())
