"""Compare SQLite structure from committed KSP DDL with production v4 -> v5 DDL.

Local-only auxiliary check: android.yml does not invoke this script. It never replaces
the enforced official schema gate, KSP regeneration or Room migration tests.
"""
import json
import pathlib
import re
import sqlite3

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "app/schemas/com.qq.closie.life.data.database.LifeDatabase"


def create(db, schema):
    for entity in schema["database"]["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity["indices"]:
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))


def structure(db):
    result = {}
    for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").fetchall():
        # Names come only from the trusted exported schema and production migration.
        quoted = '"' + table.replace('"', '""') + '"'
        columns = db.execute(f"PRAGMA table_xinfo({quoted})").fetchall()
        foreign = sorted(tuple(row[2:]) for row in db.execute(f"PRAGMA foreign_key_list({quoted})"))
        indices = []
        for row in db.execute(f"PRAGMA index_list({quoted})").fetchall():
            index = '"' + row[1].replace('"', '""') + '"'
            indices.append((row[1], row[2], row[3], row[4], db.execute(f"PRAGMA index_xinfo({index})").fetchall()))
        result[table] = (columns, foreign, sorted(indices))
    return result


def verify():
    with sqlite3.connect(":memory:") as migrated, sqlite3.connect(":memory:") as official:
        create(migrated, json.loads((SCHEMAS / "4.json").read_bytes()))
        source = (ROOT / "app/src/main/java/com/qq/closie/life/data/database/FinanceV16Migration.kt").read_text(encoding="utf-8")
        statements = re.findall(r'db\.execSQL\("""(.*?)"""\)', source, re.S)
        statements += re.findall(r'db\.execSQL\("([^"\n]*)"\)', source)
        assert len(statements) > 39, "Production migration extraction is incomplete"
        for sql in statements:
            migrated.execute(sql)
        create(official, json.loads((SCHEMAS / "5.json").read_bytes()))
        actual, expected = structure(migrated), structure(official)
        assert set(actual) == set(expected), "Official/migrated tables differ"
        differences = [table for table in expected if actual[table] != expected[table]]
        assert not differences, f"Official/migrated column, PK, FK or index structure differs: {differences}"
        return {"check": "official-ksp-ddl-vs-production-migration-sqlite", "status": "PASS",
                "tables": len(expected), "compared": ["columns", "types", "nullability", "defaults", "primaryKeys", "foreignKeys", "indices"],
                "kspRegenerationAndRoomRuntime": "UNVERIFIED"}


if __name__ == "__main__":
    print(json.dumps(verify(), ensure_ascii=False))
