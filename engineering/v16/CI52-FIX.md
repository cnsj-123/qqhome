# CI #52 定点修复交接（2026-10-19）

输入代码 `origin/feature/life-os-v16-ci-review` = `e440e6dd39ddb140e9aa55f90e8505e96845f605`；文档 `origin/life-os-docs` = `60bdce7361649c47699c2a328a0cc1ecd746b1b5`。只读 fetch 后逐项匹配，初始工作树干净，R2 `245e1475cd1d47a4205ab49f75d5e06d22de701c` 可访问。本地分支 `fix/v16-ci52-integrity-local` 从该唯一代码基线创建。未合并文档分支；已读完整文档索引、V16确认增量、Owner/G0、架构备忘录及相关架构/协作约定，历史施工状态不作为当前验证结果。

本轮三个逻辑提交：媒体测试字段访问；Civil Date证据校验、范围可信性和回归；官方v5归档、SQLite结构比对工具和本交接。没有下载工具链，没有 push、merge、PR、CI触发、真实账本或云操作。D1–D5及40项产品范围保持。

## 实际 CI 错误与关闭矩阵

[CI #52](https://github.com/cnsj-123/qqhome/actions/runs/39213117803)，Run `39213117803`，Job `117930744565`，输入SHA同上。通过GitHub connector读取真实日志、job步骤及artifact；摘要见 [ci52-observed.json](review-fix-evidence/ci52-observed.json)。

| #52 项目 | 已观察事实 | 本地修复/当前状态 |
|---|---|---|
| `FinanceMediaV16Test.kt:24:51` | `compileDebugUnitTestKotlin` FAIL，`Unresolved reference: managedPath` | 改成 `before.mediaResources.single().row.managedPath`；类型归属审查PASS，重新编译UNVERIFIED |
| Strict committed official Room schema | FAIL：5.json不被Git HEAD跟踪 | 从指定#52 KSP artifact原字节归档并提交，提交后严格本地门禁PASS；最终源码重新KSP逐字节比对UNVERIFIED |
| KSP、主Kotlin/Java编译 | #52输入SHA已执行成功 | 属于输入基线的真实PASS；本轮新CivilDate代码重新编译UNVERIFIED |
| JUnit / 2000条运行指标 | #52测试编译先失败，JUnit NOT_RUN，报告验证步骤跳过 | 保留全量测试及精确方法门禁；本轮运行UNVERIFIED |
| assembleDebug / APK | #52跳过，无该次APK | 本轮UNVERIFIED，无APK交付 |

媒体类型链：LifeBackupPayload.mediaResources是List<MediaResourceRecord>，record.row是MediaResourceEntity，managedPath在row；LifeBackupApplier按目标设备重建locator。检查共享MediaRepository/FinanceMediaRepository及其他测试，没有再发现同类直接字段访问。本轮未新增冗余字段、Any/反射或路径身份；单原件双目标、解绑不删原件、恢复缺源、同SHA重连与现金不变的原断言全部保留。

## Civil Date：分类和保守边界

集中入口为 `FinanceCivilDate.assess`，完整性仍从 `FinanceCivilDate.validate` 经FinanceV16Integrity调用。`stored`只解码原字段，不写库、不生成替代区间。

| 类别 | 判定 | 恢复/读取 | 确定范围 |
|---|---|---|---|
| A CONFIRMED | 五字段完整、格式/版本合法、区间有可能且与明确date+zone的当前规则完整匹配；DATE的at须落在保存区间 | 保存原字段，不重写 | 可使用原区间；仍为整日不确定性，不能冒充INSTANT |
| B LEGACY_UNKNOWN | 五字段全部null | 兼容读取/恢复，不补日历日、时区或postedAt | 未知；不能证明范围内或外 |
| C HISTORICAL_UNVERIFIED | 完整、结构合法、通过必要包络，且规则并非固定偏移，但命名时区的原区间无法由现有依据确认一致 | 保留全部原字段、epoch与区间；允许恢复，无法宣称还原了旧tzdb | 不使用该区间；相关核对INCOMPLETE，book/difference为空 |
| D INVALID | 部分缺失、非法/非规范日期或ZoneId、未知版本、逆序/零长、超出必要包络；固定规则时区（含命名ZoneRegion）不匹配；或精度/at与保存区间矛盾 | 拒绝新写入/恢复；不会按有效日期进入投影 | 不可形成确定结论 |

新建/用户明确更正仍要求显式LocalDate+ZoneId，并严格比较 `date.atStartOfDay(zone)` 与 `date.plusDays(1).atStartOfDay(zone)`；没有固定加24小时。B/C不从occurredAt、recordedAt、UTC或当前设备时区补推历史事实。INSTANT仍使用原epoch；UNKNOWN/LEGACY、现金投影、旧Entry时间及opening anchor不变。

必要包络的数学依据：Java [ZoneOffset](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/ZoneOffset.html) 支持范围为-18h到+18h。对声明日D的本地午夜M，任何普通/重叠午夜的瞬时边界B=M-offset，故B在 `[M-18h,M+18h]`。若午夜落入gap，[atStartOfDay(zone)](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDate.html#atStartOfDay(java.time.ZoneId)) 使用gap之后的有效边界：`B=L_before-offset_before=L_after-offset_after`，且 `L_before<=M<=L_after`，同样得到上下界。次日端点独立使用D.plusDays(1)的午夜；不从start推算end。实现直接由ZoneOffset.MAX/MIN构造Instant比较，避免epoch加减溢出。

两端分别限±18h且end>start，理论长度上界为60h；这是Java offset全集导出的必要上界，不宣称真实银行日或tzdb存在60h日。它包容23h/25h、午夜gap、历史日界线跳跃，整日不存在时新capture因end==start拒绝。没有任意“±N天”。

包络只是必要条件：错误时区、分钟偏移甚至伪造的近邻区间可能落在包络内。非固定规则的命名时区不完整匹配时降为C，不获得可信范围；ZoneRules.isFixedOffset为true时不存在该规则内的偏移变化，不匹配直接D，不能仅凭ZoneOffset实例类型排除命名别名。basisVersion=1没有tzdb版本证据，不能证明C对应哪个旧规则。合法历史区间也可能因此被保守降级；这是资料不足，不能自动修复。A表示可按现有契约核验的一致性，不是对用户原始事实的密码学认证。

所有DATE范围计算共用 `financeTimeBounds`，仅A返回保存区间；postedTimeBounds、对账scope、AccountStatus、观察区间比较、信用账期和posted过滤均复用它。B/C无法用来排除一笔“看似在未来/起点外”的同账户交易；观察依据不可靠时也不能产生账面值或差额。现金余额不受该范围不确定性改写。

核对算法升级5；指纹继续含精度、原始日历日、ZoneId、区间、basisVersion，并显式含分类与可用bounds。A→C、依据/时区/精度更正、成员范围变化均使旧结论失效；算法1–4要求重核。Status先传播INCOMPLETE并提示旧核对失效，不能继续显示成功。

### 最终 Review 定点更正

P1-01：证据非null不代表CONFIRMED。入账及观察标签现在共用civilDateLabel，按完整assessment区分A/B/C/D，并检查原at是否落在保存区间。A显示原始date/zone及“仅日期”；B明确历史依据缺失；C保留原始date/zone供参考，明确“确认区间无法核验，资料不足”；D显示“日期资料无效”，不冒充可信日期。没有用epoch、occurredAt、recordedAt、UTC、当前设备时区或假设的tzdb版本重建历史日期。

实际UI调用已审查：FinanceAccountWorkbench的观察行和区间行复用timeLabel；FinancePostingEditor使用postedTimeLabel，并将无条件“确知入账日”前缀改为中性“入账日期”。FinanceBalanceSummary原先绕过标签、直接格式化observedAt，现改为timeLabel，新增真实Compose显示回归。未保存核对时仍为DERIVED现金推算，说明明确“不代表入账日期证据已核验”；没有将其提升为已核对，也未改变现金/anchor或核对算法。

P1-02：unverifiedObservationCannotProduceBookDifferenceOrIntervalAndInvalidatesSavedSuccess保留方法名，改用两个不同且同时存在于当前Room snapshot的观察ID。第一个证据有效，第二个最初有效并保存成功核对，再模拟历史依据待核验；compare使用快照里的两条当前行，bookMovement/unexplained均为空，旧核对降为INCOMPLETE且失效。账户、真实现金、入账原事实和anchor均断言保持不变。生产compare契约没有修改。

最后P1固定规则更正：assess从`originalZone is ZoneOffset`改为`originalZone.rules.isFixedOffset`。UTC、Etc/UTC、UTC+09:00、GMT+09:00虽为命名ZoneRegion，也必须按固定规则核验，不能把矛盾区间解释成旧tzdb差异。这里检查整套ZoneRules是否固定，并非只比较所选日期的当前偏移；东京/洛杉矶等非固定规则仍保守保留C原字段，不覆盖历史。

新增shiftedFixedNamedZoneEvidenceIsInvalidEvenWithUnchangedEpochInsideTheRange：真实Repository创建明确日期/时区并将原始postedAt/observedAt保存为中午；只将两端整体后移30分钟，epoch不变且严格在伪造区间内。UTC、Etc/UTC、UTC+09:00、GMT+09:00、Z、+09:00均断言INVALID，FinanceIntegrity分别拒绝两类实体；东京和洛杉矶对照仍为C、字段保留、book/difference为空，原库快照不变。

新增forgedFixedNamedZoneEncryptedBackupsCannotChangeAnExistingLedger：对UTC、Etc/UTC、UTC+09:00分别从真实Manager导出正常包，只篡改当前用例行的两端，保留中午epoch；AEAD重新加密后走实际EncryptedBackupManager.restore，断言失败来自日期完整性拒绝，非空目标完整LifeBackupPayload/FinanceSnapshot不变、余额49,300不变。没有通过前一循环用例的坏行凑拒绝结果。两项测试均未运行Android/JUnit，保持UNVERIFIED。

## 回归对应（源码已审计，Android执行全部UNVERIFIED）

FinanceCivilDateV16Test保留原9方法及首版新增7方法，展示Review增2方法，固定规则Review再增2方法；FinanceSmokeTest的真实Compose方法保留。既有658方法（含原648及此前所有新增）全部保留；不删除/替换方法、不降低原金额/时间断言。最低契约97文件/660方法；V16审计33文件/170方法。规范化契约SHA-256为 `303247088a925db57f864fd154b3279aad3716290eab9ecdc567a8909ad5f9cc`。

| 要求 | 真实生产路径/断言 |
|---|---|
| T01/T03/T04 | contradictoryCalendarDateEvenWithEpochInsideAndMalformedGroupsAreRejectedAtomically：12类畸形组合；两种Entity完整Snapshot的FinanceIntegrity拒绝；DATE/at在伪造范围内仍拒绝；两种Repository写入失败前后完整快照相同 |
| T02 | anotherZonesBoundsArePreservedButCannotDetermineMembershipOrBypassNewWriteValidation：错配Tokyo边界/LA标识不能新写；模拟历史存储后原字段保留，bounds空、book/difference空、posted过滤空、statement未知数量1 |
| T05 | 原legacyDateHasNoGuessedZone…及旧备份恢复；all-null读取兼容，真实保存资料不足核对后Status=INCOMPLETE，book/difference为空 |
| T06/T07 | 原双向跨时区Repository重建；日期/zone/epoch/现金/anchor/指纹不变，增加difference=0和Status=RECONCILED断言 |
| T08/T09 | 原DST真实Room日期测试，LA 2026-03-08=23h、2026-11-01=25h；中午观察不能确认整日入账，保存后Status=INCOMPLETE |
| T10 | 原encryptedTypedBackup…跨库恢复完整日期行/旧账户/流水/anchor；offsetEnvelopeExtremesAndMidnightGaps…另测Sao Paulo午夜gap、Apia跳日、±18h极限及超界1ms拒绝 |
| T11/T12 | plausibleHistoricalRuleChangeCannotExcludeAnOutsideRowAndInvalidatesSavedSuccess：模拟未记录的30分钟规则差异；范围内/明确日期在未来的C均不能确定排除，旧成功失效、现金不变；明确更正为可核验的新依据后仍须重核。unverifiedObservation…覆盖观察、status和compare的不确定性传播 |
| T13 | contradictoryTypedEncryptedBackupIsRejectedBeforeAnyTargetSnapshotChange：真实BackupManager导出typed ZIP，仅改日历文本后重新AEAD加密；真实EncryptedBackupManager.restore拒绝，非空目标的完整LifeBackupPayload和FinanceSnapshot前后相同 |
| T14 | 原encryptedTypedBackup…，保留AEAD/URI/真实跨库；增加现金9,900与原anchor断言 |
| T15 | 原olderTypedArchive…真实移除新增字段后重新加密/恢复，五字段null、不造时区、book/difference空、Status=INCOMPLETE |
| C历史备份 | plausibleOldRuleBackupPreservesRawEvidenceAndInvalidatesInsteadOfRepairing：真实Manager导出/重新加密/恢复；保存的30分钟差异原样保留，旧核对失效，Status=INCOMPLETE |
| Review标签/已保存信心 | persistedDateLabelsDistinguishAllEvidenceClassesAndSavedAccountConfidence：四类真实持久行的入账/观察精确标签、at与区间矛盾标签、保存核对的信心与空差额；账户及现金不变 |
| Review未保存信心 | unsavedAccountConfidenceDoesNotCertifyUnverifiedPostingOrObservationEvidence：真实Room无已保存核对，DERIVED仅指现金推算且不认证入账依据；不可靠观察的book/difference为空 |
| Review实际UI | FinanceSmokeTest.balanceSummaryShowsHistoricalObservationConfidenceInsteadOfAnEpochDate：真实Repository/Room→生产净额展开Composable；断言保留原date/zone、无法核验说明及资料不足，无已核对标签，不改现金 |

部分直接DAO写入仅用于构造已经存在的历史/损坏行，随后通过真实Repository Snapshot及生产Integrity/Projection读取；正常创建与更正走Repository，备份反例通过实际Manager入口。每例Room隔离，额外目标库和TimeZone在finally恢复；没有catch异常后无条件通过、Ignore/Disabled/Assume或提前退出。

## 官方 Room v5 与迁移/备份

指定 artifact ID `12168253801`，名称 `room-schema-run52-e440e6dd39ddb140e9aa55f90e8505e96845f605`，Run `39213117803`、head_sha与输入匹配。官方GitHub下载工具取得ZIP；下载字节SHA-256与GitHub digest一致：`e07044391b14115d291aa5080efc3298405e20a255a452ce51b989917157badd`。

从ZIP原样取出5.json，225,392 bytes；SHA-256 **`0552aba2f220be4a927b455db260aec8cefc787e4c26801906265bbdf5b58aea`**；formatVersion=1、database.version=5、identityHash=`f251a04bbe24f7c79d40d1ff715805f4`、75实体（旧27+新48）。归档位置 [5.json](../../app/schemas/com.qq.closie.life.data.database.LifeDatabase/5.json)。不是#51产物或设计JSON转换；未编辑官方文件/hash。

两表各五字段posted*/observed* LocalDate、ZoneId、DayStartAt、DayEndExclusive、DateBasisVersion全部nullable。Entity、DAO、TypeConverter、生产Migration及设计DDL相对#52完全不变，所以本轮可归档#52官方输出。check-official-schema-migration.py是**本地辅助检查，android.yml未调用，不是强制CI步骤**；它在真实SQLite比较官方5与生产4→5：75表的列、类型、nullability、默认值、PK/FK及索引结构一致PASS，不代替Room运行或既有强制verify-room-schema.py门禁。

历史1–4的Git字节与R2一致；artifact中1–4也逐字节等于R2，没有改写。现有populated v1/v2/v3/v4→5及Civil Date迁移测试均保留，真实Room MigrationTestHelper执行仍UNVERIFIED。

没有新增持久字段或新日期Truth。Typed备份格式5、manifest schema1、AEAD2保持；原Entity全字段snapshot/Upsert保留日期证据，旧包缺组仍null。BackupImporter在staging阶段调用FinanceIntegrity，D在提交前拒绝，C按原值恢复。恢复epoch、并发/幂等和共享Media所有权没有改变。

## 本地检查、CI顺序及余项

| 检查 | 本轮交付状态 |
|---|---|
| Python工程门禁（含重复methodA不覆盖methodB、删除/跳过/缺失/参数映射反例） | PASS 22/22 |
| 固定最低契约/增量审计/旁路扫描 | PASS 97/660；33/170；删除和旁路均0 |
| 源码结构 | PASS 408 Kotlin / 75实体；不是编译 |
| 生产SQLite迁移 | PASS 旧27表保留、新48表、FK及3故障回滚 |
| 官方DDL对生产迁移SQLite结构（本地辅助，非CI强制） | PASS 75表 |
| 历史Schema/R2与已提交官方5严格门禁 | PASS（5提交后执行）；本轮重新KSP UNVERIFIED |
| git diff --check、完整diff红线自查 | PASS |
| 独立同基线worktree git am --3way及复验 | 结果及两个tree/HEAD见交付manifest；必须PASS才交付 |
| Kotlin/test编译、JUnit660方法、Room/Compose、Manager恢复实测 | UNVERIFIED，本机无工具链且未下载 |
| 真实2000条CI导入/性能指标、assembleDebug/APK SHA、设备验收 | UNVERIFIED |

工作流保留原样：fetch-depth:0/R2对象检查 → Python22/最低契约与动态审计/结构/SQLite/历史schema → KSP → 已提交官方5严格字节比对 → testDebugUnitTest（包含test Kotlin编译） → 完整类名+方法名逐项JUnit及当前SHA的2000条真实指标 → assembleDebug → APK/commit SHA。Test任务禁用旧结果缓存/up-to-date绕过。KSP成功而严格schema失败时仍收集实际单测，但job保持失败；assemble只在全部必要门禁成功后执行。JUnit失败/跳过/缺失/重复代替缺方法均FAIL，无|| true或continue-on-error。

Artifacts保留：Room JSON、JUnit XML/HTML及真实指标、Git/KSP/test/assemble日志和工程证据always上传；仅assemble成功上传debug APK+commit-sha.txt+SHA256SUMS.txt；feature不读取release签名密钥。没有把#52旧主编译PASS写成新源码或JUnit通过，也不将辅助SQLite当Room迁移PASS。

剩余验收：Owner手动应用/推送后，GitHub执行最终KSP字节重生成、compileDebugKotlin/compileDebugUnitTestKotlin、全套660最低方法及动态增量、populated迁移、日期/媒体/恶意与历史备份、并发/恢复/Compose（含既有标签回归及本次固定规则反例）、2000条真实报告和APK。随后独立Review及既定一轮设备清单；Overlay/IME/七宿主/真机性能仍DEVICE UNVERIFIED。

## 交付定位

单个multi-commit文件：`life-os-v16-ci52-integrity-fix.patch`；唯一范围 `e440e6dd39ddb140e9aa55f90e8505e96845f605..HEAD`，保留原三个逻辑commit并将日期/测试更正归入第2提交、工程记录归入第3提交。本次交付取代前版同名补丁；交付目录 `v16-ci52-fixed-rules-delivery` 中的 `delivery-manifest.json` 和 `HANDOFF.md` 记录最终源HEAD、验证worktree HEAD、相同Git tree、补丁SHA-256、实际复验结果及完整修改文件清单。它们在最后commit/导出之后生成；本文件无法在自身提交内嵌入该提交自身SHA和包含自身的补丁SHA，故这些最终值只在导出后的交接侧文件写入，不能伪造自引用哈希。仓库以干净已提交状态交付。
