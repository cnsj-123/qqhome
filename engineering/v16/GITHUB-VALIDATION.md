# V16 GitHub 验证交接（2026-10-19，尚未执行）

Owner 要求本机不下载 JDK/SDK/Gradle/模拟器，后续由 GitHub 验证。本文件不授权 push、PR、merge 或发布；本地未触发任何远端运行。

Android 基线为 `245e1475cd1d47a4205ab49f75d5e06d22de701c`，文档依据为独立文档分支 `60bdce7361649c47699c2a328a0cc1ecd746b1b5`。核对交接包 manifest 的 HEAD、补丁 SHA-256 和本地分支，不与文档分支混合历史。当前交接仍属施工 checkpoint，所有 40 项均未完成整体验收，没有候选 APK。

现有 `.github/workflows/android.yml` 已按以下顺序准备。仅在 Owner 后续自行授权的 GitHub 环境运行；本机不执行 Gradle。

1. 记录 `git rev-parse HEAD`、JDK/Gradle/SDK 实际版本。使用工程现有 JDK 17、Gradle wrapper、compileSdk 35 配置。
2. `python3 engineering/v16/check-source.py` 与 `python3 engineering/v16/check-migration.py`。这是结构/SQLite辅助证据，不替代 Room/Kotlin。
3. `./gradlew :app:kspDebugKotlin --stacktrace`。修复所有实际 Kotlin/KSP 错误，由 KSP 生成 `app/schemas/com.qq.closie.life.data.database.LifeDatabase/5.json`；不得手写此官方 schema。已发布 1–4.json 与基线必须完全一致。
4. `./gradlew :app:testDebugUnitTest --stacktrace`，完整执行，保留 XML 和 HTML 报告。任何 failure、ignored 或超时都按实际记录，禁止删测试或改弱断言使其变绿。
5. 上述通过后执行 `./gradlew :app:assembleDebug --stacktrace`，保留 APK、SHA-256、applicationId、versionCode、versionName 和对应 HEAD。
6. 对唯一修复后 SHA 做完整独立审查，修复确定问题，再执行受影响及必要完整回归。独立 Reviewer 本轮因使用额度未完成复查，上轮静态意见不能自动覆盖当前 HEAD。
7. 全部本机可替代的验证通过后，统一使用 `OWNER-ONE-ROUND.md` 做一轮设备验证。七宿主、vivo/IME/Back/旋转/锁屏/撤权和性能不能由 Robolectric 结果冒充通过。

重点回归位置：

| 范围 | 已准备的真实用例 |
|---|---|
| G0/D1–D5 | FinanceV16ContractTest（含正债务起点/还款不反转/超额/新起点） |
| 迁移 | FinanceV5MigrationTest：已发布1–4→5，有数据、旧ID/anchor；原3/4迁移用例继续保留 |
| 草稿/生命周期 | FinanceEconomicDraftV16Test、FinanceWorkspaceV16Test、FinanceReviewSessionV16Test |
| 预计/商品 | FinanceExpectedTasksV16Test、FinancePurchaseAllocationV16Test、FinanceInsightsV16Test |
| 导入 | FinanceImportRepositoryTest：后段真实失败整批回滚、空备注、绑定/附件后撤销、去重与原ID |
| 备份/隐私 | EncryptedBackupEnvelopeTest、EncryptedBackupManagerV16Test、FinancePurposeReadV16Test、FinanceShareV16Test及原Restore/Backup全套 |
| 来源/OCR/附件 | FinanceProvenanceV16Test、FinanceMediaV16Test、FinanceLocalOcrParserTest、HostDetailAdaptersTest及原采集测试 |
| Compose | FinanceSmokeTest（含真实还款两腿与净额）、LifeSwipeOwnershipTest、FinanceFilterStateV16Test |

本地现有可复核证据：`static-result.json`（403 Kotlin 文件/75实体，结构检查）、`migration-sqlite-result.json`（旧27表/新48表/外键/3故障回滚）。这些文件没有 Kotlin、Room、Compose、Android 或设备 PASS。

不接真实云、不发送真实数据。若需补充现场记录，仅用新建合成账本和不含私密信息的所选测试图。目标机不存在某个宿主或没有安全测试形态时记录 NOT_TESTED，不填写虚构 App 版本。
