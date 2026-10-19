# CI #51 综合修复交接（2026-10-19）

增量基线：`78950821129f522d17359a7ec3bca2232c120601`；分支 `feature/life-os-v16-ci-review`。已只读核实 [CI #51](https://github.com/cnsj-123/qqhome/actions/runs/39208535139) 的SHA、步骤、编译日志及artifact元数据，摘录见review-fix-evidence/ci51-observed.json。KSP在旧候选代码上成功；随后compileDebugKotlin失败，运行测试未开始，assemble跳过。本轮未下载工具链，未push/merge/PR/发布或触发CI。

| CI #51 问题 | 源码修复与保留的约束 | 关闭状态 |
|---|---|---|
| EncryptedBackupManager.kt:68 类型推断递归 | digest(File): ByteArray显式返回类型，File/InputStream两处use均明确lambda；SHA-256及重新读取已写入密文的校验保持 | 源码已修复，重新编译UNVERIFIED |
| FinanceV16References.kt:370 Int/String混用 | 按Int主键保留去重检查；FinanceV16Integrity继续要求单例id=1/非负epoch；生成器按主键类型生成检查，重新生成不会带回错误 | 源码已修复；新增实际完整性校验回归，执行UNVERIFIED |
| FinanceAccountSheet.kt:20 nullable Long | rememberSaveable保存非空MutableState<Long?>，expectedVersion本身保留null，且冻结所见版本/恢复epoch | 源码已修复；旧并发/版本用例保留，编译/UI执行UNVERIFIED |
| FinanceShoppingWorkbench.kt:62 nullable revision | 同样用MutableState<Long?>保存purchaseRevision；继续传给ShoppingPurchaseInput的expectedRevision，不填0/最新值绕过校验 | 源码已修复，重新编译UNVERIFIED |
| DATE随设备时区改变 | FinanceCivilDate、两类Entity、Workspace、Projection、完整性及显示/编辑：保存原始日历日、时区、确认时区间与解释版本，读取不再推断设备时区；账期/区间筛选复用同一证据边界 | 源码及回归已补；Kotlin/Room/Compose执行UNVERIFIED |
| 官方5与最终模型不一致风险 | 采用情况B：修订未发布候选v5，两个新表各增5个nullable字段；同步设计DDL、4→5迁移和官方schema校验。DAO的SELECT */@Upsert及typed snapshot全字段读写自动包含新列 | 不提交#51旧5；最终官方5待下一轮KSP生成，严格门禁仍FAIL |

持久化契约：finance_entry_timing增加postedLocalDate、postedZoneId、postedDayStartAt、postedDayEndExclusive、postedDateBasisVersion；finance_observations增加observed前缀对应5列。解释版本1表示“用户明确确认的本地日历日及当时日区间”。创建时必须传明确的LocalDate+ZoneId，用下一自然日边界计算区间，不固定加24小时；持久化区间避免之后设备时区或tzdb规则变化重新解释旧事实。DATE仍为整日不确定性，不能冒充midnight/end-of-day的精确入账。新增写入校验证据与所选日期/时区，重放旧无新增字段的命令不改旧事实。

旧DATE的五字段全部缺失时允许保留/恢复，时间范围未知，book和difference保持null；不能从postedAt epoch用UTC/设备时区补造日历日。部分字段、不支持的解释版本或与原时间矛盾的证据拒绝写入/恢复。INSTANT原epoch、旧Entry.occurredAt/recordedAt、opening anchor及现金算法均保留。UI显示已保存的原始日期/时区；旧DATE缺证据显示资料不足，不能自动填入一个精确时刻。用户主动补日期/时区属于明确更正，不是迁移推断。

核对算法版本4：共享范围只排除有完整时间证据且全部位于范围外的记录；日期区间与anchor/观察范围重叠时保持INCOMPLETE。指纹包含精度、原始日期、时区、区间及解释版本，已保存算法1/2/3均提示重新核对。改变设备时区不会改变事实或指纹；明确更正日期依据即使区间数值相同也会使结论失效。

| 日期要求 | 生产路径回归（均UNVERIFIED） |
|---|---|
| A 东京→洛杉矶 | CivilDate.tokyoDateSurvivesLosAngelesDeviceZoneAndRepositoryRecreation：真实Room重读、日期/时区/epoch/余额/指纹不变 |
| B 洛杉矶→东京 | CivilDate.losAngelesDateSurvivesTokyoDeviceZoneAndRepositoryRecreation，同等断言 |
| C DST | CivilDate.daylightSavingDays…：LA 2026-03-08为23小时、2026-11-01为25小时，Repository持久化后读取 |
| D DATE与INSTANT | 同DST用例及原Scope四例：同日中午book/difference为null；次日界限确定后才计算 |
| E 明确范围外 | CivilDate.legacyDateHasNoGuessedZone…及原Scope：明确完整日期区间在anchor之前或观察之后才排除 |
| F 范围未知 | 同上：旧DATE仅epoch仍未知；新未知postedAt即使cash在未来也不排除 |
| G 依据/精度变更 | CivilDate.explicitZoneBasisChange…：UTC→Etc/UTC区间相同仍失效，错误版本事务回滚；原Scope精度更正/实际重核保留 |
| H populated迁移 | V5Migration.populatedV4UpgradeStoresCivilDates…；原v1/v2/v3/v4四个入口测试保留，旧ID/时间/anchor/金额断言不弱化 |
| I typed加密恢复 | CivilDate.encryptedTypedBackup…和olderTypedArchive…：真正Manager/URI/AEAD/跨库恢复；旧ZIP去掉新增字段后重新加密，实际restore保持字段null且INCOMPLETE |
| 相关读路径/主键 | CivilDate.postedFiltersAndStatements…、integerControlIdentity…：复用完整日期区间，不将无证据DATE计为确知入账；Int主键非法/重复仍拒绝 |

Backup格式仍为未发布的typed格式5（manifest schema1、AEAD envelope2），新信息直接进入typed Entity/snapshot。现有Gson全字段导出和FinanceV16BackupRecovery完整实体Upsert保留新字段，不创建旁路JSON真相；旧备份缺列保持null，完整性在提交恢复前验证。候选Room仍为5，4→5只创建新表/列，没有destructive migration，也没有旧v1–4 schema改动。SQLite辅助检查验证新增列nullable且没有猜测默认值；它不能代替Room MigrationTestHelper。

已执行本地：Python工程门禁22/22 PASS；固定最低契约/动态审计PASS（最低97文件/648方法，原638全部保留，V16审计33文件/158方法）；结构408 Kotlin/75 Entity PASS；真实SQLite旧27表、新48表、FK与3故障回滚PASS；历史schema1–4/R2可读检查PASS；git diff --check PASS。先前清单因新测试尚未跟踪而按契约红灯，加入Git后通过，没有放宽删除防护。

当前官方5.json：本地没有生成或提交。#51的artifact 12166810831属于旧模型，不取其5作为最终文件。Owner手动推送本补丁后，既定CI依次执行完整checkout/R2/Python/最低契约/结构/SQLite→KSP最终模型→严格schema→全量testDebugUnitTest/JUnit逐方法/2000条实测→assembleDebug。首次最终模型5未提交仍红，KSP成功后继续收集单测；从该次artifact原样归档最终5后提交并由Owner再推送，全部一致才有APK。历史、固定契约、精确JUnit、失败上传与独立debug/release门禁均保留。

仍须CI执行：当前SHA的Kotlin编译/KSP、最终官方5生成与已提交字节一致性、全套648方法（含本轮新增10方法）、所有Room populated迁移/加密恢复/并发/Compose、2000条真实运行及资源指标、assembleDebug及APK SHA。当前均UNVERIFIED；真机厂商特性继续DEVICE UNVERIFIED。交付仅一份7895082之后的format-patch，完整代码SHA、补丁哈希和独立index逐树验证见包内manifest。
