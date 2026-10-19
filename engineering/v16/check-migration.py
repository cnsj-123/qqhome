"""Real SQLite exercise of the exact production v4 -> v5 DDL; not a Room/KSP substitute."""
import json, pathlib, re, sqlite3, time

ROOT = pathlib.Path(__file__).resolve().parents[2]
schema = json.loads((ROOT / "app/schemas/com.qq.closie.life.data.database.LifeDatabase/4.json").read_text(encoding="utf-8"))
source = (ROOT / "app/src/main/java/com/qq/closie/life/data/database/FinanceV16Migration.kt").read_text(encoding="utf-8")
statements = re.findall(r'db\.execSQL\("""(.*?)"""\)', source, re.S)
statements += re.findall(r'db\.execSQL\("([^"\n]*)"\)', source)
assert len(statements) > 39

def fixture():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for entity in schema["database"]["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    db.executescript("""
INSERT INTO finance_accounts(id,name,kind,currencyCode,openingBalanceMinor,createdAt,updatedAt,balanceAnchorAt)
VALUES('cash','合成旧现金','CASH','CNY',100000,100,200,100),
      ('credit','合成旧信用账户','CREDIT','CNY',-20000,100,200,150),
      ('usd','合成美元','OTHER','USD',999,100,100,100);
INSERT INTO finance_categories(id,name,parentId) VALUES('food','餐饮',NULL),('breakfast','早餐','food');
INSERT INTO finance_tags(id,name) VALUES('tag','合成标签');
INSERT INTO finance_entries(id,accountId,direction,amountMinor,description,occurredAt,recordedAt,updatedAt,categoryId,revision)
VALUES('old','cash','OUTFLOW',1234,'',99,200,201,'breakfast',4),
      ('edge','cash','OUTFLOW',1000,'合成边界',100,200,201,'breakfast',2),
      ('out','cash','OUTFLOW',2000,'合成转账',200,200,200,NULL,1),
      ('in','credit','INFLOW',2000,'合成转账',200,200,200,NULL,1),
      ('void','cash','INFLOW',88,'合成作废',300,300,300,NULL,2);
UPDATE finance_entries SET voidedAt=301 WHERE id='void';
INSERT INTO finance_transfers(id,outflowEntryId,inflowEntryId,recordedAt,updatedAt) VALUES('transfer','out','in',200,200);
INSERT INTO finance_entry_tags(entryId,tagId) VALUES('edge','tag');
INSERT INTO finance_events(id,description,nature,currencyCode,occurredAt,createdAt,updatedAt,personalShareMinor)
VALUES('event','合成经济事项','PERSONAL','CNY',100,200,200,1000);
INSERT INTO finance_event_entries(id,eventId,entryId,role,allocatedMinor,createdAt,updatedAt,revision)
VALUES('link','event','edge','PAYMENT',1000,200,200,1);
INSERT INTO finance_expected_flows(id,eventId,role,amountMinor,note,createdAt) VALUES('expected','event','REBATE',100,'合成预期',200);
""")
    db.commit()
    return db

def snapshot(db):
    return {e["tableName"]: db.execute('SELECT * FROM "'+e["tableName"]+'" ORDER BY rowid').fetchall()
            for e in schema["database"]["entities"]}

start = time.monotonic()
db = fixture()
before = snapshot(db)
db.execute("BEGIN")
for sql in statements:
    db.execute(sql)
db.commit()
assert before == snapshot(db), "Legacy values or identity changed"
assert not db.execute("PRAGMA foreign_key_check").fetchall()
assert db.execute("SELECT restoreEpoch FROM finance_control").fetchone() == (0,)
assert db.execute("SELECT id,isSelf FROM finance_parties").fetchone() == ("self",1)
tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
expected = json.loads((ROOT / "engineering/v16/schema-v5.json").read_text())["tables"]
assert all(t["name"] in tables for t in expected)
for table in expected:
    columns = {r[1] for r in db.execute('PRAGMA table_info("'+table["name"]+'")')}
    assert columns == {f["name"] for f in table["fields"]}, table["name"]
db.close()

for stop in [1, len(statements)//2, len(statements)-1]:
    db = fixture()
    original = snapshot(db)
    original_tables = db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
    db.execute("BEGIN")
    try:
        for i, sql in enumerate(statements):
            if i == stop:
                raise RuntimeError("synthetic migration failure")
            db.execute(sql)
        raise AssertionError("fault point missed")
    except RuntimeError:
        db.rollback()
    assert snapshot(db) == original
    assert db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall() == original_tables
    db.close()
result = {"check":"production-v4-to-v5-sqlite", "status":"PASS", "legacyTablesPreserved":len(before),
          "newTables":len(expected), "faultPoints":3, "elapsedSeconds":round(time.monotonic()-start,4),
          "roomKspAndAndroidRuntime":"UNVERIFIED"}
(ROOT / "engineering/v16/migration-sqlite-result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps(result,ensure_ascii=False))
