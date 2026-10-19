"""Structural/static checks only. This does not parse, compile or typecheck Kotlin."""
import collections, json, pathlib, re, subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]

def mask(text):
    result=list(text); i=0
    while i<len(text):
        start=i
        if text.startswith("//",i):
            i=text.find("\n",i)
            if i<0: i=len(text)
        elif text.startswith("/*",i):
            level=1; i+=2
            while i<len(text) and level:
                if text.startswith("/*",i): level+=1; i+=2
                elif text.startswith("*/",i): level-=1; i+=2
                else: i+=1
        elif text.startswith('"""',i):
            end=text.find('"""',i+3); i=len(text) if end<0 else end+3
        elif text[i] in ('"',"'"):
            quote=text[i]; i+=1
            while i<len(text):
                if text[i]=="\\": i+=2
                elif text[i]==quote: i+=1; break
                else: i+=1
        else: i+=1; continue
        for index in range(start,min(i,len(text))):
            if result[index]!="\n": result[index]=" "
    return "".join(result)

errors=[]
files=list((ROOT/"app/src").rglob("*.kt"))
for file in files:
    text=file.read_text(encoding="utf-8-sig"); clean=mask(text); stack=[]
    for index,char in enumerate(clean):
        if char in "({[": stack.append((char,index))
        elif char in ")}]":
            if not stack or stack[-1][0]!="({["[")}]".index(char)]:
                errors.append(f"{file.relative_to(ROOT)}:{text[:index].count(chr(10))+1}: mismatched {char}"); break
            stack.pop()
    else:
        if stack: errors.append(f"{file.relative_to(ROOT)}: unclosed delimiters")
db=(ROOT/"app/src/main/java/com/qq/closie/life/data/database/LifeDatabase.kt").read_text()
registered=re.findall(r"(\w+)::class",db.split("entities = [",1)[1].split("]",1)[0])
assert len(registered)==len(set(registered)),"Duplicate Room entity registration"
spec=json.loads((ROOT/"engineering/v16/schema-v5.json").read_text())
assert all(t["cls"] in registered for t in spec["tables"])
recovery=(ROOT/"app/src/main/java/com/qq/closie/data/backup/FinanceBackupRecovery.kt").read_text()
assert recovery.count("FinanceV16BackupRecovery.restore(")==1, "Restore must advance epoch once"
for version in range(1,5):
    changed=subprocess.check_output(["git","diff","245e1475cd1d47a4205ab49f75d5e06d22de701c","--",
        f"app/schemas/com.qq.closie.life.data.database.LifeDatabase/{version}.json"],cwd=ROOT)
    assert not changed,"Published schema changed"
result={"check":"static-structure-only","status":"PASS" if not errors else "FAIL",
        "kotlinFilesScanned":len(files),"registeredEntities":len(registered),"errors":errors,
        "compilation":"UNVERIFIED"}
(ROOT/"engineering/v16/static-result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps(result,ensure_ascii=False))
raise SystemExit(1 if errors else 0)
