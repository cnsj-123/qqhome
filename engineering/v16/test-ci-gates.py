"""Offline regression for the tracked-HEAD byte gate, using a disposable synthetic text repository."""
import importlib.util, json, os, pathlib, shutil, stat, subprocess, unittest, uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("room_gate", pathlib.Path(__file__).with_name("verify-room-schema.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
report_spec=importlib.util.spec_from_file_location("report_gate",pathlib.Path(__file__).with_name("verify-ci-tests.py"))
report_gate=importlib.util.module_from_spec(report_spec)
report_spec.loader.exec_module(report_gate)

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
        inventory=self.root / "engineering/v16/review-fix-evidence/test-inventory.json"
        inventory.parent.mkdir(parents=True)
        inventory.write_text(json.dumps({"files":[{"file":"app/src/test/java/example/FixtureTest.kt","tests":["works"]}]}),encoding="utf-8")
        self.metric=self.root / "app/build/reports/v16/testDebugUnitTest/synthetic-history-2000.json"
        self.metric.parent.mkdir(parents=True)
        self.data={"status":"PASS","commitSha":self.commit,"activeEntries":2000,"netMinor":-294950,
                   **{key:1 for key in ("stageNanos","commitNanos","projectionAndFilterNanos","totalNanos","heapBeforeBytes","heapAfterBytes")}}
        self.metric.write_text(json.dumps(self.data),encoding="utf-8")

    def tearDown(self):
        remove_fixture(self.root)

    def test_complete_synthetic_report_is_accepted_by_report_parser(self):
        self.assertEqual("PASS",report_gate.verify(self.root,self.commit)["status"])

    def test_skipped_and_failed_cases_are_rejected(self):
        for tag in ("skipped","failure"):
            self.xml.write_text(f'<testsuite><testcase classname="example.FixtureTest" name="works"><{tag}/></testcase></testsuite>',encoding="utf-8")
            self.assertEqual("FAIL",report_gate.verify(self.root,self.commit)["status"])

    def test_wrong_commit_metrics_are_rejected(self):
        self.data["commitSha"]="b"*40
        self.metric.write_text(json.dumps(self.data),encoding="utf-8")
        self.assertEqual("FAIL",report_gate.verify(self.root,self.commit)["status"])

    def test_missing_metrics_and_missing_junit_are_rejected(self):
        self.metric.unlink()
        self.assertEqual("FAIL",report_gate.verify(self.root,self.commit)["status"])
        self.xml.unlink()
        self.assertEqual("FAIL",report_gate.verify(self.root,self.commit)["status"])

if __name__ == "__main__":
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromModule(__import__(__name__)))
    report=ROOT / "engineering/v16/review-fix-evidence/ci-gate-unit-tests.json"
    report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps({"check":"offline-engineering-ci-gates","status":"PASS" if result.wasSuccessful() else "FAIL",
        "cases":result.testsRun,"failures":len(result.failures),"errors":len(result.errors),
        "fixtures":"Synthetic text Git repository and synthetic JUnit documents; not Android execution", "androidTests":"UNVERIFIED"},indent=2)+"\n",encoding="utf-8")
    raise SystemExit(not result.wasSuccessful())
