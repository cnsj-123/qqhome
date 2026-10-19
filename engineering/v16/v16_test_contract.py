"""Fixed source-method floor and explicit JUnit names; no inferred parameter suffix stripping."""
import hashlib, json, pathlib, re, subprocess

R2 = "245e1475cd1d47a4205ab49f75d5e06d22de701c"
MINIMUM_BASE = "78950821129f522d17359a7ec3bca2232c120601"
CONTRACT = "engineering/v16/test-minimum-contract.json"
CONTRACT_SHA256 = "303247088a925db57f864fd154b3279aad3716290eab9ecdc567a8909ad5f9cc"

def contract_digest(data):
    canonical=json.dumps(data,ensure_ascii=False,sort_keys=True,separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()

def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, encoding="utf-8", stderr=subprocess.PIPE)

def require_history(root, revision):
    git(root, "cat-file", "-e", f"{revision}^{{commit}}")
    return revision

def source_methods(relative, raw):
    source = raw.decode("utf-8-sig")
    # Keep Kotlin backtick identifiers intact for exact JUnit method-name matching.
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    names = re.findall(r"@Test(?:\([^)]*\))?\s+(?:fun)\s+(`[^`]+`|\w+)", code)
    names = [name.strip("`") for name in names]
    if len(names) != len(re.findall(r"@Test\b", code)) or len(names) != len(set(names)):
        raise ValueError(f"Unparsed or duplicate @Test method in {relative}")
    package = re.search(r"^\s*package\s+([\w.]+)", code, re.M)
    stem = pathlib.PurePosixPath(relative).stem
    if names and (package is None or not re.search(r"\bclass\s+" + re.escape(stem) + r"\b", code)):
        raise ValueError(f"Test class must have an explicit package and match its filename: {relative}")
    return {"file": relative, "className": f"{package.group(1)}.{stem}" if package else stem, "tests": names}, code

def baseline_rows(root, revision):
    require_history(root, revision)
    paths = git(root, "ls-tree", "-r", "--name-only", revision, "--", "app/src/test").splitlines()
    return [source_methods(path, git(root, "show", f"{revision}:{path}").encode("utf-8"))[0]
            for path in paths if path.endswith(".kt")]

def read_contract(root):
    data = json.loads((root / CONTRACT).read_text(encoding="utf-8"))
    if data.get("version") != 1 or not isinstance(data.get("files"), list) or not data["files"]:
        raise ValueError("Missing fixed minimum test contract")
    expected, files = {}, set()
    for row in data["files"]:
        file, cls, methods = row["file"], row["className"], row["tests"]
        if file in files or not file.startswith("app/src/test/java/") or ".." in pathlib.PurePosixPath(file).parts:
            raise ValueError(f"Duplicate or invalid minimum file: {file}")
        files.add(file)
        if not cls or not isinstance(methods, list) or len(methods) != len(set(methods)):
            raise ValueError(f"Invalid method contract: {file}")
        parameters = row.get("parameterized", {})
        if set(parameters) - set(methods):
            raise ValueError(f"Parameterized mapping has no corresponding source method: {file}")
        for method in methods:
            # For parameterized runners, enumerate EVERY required JUnit invocation by exact name.
            # E.g. methodA -> [methodA[0], methodA[1]]. Never use arbitrary prefix/regex matching.
            reports = parameters.get(method, [method])
            if not method or not isinstance(reports, list) or not reports or any(not isinstance(x, str) or not x for x in reports):
                raise ValueError(f"Invalid JUnit names for {cls}#{method}")
            for name in reports:
                key = (cls, name)
                if key in expected:
                    raise ValueError(f"Duplicate JUnit requirement: {cls}#{name}")
                expected[key] = method
    return data, expected

def require_floor(data, expected_hash=CONTRACT_SHA256):
    """Pin the reviewed floor independently of the regenerated inventory and git-am commit metadata.

    Updating this contract requires an explicit, reviewable digest change; CI never regenerates it.
    Only R2 must be reachable remotely. The local delivery SHA is provenance, not a remote dependency.
    """
    return [] if contract_digest(data)==expected_hash else ["Fixed minimum test contract digest changed; do not lower or regenerate the reviewed floor"]
