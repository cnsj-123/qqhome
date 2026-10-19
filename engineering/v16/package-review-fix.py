"""Export only the committed review fix after the Owner-pinned V16 HEAD. No remote actions."""
import argparse, hashlib, json, os, pathlib, subprocess
from v16_test_contract import CONTRACT_SHA256

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = "7d9fe46d4f489d50f72cfcff3e3b1b5dce02b319"

def git(*args, env=None):
    return subprocess.check_output(["git", *args], cwd=ROOT, env=env, encoding="utf-8", stderr=subprocess.PIPE).strip()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True, help="New sibling directory in this workspace")
    args=parser.parse_args()
    if git("status", "--porcelain"):
        raise RuntimeError("Commit all review-fix changes before exporting")
    head=git("rev-parse", "HEAD")
    base_tree=git("rev-parse",f"{BASE}^{{tree}}")
    branch=git("symbolic-ref", "--short", "HEAD")
    git("merge-base", "--is-ancestor", BASE, head)
    commits=git("rev-list", "--reverse", f"{BASE}..{head}").splitlines()
    if not commits:
        raise RuntimeError("No incremental commits after the pinned V16 HEAD")
    git("diff", "--check", BASE, head)
    out=args.output.resolve()
    if out.parent != ROOT.parent.resolve():
        raise ValueError("Output must be a new sibling directory within the workspace")
    out.mkdir(exist_ok=False)
    git("format-patch", "--binary", "--full-index", f"--base={BASE}", "--output-directory", str(out), f"{BASE}..{head}")
    patches=sorted(out.glob("*.patch"))
    if len(patches) != len(commits):
        raise RuntimeError("Patch count differs from incremental commit count")

    # Apply the real mail patches to a separate index, then compare the entire resulting tree.
    # The actual checkout and index are never reset or modified by this verification.
    index=out / ".validation.index"
    env=os.environ.copy()
    env["GIT_INDEX_FILE"]=str(index)
    try:
        git("read-tree", BASE, env=env)
        for patch in patches:
            git("apply", "--cached", "--check", str(patch), env=env)
            git("apply", "--cached", str(patch), env=env)
        expected=git("rev-parse", "HEAD^{tree}")
        actual=git("write-tree", env=env)
        if actual != expected:
            raise RuntimeError(f"Patch tree mismatch: {actual} != {expected}")
    finally:
        if index.exists():
            index.unlink()  # Only this newly created verification file, never the real Git index.
    if git("status", "--porcelain"):
        raise RuntimeError("Checkout changed during export; do not deliver this package")

    files=[{"file":p.name,"bytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest()} for p in patches]
    gate_result=json.loads((ROOT / "engineering/v16/review-fix-evidence/ci-gate-unit-tests.json").read_text(encoding="utf-8"))
    inventory=json.loads((ROOT / "engineering/v16/review-fix-evidence/test-inventory.json").read_text(encoding="utf-8"))
    manifest={
        "kind":"LOCAL_V16_REVIEW_FIX_INCREMENT", "repository":"cnsj-123/qqhome", "branch":branch,
        "base":BASE,"baseTree":base_tree,"head":head,"tree":expected,"commits":commits,"files":files,
        "changedFiles":git("diff","--name-status",BASE,head).splitlines(),
        "patchApplyAtBaseAndFullTreeComparison":"PASS",
        "reviewRound":2,"engineeringGateTests":gate_result,"testMinimumContractSha256":CONTRACT_SHA256,
        "historicalRoomSchemas":json.loads((ROOT / "engineering/v16/review-fix-evidence/room-history.json").read_text(encoding="utf-8")),
        "officialRoom5":"NOT_GENERATED_NOT_COMMITTED_BOOTSTRAP_REQUIRED_STRICT_GATE_FAIL",
        "testSourceInventory": {k:v for k,v in inventory.items() if k!="files"},
        "androidKspBuild":"UNVERIFIED","allKotlinRoomComposeTests":"UNVERIFIED",
        "synthetic2000ExecutionAndPerformance":"UNVERIFIED","actionsExecution":"UNVERIFIED",
        "deviceAcceptance":"DEVICE UNVERIFIED","finalIndependentReview":"AFTER_COMPLETE_CI_GREEN",
        "candidateApk":None,"remoteActionsExecuted":False,
        "handoff": {"issueFileMatrix":"engineering/v16/REVIEW-FIX-ROUND2.md","testAudit":"engineering/v16/TEST-AUDIT.md",
                    "workflow":".github/workflows/android.yml","ciBootstrapArtifacts":"engineering/v16/GITHUB-VALIDATION.md"}
    }
    (out / "manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    checksums="".join(f"{row['sha256']}  {row['file']}\n" for row in files)
    (out / "SHA256SUMS.txt").write_text(checksums,encoding="utf-8")
    (out / "README.md").write_text(
        f"# V16 Review Fix 第二轮增量交接\n\n本地代码SHA：`{head}`\n\n增量基线：`{BASE}`\n\n"
        f"完整代码树：`{expected}`\n\n"
        f"补丁应用前代码树：`{base_tree}`\n\n"
        "本包只含基线之后的本地修复format-patch，没有重复导出旧V16全量补丁。"
        "每个补丁SHA-256见SHA256SUMS.txt和manifest.json；已在独立Git index从指定基线应用全部补丁并逐树核对当前HEAD，通过。\n\n"
        "在干净独立checkout，先确认HEAD等于基线，或第一轮git am后仅元数据不同且HEAD代码树等于上述应用前代码树；核对补丁哈希，然后按文件序号执行 `git am <补丁文件>`。"
        "git am可能因提交者元数据产生新commit SHA；代码树应与上述tree一致，CI以实际推送SHA为准。"
        "本轮未push/PR/merge/发布/触发CI，交付后等待Owner授权推送。\n\n"
        f"问题与文件矩阵：engineering/v16/REVIEW-FIX-ROUND2.md；新增/修改测试逐项自审及{inventory['testFiles']}文件/{inventory['testMethods']}方法清单："
        "engineering/v16/TEST-AUDIT.md 和 review-fix-evidence/test-inventory.json；更新工作流：.github/workflows/android.yml；"
        "CI运行、Schema Bootstrap、预期artifact名称和检查方法：engineering/v16/GITHUB-VALIDATION.md。\n\n"
        f"本地工程门禁{gate_result['cases']}例通过，历史schema/结构/SQLite辅助检查通过。Android/KSP、全部Kotlin/Room/Compose测试、"
        "实际2000条指标、Actions执行和APK均UNVERIFIED；没有候选APK。官方5.json未生成/提交，严格门禁当前FAIL。"
        "首次Owner授权的功能分支CI先KSP并上传官方schema，缺提交版时保持红；原样归档5.json后再运行完整门禁。\n\n"
        "对账遵循Owner确认的保守证据边界：DATE按整日不确定区间判断，与边界重叠保持INCOMPLETE；精度进入算法3指纹。未知postedAt且无法证明范围外的同账户交易保持INCOMPLETE，"
        "不使用occurredAt或recordedAt排除。之后由Sol定位实际CI失败并交新的本地修复；完整CI通过后再最终独立Review，"
        "无阻断才集中一轮设备验收。厂商特性保持DEVICE UNVERIFIED。\n\n"
        "CI保留fetch-depth: 0并显式验证R2对象；最低测试契约为96文件/638方法，固定规范化SHA-256，不能靠重新生成动态清单降低。"
        "JUnit按完整类名及方法/显式参数调用名逐项匹配，重复、缺失、失败、跳过均FAIL。契约不依赖未推送的本地7d9fe46对象，"
        "git am更改提交者元数据不会破坏CI的历史依赖。\n",encoding="utf-8")
    print(json.dumps({"output":str(out),"head":head,"base":BASE,"patches":files,"fullTreeComparison":"PASS","remoteActionsExecuted":False},ensure_ascii=False))

if __name__ == "__main__":
    main()
