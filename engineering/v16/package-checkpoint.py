"""Package a clean local checkpoint. Never pushes, starts CI or changes the real index/worktree."""
import argparse, hashlib, json, os, pathlib, subprocess, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = "245e1475cd1d47a4205ab49f75d5e06d22de701c"
def git(*args, env=None, capture=False):
    return subprocess.run(["git", *args], cwd=ROOT, env=env, check=True,
                          stdout=subprocess.PIPE if capture else None, text=capture, encoding="utf-8" if capture else None)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=pathlib.Path, required=True, help="New directory; existing paths are never overwritten")
args = parser.parse_args()
assert not git("status", "--porcelain", capture=True).stdout.strip(), "Commit the checkpoint before packaging"
head = git("rev-parse", "HEAD", capture=True).stdout.strip()
branch = git("symbolic-ref", "--short", "HEAD", capture=True).stdout.strip()
git("merge-base", "--is-ancestor", BASE, head)
out = args.output.resolve()
out.mkdir(parents=False, exist_ok=False)
patch = out / "v16-from-r2.patch"
bundle = out / "v16-checkpoint.bundle"
git("diff", "--binary", "--full-index", f"--output={patch}", BASE, head)
git("bundle", "create", str(bundle), f"{BASE}..{branch}")
git("bundle", "verify", str(bundle))

# A private index proves forward application against the exact base, without checking it out.
with tempfile.TemporaryDirectory(prefix="patch-check-", dir=out) as scratch:
    env = os.environ.copy()
    env["GIT_INDEX_FILE"] = str(pathlib.Path(scratch) / "index")
    git("read-tree", BASE, env=env)
    git("apply", "--cached", "--check", str(patch), env=env)
git("apply", "--reverse", "--check", str(patch))

files = [{"file": file.name, "bytes": file.stat().st_size, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}
         for file in (patch, bundle)]
manifest = {
    "kind": "IN_PROGRESS_LOCAL_CHECKPOINT", "repository": "cnsj-123/qqhome", "branch": branch,
    "base": BASE, "head": head, "docsReference": "60bdce7361649c47699c2a328a0cc1ecd746b1b5",
    "commits": git("rev-list", "--reverse", f"{BASE}..{head}", capture=True).stdout.splitlines(),
    "files": files, "patchForwardCheckAtBase": "PASS", "patchReverseCheckAtHead": "PASS", "bundleVerify": "PASS",
    "structuralEvidence": json.loads((ROOT / "engineering/v16/static-result.json").read_text(encoding="utf-8-sig")),
    "sqliteEvidence": json.loads((ROOT / "engineering/v16/migration-sqlite-result.json").read_text(encoding="utf-8-sig")),
    "androidCompilation": "UNVERIFIED", "kotlinRoomComposeTests": "UNVERIFIED", "deviceAcceptance": "UNVERIFIED",
    "finalIndependentReview": "INCOMPLETE_REVIEWER_USAGE_LIMIT", "acceptedFeatures": "0/40", "candidateApk": None,
    "remoteActionsExecuted": False,
}
(out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(out / "README.md").write_text(
    f"# V16 本地施工 checkpoint\n\nHEAD：`{head}`\n\n基线：`{BASE}`\n\n"
    "这是未完成整体验收的本地代码包，不是已验证候选版；没有 APK。两个Git补丁方向检查与bundle验证已执行，"
    "不代表编译、测试或设备通过。完整状态及SHA-256见manifest.json。\n\n"
    "在单独且干净的R2基线上，用git apply --check检查补丁后再应用；或在已有基线对象的独立仓库中导入bundle。"
    "不要覆盖已有本地修改。没有push、PR、merge或发布授权。\n\n"
    "接手后先读工程内engineering/v16/PROGRESS.md、GITHUB-VALIDATION.md、feature-matrix.md和OWNER-ONE-ROUND.md。"
    "先KSP生成官方5.json、全套测试、构建、最终独立审查，再统一设备验收。本机按Owner要求未下载工具链。\n",
    encoding="utf-8")
print(json.dumps({"output": str(out), "head": head, "files": files, "patchChecks": "PASS", "compilation": "UNVERIFIED"}, ensure_ascii=False))
