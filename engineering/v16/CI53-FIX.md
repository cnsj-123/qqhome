# CI53 定点测试修复：本地环境 BLOCKED，尚未验收

唯一代码基线：`e84897a946cd258c3df22e55855205618401b930`。从已确认相等的远端分支建立本地修复分支，未从 main/旧 patch 重建；没有 push、merge、PR 或远端修改。

## 真实失败证据

已读取 [CI53](https://github.com/cnsj-123/qqhome/actions/runs/39249949151) 的完整 JUnit artifact `12185491670`，ZIP SHA-256 `12a110e98807bbdaafce4973476d4ec2c6011f3b4c0aa511db5ee8dc01834540` 与下载元数据一致。93 份 XML：660 执行、651 PASS、9 FAIL、0 SKIP。逐项读取完整 failure/stack trace；两个恢复相等失败的长快照经比较，仅 `controlRows=[]` 与 `controlRows=[FinanceControlEntity(id=1, restoreEpoch=1)]` 不同。

这是基线的远端证据，不是本地最终树的通过证据。交付目录保存原 ZIP、逐 XML 哈希/完整失败消息及 metrics；不把它们复制到本地 app/build 充当新结果。

## 九项修复矩阵

下表“修改后”为源码处理和预期，不表示已执行通过；九项最终运行均 **UNVERIFIED**。

| CI53 失败方法 | 精确原因 | 修改后检查 |
|---|---|---|
| ArchitectureBoundaryTest.financeOwnsTypedFactsInsideTheExistingDatabase | 新经济草稿 funding/share 子表维护 DELETE 未入窄白名单 | 仅两张子表的全表恢复清理及 `WHERE draftId = :id` 四种现有 @Query；拒绝其他 SQL/非 @Query。新增 canonical DELETE、@Delete、REPLACE 拒绝反例 |
| FinanceBackupRecoveryTest.snapshotAndJsonArchiveCarryEveryFinanceTableExactly | 当前已 75 表，仍断言 27 | 与实际 SQLite registry 精确集合相等；重复/缺失/额外分别断言；完整 JSON/插入行列表往返、非空 v4 日志与 v16 timing |
| FinanceV4BackupRecoveryTest.jsonArchiveRestoresActiveAndHistoricalLinksExpectedRulesProposalsAndStagingExactly | 合法恢复新增 epoch 控制单例，首相等及后续重试相等过期 | 只归一化控制行，全部其他字段完整相等；单例 ID、来源/目标 epoch 单调递增；两次恢复、旧 token 拒绝且完整快照不变；原历史 links/expected/proposal/rule/staging 全保留 |
| FinanceBalanceAnchorTest.backupRecoveryPreservesAnchorAndBackfilledCanonicalRows | 同一控制单例差异 | 首次/重试只允许 epoch 差异；原 anchor/createdAt/现金 980000/两条历史记录保持；恢复前完整审计日志保留；两代旧 token 被拒绝 |
| FinanceInsightsV16Test.creditShortMonthUsesKnownPostingAndRepaymentIsOnlyCashMovement | DATE 正常写入未提供 civil evidence，正确被 validateNew 拒绝 | 使用原选定 2026-02-28 / Asia/Shanghai 的 capture 证据走真实 Repository；二月边界、三月排除、还款双腿与个人成本不重复、未知入账仍未知 |
| ClosetSmokeTest.drawerFinanceOpensRealLedgerAndReturnsToStableHome | 等待旧“钱的位置”，并期望旧四栏目 | 可见当前 ledger、唯一三导航“账簿/账户/分析”和选中态；返回唯一 Home、Finance 移除、抽屉关闭 |
| ClosetSmokeTest.financeRootRightSwipeOpensShellFunctionPageAndBackKeepsFinance | 同上 | 同一真实 ledger 的内侧身体手势打开 shell；Back 关闭后仍当前 Finance/账簿及完整三导航 |
| FinanceSmokeTest.establishAccountRecordCorrectSearchAndVoidThroughReceiptSheets | 查找已不存在的“建立第一个账户”，继而对缺失节点滚动 | 经真实账户页建立首账户；返回账簿并选择全部；懒容器先滚动、按说明+分类/账户合并语义定位现金行；创建/改额/日期分类标签保留/搜索/作废/余额回归/完整性 |
| FinanceSmokeTest.transferIsOneVisualRecordAndEditsBothFlowsThenConfirmsWholeVoid | 全局文本查找依赖默认账户顺序与未组合的 lazy 行 | 显式选择源/目标；全部范围；先滚动当前账簿再唯一语义匹配；单一投影和视觉行、两现金腿 ID/角色、原时间/关系保持、两账户余额、双腿及 relation 同时作废、零额外消费和完整性 |

经济草稿维护依据：`FinanceEconomicDraftRepository.save` 在命令事务内重建可变草稿的子关联；提交草稿前不写现金/正式成本。`FinanceV16BackupRecovery` 在事务中先清理再恢复这两张子表。正式 economic/funding/shares、账户/entries/transfers、历史 links、父草稿物理删除均继续受守卫拒绝；原 shadow storage、第二数据库、@Delete 和 REPLACE 禁令保留。没有修改 DAO。

共用恢复断言位于测试夹具 `FinanceRoomTest.kt`，只处理明确允许的控制单例差异，不丢弃整个 v16、任何业务行、引用或日志。原失败/恶意恢复事务不变测试继续完全比较，不套用合法恢复归一化。

## 全套审计与最小范围

见 [逐 97 文件审计](CI53-TEST-AUDIT.md)。既有 660 个方法全保留，只新增 2 个架构反例；固定最低 **97 文件 / 662 方法**，digest `e6f340a4fb3caf723cb80d7cd78fc8ca2445130c0d57ed17f3dac0d3fc01116a`。R2 增量清单 40 文件 / 218 方法，不可用动态清单降低最低契约。

另外修复两套已有并发测试的静态可证竞争：租约释放后检测恢复会误拒绝合法恢复；plain Int 跨线程订阅计数不保证可见性；先读 active count 不会预留恢复门；正向两代数据等待靠定时延迟不保证已经接收。采用生产租约内的闩锁、原子计数、直接获取 beginRestore、实际内容信号及取消 join，原正反断言保留。负向静默窗口仍有前后实际发射控制；不加任意 sleep、skip、ignore、Assume、过滤或 fallback success。

FinanceSmoke 先清实际宿主 ViewModelStore 再关数据库，避免 @After 早于 Activity 销毁而遗留 Room collector。未发现已确认产品契约要求在账簿空态额外提供首次账户 CTA，因此测试使用现有账户页入口，没有改产品界面。**生产 Kotlin、Entity/DAO/迁移、原现金与 opening anchor、civil-date/加密备份/epoch 规则全部未改。**

## 本地预检边界

本机没有可用 JDK 17、Android SDK 或 Gradle 8.10.2 安装/缓存，JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT 未配置。旧 jdk17.zip 不完整，Gradle .part 为零字节。遵守不下载工具链的既有要求。最终提交后按同一顺序执行并记录：

1. Python 工程门禁（22 个含反向门禁用例）。
2. 固定最低契约与动态源清单。
3. 结构检查（仅静态，不等于 Kotlin 编译）。
4. 生产迁移 SQL 的真实 Python SQLite/3 个回滚故障点。
5. R2 与历史 1–4 schema 校验。
6. 离线调用 `gradlew.bat :app:kspDebugKotlin --stacktrace --offline`，环境缺少 Java 即阻塞，不触发下载。
7. 后续同链严格 schema、全套 rerun JUnit、逐 XML/精确方法/当前 SHA 的 2000 指标、APK 与实际包名验证，均不得越过步骤 6 伪称完成。

前五项可实际执行；交付目录 `local-preflight.json` / logs 记录最终 HEAD/树及每步实际状态。独立运行已提交 schema 静态字节门与 SQLite 官方 DDL 比较仅为辅助证据，不冒充 KSP 后门禁；`check-official-schema-migration.py` 仍是 **local-only**，不在 CI 强制步骤中。

当前官方 5.json 原字节 SHA-256 `0552aba2f220be4a927b455db260aec8cefc787e4c26801906265bbdf5b58aea`，历史 1–4、最终官方 5、生产 Entity/DAO/迁移均无差异。KSP 重生成和真实 Room 迁移运行仍 UNVERIFIED；不手写 schema、不 destructive migration、不关闭严格门禁。

## 尚未验收的项目

最终本地 JUnit XML **不存在**：total/pass/fail/skip 均 N/A（未执行，不是 0/0/0/0 通过）。最终树的 Kotlin 编译、662 个最低方法、Room/Compose/备份/并发及 2000 条 Repository 实测、完整 APK 构建/实际 applicationId 均 UNVERIFIED。

基线远端 2000 条旧指标 PASS：2000 活动行、netMinor -294950、对应 SHA e848；不能归为 LOCAL 或最终树证据。最终本地不生成假 metrics。debug applicationId 仅源码配置预期 `com.qqhome.lifeos.debug`，没有 APK 可验证。

以下八套至少两轮真实重复执行仍待具备环境后运行：FinanceSmokeTest、ClosetSmokeTest、FinanceCivilDateV16Test、FinanceBackupRecoveryTest、FinanceV4BackupRecoveryTest、FinanceBalanceAnchorTest、RestoreGateConcurrencyTest、GateAwareFlowTest。专用重复运行不得替代最终一次全套；若再次运行全套生成指标，GITHUB_SHA 必须为实际受测最终 HEAD，证据明确 LOCAL。

本轮保存的是 **环境受阻的可审阅 checkpoint**，不是“九项已实测通过”的已完成交付，不建议凭未执行结果推送。唯一完整三提交 format-patch、最终 SHA/树、逐文件变更及独立基线重放一致性保存在交付目录 manifest；本文件不自引用尚未生成的 commit ID。
