# Finance V4 review-fix 验收说明

## 范围与基线
在 `372d41b866bb63d18663100c276f9368a695fdb6` 上追加两个 review-fix 提交，保留原五个提交。只修改 Finance V4 关系、统计归属、导入审核及相关测试。未推送。数据库仍为 version 4、同一个 life_os.db；未修改 applicationId、签名、根导航、功能页手势、层叠/平面外观、减少动画、图标或字体。

## 修复结果
- 事件关系增加稳定 ID、创建/更新时间、作废时间和 revision。普通编辑和分配作废旧关系、插入新关系；LINK_CREATE/LINK_RETIRE 日志引用关系 ID、版本和确认来源。备份保留完整历史，语义查询、统计及关系分配只采用有效关系。
- 移走最后一条有效关系后，旧事件作废，预计关系取消；共享事件关系变化会更新事件时间以阻止过期编辑。作废流水、转账手续费及撤销导入采用同一生命周期规则。Integrity 拒绝孤立有效事件、作废流水上的有效关系和孤立/已作废事件上的有效预计款项。
- 一个事件可有多项预计款项，提供用途、可选金额、备注、添加和逐项取消。未编辑列表时不改既有预计款项。预计款项不会创建实际流水或影响余额、收入、支出。
- REFUND/REBATE/CASHBACK 在明确时采用原付款分类、标签；付款分类冲突或缺少有效付款上下文时使用“调整待归属”。分类与标签分别判定歧义。服饰付款 300、退款 100 的个人净支出和服饰分类均为 200。金额只在投影中调整，实际流水不变；跨期退款仍使用完整事件上下文，分析分类/标签筛选在归属之后执行。
- 导入六组：普通 READY 默认批量选择；报销、退款、转账手续费按组确认；重复候选默认跳过，支持明细中逐条覆盖；无法解释、债务关系或无效记录需单独处理。多种问题并存时需要确认全部相应政策。报销/退款附属字段仅保留在 staging 证据中，不虚构到账。手续费一次确认适用于所有所选适用行。明细默认收起，以分组入口查看。
- 新增关系不扫描全部关系；旧事件清理按事件索引查询。通知退款建议新增 typed REFUND role，界面不再从原文重新推断角色。
- canonical DELETE 源码审计：移除 clearLinks，Finance canonical 表无 DELETE、无 REPLACE、无 cascade。仅保留 finance_entry_tags 的描述性重建、finance_proposal_tags 和 finance_rule_conditions/actions 的配置重建 DELETE。架构守卫覆盖 Finance DAO 及 Finance 领域源码，并检查当前 Room schema 文件及其 version。

## 新增测试类（未执行）
包 `com.qq.closie.life.finance`：
- FinanceV4MigrationTest：实际 MigrationTestHelper v3→4、v2→3→4；旧 Capture/Plan/身份 revision、账户、普通流水、转账、分类/标签、余额与 anchor 保留；Room 写入新语义。
- FinanceRelationLifecycleTest：重关联、多事件分配、关系历史与日志、孤立清理、多项可选预计款项、余额隔离、过期编辑、失败回滚、作废流水/手续费、Integrity。
- FinancePersonalProjectionTest：个人付款、退款/返利/返现分类及标签归属、歧义桶、分账/报销承担和实际结算、代拍、历史关系和排除统计。
- LegacyLedgerReaderTest：合成 CSV/XLSX、精确分值、分类/子分类、引号/换行、重复、非法精度、报销/退款/排除字段、手续费、未知类型/债务。错误 dimension A1:V1 的 XLSX 实际写入 512 行，断言全部读取。
- FinanceImportReviewTest：300 条报销记录的批量政策、重叠问题、普通默认选择、重复单独覆盖、无效禁止选择。
- FinanceImportRepositoryTest：staging 不入账、账户映射、历史与当前余额 anchor、转账/手续费、分类/标签、无虚构到账、同文件幂等、库内/文件内重复、迟发失败原子回滚、重复提交、撤销及保留后续编辑。
- FinanceAutomationTest：来源允许列表、非支付忽略、单金额和歧义金额、REFUND role、sourceKey 幂等、稍后/忽略不入账、确认一次、重复确认、确认失败不改变 Proposal、规则确定排序/首个标量/合并标签。
包 `com.qq.closie.data.backup`：
- FinanceV4BackupRecoveryTest：JSON + 实际 Room 恢复，完整活动/历史关系、多项及已取消预计、规则、Proposal、staging；恢复重试、恢复旧快照保留后来关系身份、旧 F1 格式恢复保留 pending intake。
另有测试基类 FinanceRoomTest、合成数据辅助 SyntheticLegacyLedger。维护 ArchitectureBoundaryTest、FinanceMigrationTest、FinanceBackupRecoveryTest 的过期断言，并增加 listener 无直接创建 FinanceEntry/启动 overlay 的源代码守卫。

## 实际验证状态
- 构建：未运行。PATH 无 java/javac/kotlinc，JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT 未设置，常用 Android Studio JBR/Android SDK 位置不存在；没有 local.properties。
- 单元/Room/Compose 测试：未运行，不能声称通过。测试代码也未由 Kotlin 编译器验证。
- Room schema 4：未生成、未提交。没有手工制作 identityHash；这是仍未满足的验收条件。迁移和当前 schema 守卫已补齐。
- 私人真实 XLSX：未提交，没有写入测试或日志。
- 真实 XLSX 的 Kotlin 读取器 dry-run：未执行，原因同上。此前仅有本地文件结构检查，不等于实际读取器验证。
- 合成 XLSX 回归：测试已写，未运行。
- 字体仍为原 Source Han Sans CN Normal 8,434,332 字节；未生成 APK，因此 debug 前后、release/压缩增量均不可用。
- `git diff --check`：导出前执行，结果记于交付消息。

## Android 环境/CI 最小待办
先用 JDK 17 和项目要求的 SDK，依次运行：

```sh
./gradlew :app:kspDebugKotlin --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:assembleDebug --stacktrace
```

先单独运行 KSP，确保新导出的 schema 4 在 debug migration assets 合并前存在。预期：真实生成
`app/schemas/com.qq.closie.life.data.database.LifeDatabase/4.json`，迁移和新测试通过，debug APK 产出。
将实际生成的 4.json 作为第三个 `chore(room): commit LifeDatabase v4 schema` 提交；失败时根据真实诊断修复，不手填 schema/hash。此 patch 尚不具备“已编译、测试通过、schema 完备”的验收状态。

## 最小手动验收（未执行）
仅使用可丢弃账本/安装和文件副本，保留真实账本备份。

1. 服饰付款 300、关联退款 100：个人总额、子/主分类均为 200；实际流出 300、流入 100。再让事件含餐饮付款：退款显示“调整待归属”，分类合计仍等于个人总额；原退款分类不得冒充付款归属。
2. 同事件添加多项预计（其中一项金额留空），取消一项；保存后余额及实际流水数量不变。重关联后旧孤立事件与其预计失效；备份再恢复仍保留旧关系身份。
3. 合成/脱敏 22 列账单：确认前不入账；普通默认批量选中，报销/退款/手续费组各一次操作覆盖全部适用记录，重复默认跳过。实际入账仅包括真实流水和已确认费用。重选同文件打开原批次；撤销作废未修改流水，保留后来编辑。
4. 微信/支付宝测试通知：发现时仅有 Proposal；稍后/忽略不入账；确认及重复确认只有一笔。系统通知权限拒绝时提案仍可从 Finance 查看。

已知限制：不自动解释债务/未知类型；不推断缺失的个人承担；歧义分类需人工整理；多标签统计本身允许交叉归类，不能将所有标签桶简单相加作为支出总额。通知幂等按通知来源 key，不保证不同通知描述同一交易时自动合并。未执行真机/界面检查、真实工作簿读取器 dry-run、构建和迁移验证，schema 4 仍待真实 KSP 生成。
