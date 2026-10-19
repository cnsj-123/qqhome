# CI53 全测试源审计（静态）

唯一源基线 `e84897a946cd258c3df22e55855205618401b930`。逐文件读取并扫描当前 97 个 Kotlin 测试源（含 4 个辅助源），对照完整 CI53 XML 的 93 个测试类。原 660 方法全部保留，新增架构反例 2 方法，最低契约 97/662；清单 digest 只为新增方法上调，不重新生成/降低原验收范围。每个源的 SHA-256、风险行号与远端计数记录于交付目录 `cross-suite-audit.json`。

以下 CI53 数字仅为 e848 的远端既有证据。最终源码的 Kotlin/JUnit/重复稳定性均 UNVERIFIED；不能由静态审阅和旧 651 通过推导新版本全绿。

## 横向结论

- 旧 UI 正向标签与旧四栏目期望仅在两项 Closet 失败；Finance 旧首次按钮仅在失败用例。采用支持的“账户 → ＋ 建立账户”，未发现已确认需求强制账簿空态直接按钮，故不更改产品入口。
- 唯一过期的当前全库 27 表断言已替换为真实 SQLite 注册表集合与重复/缺失/额外检查。历史迁移库的版本和旧表断言保留。
- 合法恢复仅两项全快照相等遗漏 epoch；修复首恢复及重试。其余故障回滚/错误备份断言继续完全相等；分字段恢复策略检查不剥离 v16。
- DATE 正常写入需 capture 证据；Insights 唯一遗漏已补。CivilDate 中故意缺证据/伪造日期的负例保留。测试内合成 epoch/明确时区的生成不等于历史读取回填；切换设备时区的用例 finally 复原。
- 两项 Compose 失败使用实际入口/范围/当前 lazy 容器。普通记录同时有个人成本与现金两处展示，因此按合并语义中的说明及账户/分类定位现金行；转账只显示一条，源/目标账户显式选择。
- FinanceSmoke 的 Activity 在 @After 之后才由 Compose rule 结束，因此先清理其真实 ViewModelStore，再关闭 Room 和重置门。既有共享 FinanceRoomTest 清理与其他模型测试的自有 store/main dispatcher 清理保留。
- 并发测试修复租约释放后的错误竞争判断，以闩锁持有生产租约到真实恢复尝试完成；不使用任意 sleep。两代快照的正向接收用两个实际内容信号。GateAwareFlow 订阅计数使用原子变量；直接竞争 beginRestore，而不是先看 active count 再假定预留；修改过的 collector 在 finally 中取消并 join。
- 300 ms 的既有负向静默窗口只用于有前后正向控制的“恢复期间不发射”观察，不作为正向完成条件。剩余固定轮数/超时为失败边界，不是 fallback success。跨真实线程/Room tracker 的重复运行仍受本地环境阻塞，不能宣称消除了全部抖动。
- 无方法删除、skip/ignore/Assume、新增过滤或 catch-and-pass；原金额、历史引用、备份信任和日期拒绝契约不放宽。CI 工作流、生产 Kotlin、Entity/DAO/Migration、官方 schema 均未修改。

## 逐文件范围

| 源文件 | 原→现方法 | CI53 远端原结果 | 风险领域及审阅处理 |
|---|---:|---|---|
| [AppearanceRepositoryTest](../../app/src/test/java/com/qq/closie/AppearanceRepositoryTest.kt) | 4→4 | 4 PASS | restore/equality、setup/cleanup；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [AppearanceViewModelTest](../../app/src/test/java/com/qq/closie/AppearanceViewModelTest.kt) | 5→5 | 5 PASS | setup/cleanup；测试专用 ViewModelStore 清理和 Dispatchers.resetMain；保留编辑/取消/持久化断言。 |
| [ArchitectureBoundaryTest](../../app/src/test/java/com/qq/closie/ArchitectureBoundaryTest.kt) | 9→11 | 8 PASS, 1 FAIL | 规则/结构；修复窄草稿子表 DELETE 规则；新增两项拒绝反例；版本 5 是当前契约。 |
| [CalendarMathTest](../../app/src/test/java/com/qq/closie/CalendarMathTest.kt) | 6→6 | 6 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [CalendarStateTest](../../app/src/test/java/com/qq/closie/CalendarStateTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [DraftStoreTest](../../app/src/test/java/com/qq/closie/DraftStoreTest.kt) | 8→8 | 8 PASS | setup/cleanup；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FunctionEffectsTest](../../app/src/test/java/com/qq/closie/FunctionEffectsTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [HomeStateTest](../../app/src/test/java/com/qq/closie/HomeStateTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LifeDrawerStructureTest](../../app/src/test/java/com/qq/closie/LifeDrawerStructureTest.kt) | 4→4 | 4 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LifeOsNavigationTest](../../app/src/test/java/com/qq/closie/LifeOsNavigationTest.kt) | 5→5 | 5 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LifeTopLevelNavigationTest](../../app/src/test/java/com/qq/closie/LifeTopLevelNavigationTest.kt) | 5→5 | 5 PASS | setup/cleanup；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [MeasurementParserTest](../../app/src/test/java/com/qq/closie/MeasurementParserTest.kt) | 11→11 | 11 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [MonthGridTest](../../app/src/test/java/com/qq/closie/MonthGridTest.kt) | 4→4 | 4 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [PhotoStackInteractionTest](../../app/src/test/java/com/qq/closie/PhotoStackInteractionTest.kt) | 1→1 | 1 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [ProductLinkExtractorTest](../../app/src/test/java/com/qq/closie/ProductLinkExtractorTest.kt) | 13→13 | 13 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [ThemeCollectionTest](../../app/src/test/java/com/qq/closie/ThemeCollectionTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [ThemeValidationTest](../../app/src/test/java/com/qq/closie/ThemeValidationTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [BackupManagerTest](../../app/src/test/java/com/qq/closie/data/backup/BackupManagerTest.kt) | 15→15 | 15 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [DelegatingRestoreFs](../../app/src/test/java/com/qq/closie/data/backup/DelegatingRestoreFs.kt) | 0→0 | 辅助源 | 规则/结构；共用夹具/故障注入源，无独立 JUnit 方法。 |
| [EncryptedBackupEnvelopeTest](../../app/src/test/java/com/qq/closie/data/backup/EncryptedBackupEnvelopeTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [EncryptedBackupManagerV16Test](../../app/src/test/java/com/qq/closie/data/backup/EncryptedBackupManagerV16Test.kt) | 3→3 | 3 PASS | restore/equality、setup/cleanup；错误密码/恶意内容必须原样不变；合法恢复单独验证 epoch；不得放宽。 |
| [FailingRestoreFs](../../app/src/test/java/com/qq/closie/data/backup/FailingRestoreFs.kt) | 0→0 | 辅助源 | date/clock；共用夹具/故障注入源，无独立 JUnit 方法。 |
| [FinanceBackupRecoveryTest](../../app/src/test/java/com/qq/closie/data/backup/FinanceBackupRecoveryTest.kt) | 5→5 | 4 PASS, 1 FAIL | date/clock、restore/equality、setup/cleanup；实际 SQLite 表名集合/重复/序列化；保留失败恢复不变断言。 |
| [FinanceV4BackupRecoveryTest](../../app/src/test/java/com/qq/closie/data/backup/FinanceV4BackupRecoveryTest.kt) | 3→3 | 2 PASS, 1 FAIL | date/clock、restore/equality、setup/cleanup；只归一化 controlRows；首恢复/重试 epoch 与旧 token 拒绝；历史 links/expected 全保留。 |
| [GateAwareFlowTest](../../app/src/test/java/com/qq/closie/data/backup/GateAwareFlowTest.kt) | 8→8 | 8 PASS | threads/flows、setup/cleanup；AtomicInteger 订阅计数；beginRestore 原子获取替代先读计数；取消并 join；保留负向静默窗口。 |
| [LocalWardrobeRepositoryRecoveryTest](../../app/src/test/java/com/qq/closie/data/backup/LocalWardrobeRepositoryRecoveryTest.kt) | 5→5 | 5 PASS | setup/cleanup；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [RestoreCoordinatorTest](../../app/src/test/java/com/qq/closie/data/backup/RestoreCoordinatorTest.kt) | 71→71 | 71 PASS | restore/equality、setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [RestoreGateConcurrencyTest](../../app/src/test/java/com/qq/closie/data/backup/RestoreGateConcurrencyTest.kt) | 15→15 | 15 PASS | threads/flows、setup/cleanup；租约内闩锁检查替代 sleep/租约外竞争断言；两代快照用实际接收信号。 |
| [RestoreRecoveryManagerTest](../../app/src/test/java/com/qq/closie/data/backup/RestoreRecoveryManagerTest.kt) | 15→15 | 15 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [RestoreStartupGateLifecycleTest](../../app/src/test/java/com/qq/closie/data/backup/RestoreStartupGateLifecycleTest.kt) | 15→15 | 15 PASS | threads/flows、setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [ExternalIntentRouterTest](../../app/src/test/java/com/qq/closie/life/capture/ExternalIntentRouterTest.kt) | 12→12 | 12 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [ShareRouterTest](../../app/src/test/java/com/qq/closie/life/capture/ShareRouterTest.kt) | 12→12 | 12 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LifeContainerStartupGateTest](../../app/src/test/java/com/qq/closie/life/data/LifeContainerStartupGateTest.kt) | 5→5 | 5 PASS | restore/equality、setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [LifeDatabaseNameTest](../../app/src/test/java/com/qq/closie/life/data/LifeDatabaseNameTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LifeDatabaseMigrationTest](../../app/src/test/java/com/qq/closie/life/data/database/LifeDatabaseMigrationTest.kt) | 8→8 | 8 PASS | setup/cleanup；逐代升级/表集合及旧字段保留，历史版本断言有意保留。 |
| [FinanceAssistantProviderTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceAssistantProviderTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceAutomationTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceAutomationTest.kt) | 7→7 | 7 PASS | date/clock、restore/equality；继承真实 Room/门清理；审阅命令及拒绝后快照断言，保留。 |
| [FinanceBalanceAnchorTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceBalanceAnchorTest.kt) | 6→6 | 5 PASS, 1 FAIL | restore/equality、setup/cleanup；只允许恢复控制差异；重试保持原 anchor、现金和完整审计日志。 |
| [FinanceCalculatorTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceCalculatorTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceCategoryTreeTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceCategoryTreeTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceCivilDateV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceCivilDateV16Test.kt) | 20→20 | 20 PASS | date/clock、restore/equality、setup/cleanup；20 个真实日期/历史证据/恶意备份方法不改；时区 finally 复原；历史假证据仍应拒绝。 |
| [FinanceDiscoveryHistoryTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceDiscoveryHistoryTest.kt) | 2→2 | 2 PASS | setup/cleanup；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceEconomicDraftV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceEconomicDraftV16Test.kt) | 5→5 | 5 PASS | restore/equality；审阅合法恢复、历史保留及拒绝后不变断言；未发现额外 epoch 全快照冲突，保留原测试。 |
| [FinanceExpectedTasksV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceExpectedTasksV16Test.kt) | 3→3 | 3 PASS | restore/equality；审阅合法恢复、历史保留及拒绝后不变断言；未发现额外 epoch 全快照冲突，保留原测试。 |
| [FinanceImportRepositoryTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceImportRepositoryTest.kt) | 8→8 | 8 PASS | restore/equality；继承真实 Room/门清理；审阅命令及拒绝后快照断言，保留。 |
| [FinanceImportReviewTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceImportReviewTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceInsightsV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceInsightsV16Test.kt) | 7→7 | 6 PASS, 1 FAIL | date/clock、restore/equality、setup/cleanup；DATE 显式 capture 上海 2026-02-28；补真实还款及未知入账断言。 |
| [FinanceLocalOcrParserTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceLocalOcrParserTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceMediaV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceMediaV16Test.kt) | 5→5 | 5 PASS | restore/equality；审阅合法恢复、历史保留及拒绝后不变断言；未发现额外 epoch 全快照冲突，保留原测试。 |
| [FinanceMigrationTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceMigrationTest.kt) | 1→1 | 1 PASS | restore/equality、setup/cleanup；显式历史迁移夹具，旧表数/版本为输入契约，不替换为当前版本。 |
| [FinanceMoneyTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceMoneyTest.kt) | 4→4 | 4 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceNotificationParserTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceNotificationParserTest.kt) | 8→8 | 8 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinancePersonalProjectionTest](../../app/src/test/java/com/qq/closie/life/finance/FinancePersonalProjectionTest.kt) | 12→12 | 12 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinancePortableExportTest](../../app/src/test/java/com/qq/closie/life/finance/FinancePortableExportTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceProposalPrivacyTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceProposalPrivacyTest.kt) | 1→1 | 1 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceProvenanceV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceProvenanceV16Test.kt) | 5→5 | 5 PASS | restore/equality；审阅合法恢复、历史保留及拒绝后不变断言；未发现额外 epoch 全快照冲突，保留原测试。 |
| [FinancePurchaseAllocationV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinancePurchaseAllocationV16Test.kt) | 2→2 | 2 PASS | restore/equality；继承真实 Room/门清理；审阅命令及拒绝后快照断言，保留。 |
| [FinancePurposeReadV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinancePurposeReadV16Test.kt) | 4→4 | 4 PASS | restore/equality；继承真实 Room/门清理；审阅命令及拒绝后快照断言，保留。 |
| [FinanceReconciliationScopeV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceReconciliationScopeV16Test.kt) | 8→8 | 8 PASS | date/clock、restore/equality；合成当前时区日期在同一显式夹具中 capture；不是生产读取历史日期的回退。 |
| [FinanceRecoveryV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceRecoveryV16Test.kt) | 3→3 | 3 PASS | restore/equality、setup/cleanup；恢复保留/退休策略逐字段检查；不误把故障回滚全量相等当合法恢复。 |
| [FinanceRelationLifecycleTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceRelationLifecycleTest.kt) | 8→8 | 8 PASS | restore/equality；继承真实 Room/门清理；审阅命令及拒绝后快照断言，保留。 |
| [FinanceRelationsProjectionTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceRelationsProjectionTest.kt) | 1→1 | 1 PASS | restore/equality；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceRepositoryTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceRepositoryTest.kt) | 18→18 | 18 PASS | restore/equality、setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [FinanceReviewDeliveryTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceReviewDeliveryTest.kt) | 1→1 | 1 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceReviewSessionV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceReviewSessionV16Test.kt) | 8→8 | 8 PASS | date/clock、restore/equality；审阅合法恢复、历史保留及拒绝后不变断言；未发现额外 epoch 全快照冲突，保留原测试。 |
| [FinanceRoomTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceRoomTest.kt) | 0→0 | 辅助源 | date/clock、restore/equality、setup/cleanup；共用真实 Room/门初始化清理；恢复助手只剥离控制行后完整比较，拒绝旧命令且快照不变。 |
| [FinanceShareV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceShareV16Test.kt) | 2→2 | 2 PASS | restore/equality；审阅合法恢复、历史保留及拒绝后不变断言；未发现额外 epoch 全快照冲突，保留原测试。 |
| [FinanceSyntheticHistoryV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceSyntheticHistoryV16Test.kt) | 1→1 | 1 PASS | restore/equality；真实 Repository 2000 行、摘要/总额/当前 SHA 指标；最终本地未执行。 |
| [FinanceV16ContractTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceV16ContractTest.kt) | 15→15 | 15 PASS | restore/equality、setup/cleanup；命令回执、恢复失效、回滚/金额等既有强断言保留。 |
| [FinanceV4MigrationTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceV4MigrationTest.kt) | 2→2 | 2 PASS | restore/equality、setup/cleanup；显式历史版本 3→4 夹具，版本断言不是当前数据库版本错误。 |
| [FinanceV5MigrationTest](../../app/src/test/java/com/qq/closie/life/finance/FinanceV5MigrationTest.kt) | 2→2 | 2 PASS | date/clock、restore/equality、setup/cleanup；实际迁移及原时间/anchor/ID；新 DATE capture；保留旧库无猜测证据断言。 |
| [FinanceWorkspaceV16Test](../../app/src/test/java/com/qq/closie/life/finance/FinanceWorkspaceV16Test.kt) | 8→8 | 8 PASS | date/clock、restore/equality、setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [HostDetailAdaptersTest](../../app/src/test/java/com/qq/closie/life/finance/HostDetailAdaptersTest.kt) | 19→19 | 19 PASS | date/clock；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LegacyLedgerReaderTest](../../app/src/test/java/com/qq/closie/life/finance/LegacyLedgerReaderTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [SyntheticLegacyLedger](../../app/src/test/java/com/qq/closie/life/finance/SyntheticLegacyLedger.kt) | 0→0 | 辅助源 | 规则/结构；共用夹具/故障注入源，无独立 JUnit 方法。 |
| [VisibleEvidenceTest](../../app/src/test/java/com/qq/closie/life/finance/VisibleEvidenceTest.kt) | 5→5 | 5 PASS | date/clock、restore/equality；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [MediaStoreImporterTest](../../app/src/test/java/com/qq/closie/life/media/MediaStoreImporterTest.kt) | 6→6 | 6 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [CaptureRepositoryTest](../../app/src/test/java/com/qq/closie/life/repository/CaptureRepositoryTest.kt) | 12→12 | 12 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [LifeRepositoryTest](../../app/src/test/java/com/qq/closie/life/repository/LifeRepositoryTest.kt) | 11→11 | 11 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [MediaRepositoryTest](../../app/src/test/java/com/qq/closie/life/repository/MediaRepositoryTest.kt) | 6→6 | 6 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [PlanRepositoryTest](../../app/src/test/java/com/qq/closie/life/repository/PlanRepositoryTest.kt) | 34→34 | 34 PASS | date/clock、setup/cleanup；日程夹具与 dueLabel 统一时区/相对今日；不将固定公历日期误当今日。 |
| [ReferenceRepositoryTest](../../app/src/test/java/com/qq/closie/life/repository/ReferenceRepositoryTest.kt) | 30→30 | 30 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [CaptureGroupingTest](../../app/src/test/java/com/qq/closie/life/ui/capture/CaptureGroupingTest.kt) | 4→4 | 4 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [JournalCoverageTest](../../app/src/test/java/com/qq/closie/life/ui/theme/JournalCoverageTest.kt) | 4→4 | 4 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LifeDimensionsTest](../../app/src/test/java/com/qq/closie/life/ui/theme/LifeDimensionsTest.kt) | 6→6 | 6 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [LifeFontRulesTest](../../app/src/test/java/com/qq/closie/life/ui/theme/LifeFontRulesTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [ExternalCommandStateTest](../../app/src/test/java/com/qq/closie/navigation/ExternalCommandStateTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [ExternalIntakeTest](../../app/src/test/java/com/qq/closie/navigation/ExternalIntakeTest.kt) | 5→5 | 5 PASS | setup/cleanup；独立 Room/门夹具与清理、查询断言检查；保留。 |
| [TopLevelEntriesTest](../../app/src/test/java/com/qq/closie/navigation/TopLevelEntriesTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [ClosetSmokeTest](../../app/src/test/java/com/qq/closie/ui/lifeos/ClosetSmokeTest.kt) | 9→9 | 7 PASS, 2 FAIL | UI/lazy；实际 3 导航项/当前账簿/返回 Home/身体手势；移除旧屏幕正向期望。 |
| [LifeSwipeOwnershipTest](../../app/src/test/java/com/qq/closie/ui/lifeos/LifeSwipeOwnershipTest.kt) | 2→2 | 2 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceCalendarStopsTest](../../app/src/test/java/com/qq/closie/ui/lifeos/finance/FinanceCalendarStopsTest.kt) | 1→1 | 1 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceFilterStateV16Test](../../app/src/test/java/com/qq/closie/ui/lifeos/finance/FinanceFilterStateV16Test.kt) | 3→3 | 3 PASS | date/clock、restore/equality；继承真实 Room/门清理；审阅命令及拒绝后快照断言，保留。 |
| [FinancePresentationContractsTest](../../app/src/test/java/com/qq/closie/ui/lifeos/finance/FinancePresentationContractsTest.kt) | 4→4 | 4 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceSmokeTest](../../app/src/test/java/com/qq/closie/ui/lifeos/finance/FinanceSmokeTest.kt) | 6→6 | 4 PASS, 2 FAIL | UI/lazy、date/clock、restore/equality、setup/cleanup；首账户实际入口；当前懒列表滚动/合并语义；完整现金双腿/编辑/作废；先清 ViewModel 再关 Room。 |
| [MonthLedgerTest](../../app/src/test/java/com/qq/closie/ui/lifeos/finance/MonthLedgerTest.kt) | 3→3 | 3 PASS | 规则/结构；审阅纯规则/结构/状态或自有资源夹具；风险扫描未要求修改。 |
| [FinanceHomeProjectionV16Test](../../app/src/test/java/com/qq/closie/ui/lifeos/home/FinanceHomeProjectionV16Test.kt) | 2→2 | 2 PASS | date/clock、restore/equality、setup/cleanup；审阅合法恢复、历史保留及拒绝后不变断言；未发现额外 epoch 全快照冲突，保留原测试。 |
