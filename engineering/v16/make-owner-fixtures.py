"""Deterministic synthetic CSV fixtures; no device, network or private workbook access."""
import csv, datetime, hashlib, json, pathlib, re

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent / "fixtures"
OUT.mkdir(exist_ok=True)
HEADERS = ["日期", "金额", "账本", "类型", "分类", "子分类", "备注", "标签", "账户", "转入账户",
           "债务人", "转账手续费", "是否为报销", "账单状态", "报销金额", "报销日期", "退款金额", "退款日期",
           "原价", "不计入预算", "不计入收支", "账单图片"]
reader = (ROOT / "app/src/main/java/com/qq/closie/life/finance/LegacyLedgerReader.kt").read_text(encoding="utf-8-sig")
declared = re.search(r"val headers = listOf\((.*?)\)", reader, re.S).group(1)
assert re.findall(r'"([^"]+)"', declared) == HEADERS, "Fixture header differs from the real reader"

def row(**values):
    result = {"日期": "2026-10-01 12:00:00", "金额": "12.34", "类型": "支出", "账本": "V16合成测试",
              "分类": "合成测试分类", "备注": "合成记录", "账户": "合成钱包"}
    result.update(values)
    return result

def write(name, rows):
    path = OUT / name
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, HEADERS, lineterminator="\n")
        writer.writeheader(); writer.writerows(rows)
    with path.open(encoding="utf-8-sig", newline="") as source:
        readback = list(csv.DictReader(source))
    assert readback == [{key: value.get(key, "") for key in HEADERS} for value in rows]
    return {"file": name, "dataRows": len(rows), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

duplicate = row(备注="合成重复候选")
small = [row(备注=""), duplicate, duplicate.copy(), row(金额="bad", 备注="合成非法金额"),
         row(类型="转账", 金额="20.00", 转入账户="合成银行", 转账手续费="0.29", 备注="合成转账及额外费用"),
         row(是否为报销="是", 报销金额="3.00", 备注="合成报销证据，不能制造到账"),
         row(退款金额="2.00", 备注="合成退款证据，不能制造到账"), row(金额="1.00", 备注="合成普通付款")]
large, totals = [], {"合成钱包": 0, "合成银行": 0}
for index in range(1, 2001):
    cents = 100 + index % 97
    account = "合成银行" if index % 5 == 0 else "合成钱包"
    at = datetime.datetime(2025, 1, 1, 12) + datetime.timedelta(hours=index)
    large.append(row(日期=at.strftime("%Y-%m-%d %H:%M:%S"), 金额=f"{cents // 100}.{cents % 100:02d}",
                     账户=account, 备注=f"合成压力记录{index:04d}", 标签="合成压力"))
    totals[account] -= cents

manifest = {"syntheticOnly": True, "devicePerformance": "UNVERIFIED",
            "files": [write("synthetic-review-8.csv", small), write("synthetic-history-2000.csv", large)],
            "historyExpectedAfterFullImportWithZeroOpening": {"activeEntries": 2000, "currency": "CNY",
                "netMinor": sum(totals.values()), "accountBalancesMinor": totals,
                "confirmedPersonalCostMinor": -sum(totals.values())}}
(OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(manifest, ensure_ascii=False))
