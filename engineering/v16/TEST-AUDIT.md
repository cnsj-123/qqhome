# V16 测试源码自审（2026-10-19）

最终固定规则Review：保留已有658方法，新增shiftedFixedNamedZoneEvidenceIsInvalidEvenWithUnchangedEpochInsideTheRange及forgedFixedNamedZoneEncryptedBackupsCannotChangeAnExistingLedger。固定命名别名与字面offset均校验，东京/洛杉矶作非固定规则对照；仅移动区间两端、原epoch不动，分别检验两类Entity的FinanceIntegrity拒绝及实际加密恢复的非空目标全Snapshot不变。最低97/660、增量33/170，pin与inventory同步；两项Android/JUnit执行均UNVERIFIED。既有标签/真实双ID区间/Compose方法保留，未旁路或降低断言。以下为此前快照。

CI52最终Review：新增CivilDate四类精确标签/已保存信心、无已保存核对说明两方法及FinanceSmokeTest真实净额展开标签方法；原unverifiedObservation…方法保留并改用当前快照内两个不同Observation ID，增加现金/账户/anchor/入账行不变断言。最低97/658、增量33/168；原648及首版新增7方法全部保留，pin与inventory同步。实际源码/工程验证与未执行Android边界见CI52-FIX.md；本次Kotlin/Compose/JUnit仍UNVERIFIED。以下审计数值为此前快照。

CI52增量审计：保留原648最低方法，新增CivilDate 7方法，固定97文件/655方法，V16清单33文件/165方法；pin与动态inventory同步更新。新方法逐项映射和恢复/区间反向审查见CI52-FIX.md的T01–T15表。既有跨时区/DST及备份方法仅加强状态/差额/现金/anchor断言，媒体仅修正typed row字段访问；没有删除或旁路。22例Python工程门禁实际PASS；全部当前Kotlin方法执行仍UNVERIFIED。以下CI51审计为历史快照。

当前CI51修复范围：R2以来新增或修改33个Kotlin测试文件/158个方法；含第二轮4个精度方法和本轮10个方法。逐文件清单、完整类名/方法名、原始文件SHA与旁路扫描见review-fix-evidence/test-inventory.json。该清单是审查索引，不是编译或执行报告。所有当前SHA的Kotlin测试运行仍UNVERIFIED。

固定最低契约增至97个测试源文件、648个方法，原638全部保留，详见test-minimum-contract.json；最低契约由规范化SHA-256锁定，独立于动态清单，删除文件/方法、重命名或暂存删除均明确FAIL。JUnit按完整类名和方法/显式参数调用名逐项核验，不能按重复用例数量填覆盖。该固定覆盖要求不代表对其余R2文件新增产品语义审查，也不代表648个方法已执行。

| 测试文件/类 | 检查内容与真实边界 | 自审说明 |
|---|---|---|
| ArchitectureBoundaryTest | 源码依赖、唯一Root、debug标识、schema只进debug assets | 架构静态断言，不冒充运行UI |
| BackupManagerTest | 实际URI归档、typed数据库、媒体、恢复后可继续写 | 保留原失败/坏包/回滚断言；备份格式与受支持旧包兼容 |
| EncryptedBackupEnvelopeTest | 真正分段AEAD、盐、错密钥、篡改/截断/乱序/尾随字节 | 有界真实加解密，不是JSON模拟 |
| EncryptedBackupManagerV16Test | 实际manager导出文件/解密/跨库恢复/损坏/错密码/写入失败 | 密码清零、暂存清理、失败数据库不变，真实Room |
| FinanceAssistantProviderTest | T09/T10用途/范围/撤销/恶意建议 | 使用获准的离线Fake transport，测试真实Coordinator；没有真实云或网络执行，建议仅惰性数据 |
| FinanceAutomationTest | 候选、正式确认、原子失败、重复确认、规则顺序 | 真实Repository/DAO与纯规则引擎；保留权威来源断言 |
| FinanceCalculatorTest | 精确表达式/非法/溢出 | 调生产计算器，不改变正确金额 |
| FinanceMoneyTest | 十进制精度、符号、范围、日期解析与原时间保留 | 无Double近似，无默认时区固定epoch假设 |
| FinanceEconomicDraftV16Test | 原始草稿/恢复/epoch/确认幂等/报销500/50/450 | 真实Room；300+150结算后成本仍50，不用假现金替代 |
| FinanceExpectedTasksV16Test | 未知/部分到账/明确结束/恢复/历史累计目标 | 不把预计写收入；固定clock用来证明非按墙钟猜顺序 |
| FinanceImportRepositoryTest | 真CSV/XLSX、去重、后段失败整批回滚、后续关系保留、空备注 | 调生产stage/commit/undo；新typed兼容断言保留完整事实 |
| FinanceInsightsV16Test | 独立统计/预算、商品退款、未分配、账期 | 保留D4和原付款；原流水关联检查完整ID集合 |
| FinanceLocalOcrParserTest | 原文候选、歧义/余额/编号/不完整日期 | 纯parser测试，不冒充MLKit或手机OCR运行 |
| FinanceMediaV16Test | 真实跨域引用、恢复缺源、重连、OCR候选 | 本轮修复DAO无ORDER BY下的插入顺序假设：比较两条完整实体的ID映射并另断言数量2，内容断言未降低 |
| FinanceMigrationTest | populated v2、旧记录/ID/精度 | 真实MigrationTestHelper及Room打开到v5 |
| FinanceV4MigrationTest | populated v2/v3→4与继续打开v5、anchor/旧语义 | 不只建空库；官方已发布schema保留 |
| FinanceV5MigrationTest | populated v1/v2/v3/v4→5；新增populated v4日期列写入 | 保留4个入口原断言，新增真实迁移后Repository/DAO写日期并验证旧ID/时间/anchor/现金；依赖最终KSP官方5.json |
| FinancePortableExportTest | 真实ZIP条目与CSV公式防护 | 是导出单元边界；Manager实际恢复由其他文件覆盖 |
| FinanceProvenanceV16Test | 编号/身份/工具/时效绑定/旧证据版本/恢复 | 真实Repository/DAO；无真实账号，恢复先禁用匹配 |
| FinancePurchaseAllocationV16Test | 更正/解绑、释放调整、跨事项错误覆盖 | 真实事务；失败前后完整快照相等，现金及成本值不减弱 |
| FinancePurposeReadV16Test | 用途隔离、整笔现金不继承事项授权、未知、字段独立到期 | 真实Room+生产投影；本轮补prepared字段到期 |
| FinanceRecoveryV16Test | 双腿整组/单调版本/批量冲突/安全恢复 | 真实Room，无部分提交；后续无关新增不阻止安全恢复 |
| FinanceReviewSessionV16Test | 草稿/稍后/跨表面竞争/明确退款/已有现金关联 | 本轮将helper日期设为可提交值，仅未完成场景显式使用“未完成日期”并断言保存；不改钱数或成功/失败预期 |
| FinanceShareV16Test | 单grant片段、撤销/改账/到期/恢复后拒绝旧预览 | 真实Repository；没有实际发送消息或启动外部接收应用 |
| FinanceV16ContractTest | T01–T08/T11/T12、gift/debt/unknown、D4、净额起点/还款 | 真实Room及加密typed恢复；本轮补T05完整100/80/120分配、T08过期revision及已有还款拒绝改赠与，失败前后完整快照相等；正确金额保持200/-40/160及报销500/50/450 |
| FinanceWorkspaceV16Test | 真实草稿/连续editGeneration/提交后取消/anchor/posted/单调核对 | 固定clock制造竞争边界；post已知测试不拿occurredAt回填 |
| FinanceCalendarStopsTest | 纯停靠状态函数 | 不能替代系统Back/IME/厂商手势 |
| FinanceFilterStateV16Test | Bundle条件恢复、完整账扫描后筛选、posted精确区间 | 同行余额与完整算术一致，cash与posted不混 |
| FinanceSmokeTest | 真实Compose→VM→Repository→Room，账户/流水/转账/筛选/还款 | 操作前等真实loaded，不操作真实设备；Compose未运行 |
| FinanceReconciliationScopeV16Test | 范围外/未知/早于cash/后补/anchor/void/算法升级；第二轮再补4个DATE精度方法 | 同日/anchor重叠时book与difference均null；精度变化、保存资料不足后补精度重核、全日在范围外与范围内，真实保存结论验证失效 |
| FinanceCivilDateV16Test | 本轮9个方法：东京/LA双向、23/25h DST、中午/范围、旧日期、时区依据、Int主键、筛选/账期、实际加密备份新旧恢复 | 时间域构造显式提供创建日历日/时区，经真实Repository及Room持久化；TimeZone.setDefault均finally恢复；备份使用Manager/AEAD/跨库完整恢复，旧ZIP仅移除新增字段以模拟旧候选格式，未将JSON往返冒充恢复 |
| FinanceSyntheticHistoryV16Test | 本轮新增：真实已提交2000条fixture→导入/DAO/投影/重复/过滤 | SHA对照、独立逐笔加减oracle、最终金额；指标仅在实际运行时写出，无假性能PASS |
| FinanceHomeProjectionV16Test | 本轮新增：时钟/相关依赖/到期/撤销/门禁/恢复 | 控制时间和通知调度，财务快照来自真实Room，计数器委托真实生产成本计算；100tick不重算 |

旁路检查：未发现新增/修改测试里的Ignore、Disabled、Assume或return@runTest绕过；CI无忽略Gradle失败的`|| true`/continue-on-error，日志管道用pipefail。断言个数、源码文字检查不能证明Kotlin类型正确，因此KSP、真实JUnit XML和完整测试仍是强制门禁。

状态隔离：Room用例每例新建in-memory DB，Before/After重置RestoreStartupGate；备份额外目标库在finally关闭。新Home用例使用testScheduler/backgroundScope，时区由同一默认时区计算比较日期，不依赖固定墙钟。2000条测试金额与时区无关，10分钟仅是防卡死超时，不是性能通过线。现有真实线程restore竞争用例保留有界等待，运行稳定性仍由CI报告验证。

D1/D2/D3/D4主要由FinanceV16Contract/Workspace/Insights/Purchase/Recovery覆盖；D5与T09/T10由PurposeRead/Share/Assistant/Backup覆盖。

| G0 不变量 | 对应现有方法/本轮补强（全部运行UNVERIFIED） |
|---|---|
| T01 赠与无本人现金/债务 | Contract.T01，价值120、成本0、Entry/义务为空 |
| T02 垫付及还款不重复消费 | Contract.T02，承担120、还款120后成本仍120/义务0 |
| T03 赠与/债务混合 | Contract.T03，300中赠100/需还200，还80后义务120 |
| T04 未知不等于0 | Contract.T04、PurposeRead.unknownLegacyShareNeverBecomesConfirmedZeroOnHome |
| T05 精确分配 | Contract.T05，缺120时不完整；本轮补100/80/120闭合、只需还部分形成义务 |
| T06 历史结清非银行证据 | Contract.T06，无Entry、现金证据仍不足 |
| T07 跨月调整 | Contract.T07、Insights.purchasePartialRefundTimeline…，200/-40/160保留 |
| T08 关系更正/版本/恢复 | Contract.T08，本轮补过期revision和已还80后拒绝删除需还关系；原重复确认/restore epoch拒绝不变 |
| T09 关闭/未授权不出站 | Assistant.T09、PurposeRead、Share；Fake transport只观察真实Coordinator边界 |
| T10 在途撤销/恶意结果 | Assistant.T10，生产Coordinator拒绝落地及扩范围 |
| T11 加密跨库恢复 | Contract.T11、EncryptedBackupManagerV16Test/EnvelopeTest，typed金额守恒及撤销授权 |
| T12 分币/负债/旧anchor | Contract.T12及positiveLegacyDebt…/negativeOpening…、Workspace.anchorPreview… |

F44/F46/F48真实宿主、MLKit离线与vivo/IME/权限/Back仍DEVICE UNVERIFIED。40项功能映射沿用feature-matrix.md，不把源码测试清单写成40项验收通过。

本地已实际运行的另22例是Python工程门禁测试（test-ci-gates.py）：5例Schema提交字节、10例JUnit/指标、7例最低契约及删除/历史检查，使用合成文本仓库和报告，不是上述158个Kotlin方法。第一轮Windows只读Git对象清理已修；第二轮辅助用例两行错位已归位，原断言保留，全套22例通过。CI51旧SHA也已实际通过工程步骤，但在主Kotlin编译失败，因此不能算本轮648方法通过。未添加/下载任何Android工具链或YAML依赖；当前Actions执行待Owner推送。
