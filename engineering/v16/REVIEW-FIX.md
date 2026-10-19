# V16 Review 第一轮修复与 CI 门禁交接（2026-10-19）

第二轮当前增量、精度算法3和最低测试契约见REVIEW-FIX-ROUND2.md；以下保留第一轮交付记录。

本轮增量基线：`9a0acf0da84544750bd37f1e15e5220c4a899a95`。保留所有已有 V16 代码；原 R2 基线仍为 `245e1475cd1d47a4205ab49f75d5e06d22de701c`。交接只包含该 V16 HEAD 之后的 format-patch，本地未 push、PR、merge、发布或触发 CI。

| 问题 | 修改文件/实际行为 | 新增或补强验证 |
|---|---|---|
| P0 官方 Schema 门禁 | android.yml、verify-room-schema.py、.gitattributes：1–4 与 R2 及提交版一致；5 必须同时已跟踪、已提交、KSP生成后字节与HEAD一致，整个schema目录无diff；设计JSON不能替代官方文件 | test-ci-gates.py 实际创建合成文本Git仓库，覆盖相同/未跟踪/仅暂存/生成变化/缺文件；本地5例通过。当前严格门禁因无官方5.json明确FAIL |
| P1 对账范围/指纹 | FinanceAccountProjection、WorkspaceRepository、AccountStatus：计算和指纹共用同一证据范围；已知postedAt按闭区间[anchor,observation]；未知同账户保留不确定性；指纹算法2不含明确范围外流水或无关录入/备注变化，旧算法1提示重新核对 | FinanceReconciliationScopeV16Test：入账早于收付、anchor前/未来收付但入账未知、明确posted范围外、其他账户未知、后补范围内/边界、作废、起点及算法版本 |
| P1 Home每秒全量成本 | FinancePurposeRead、FinanceHomeStateProvider：先裁取真实成本/授权依赖并distinct，再在Default dispatcher计算；时钟只掩去过期字段，未授权金额不计算/缓存金额；关闭恢复门禁即输出空，重新收集读取新事实 | FinanceHomeProjectionV16Test：真实Room快照/真实生产投影，100次时钟更新及journal-only通知不重算，改账/撤销重算，过期清空，恢复/新collector不读过期授权；PurposeRead覆盖不同字段到期 |
| P1 2000条fixture未执行 | FinanceSyntheticHistoryV16Test、app/build.gradle.kts：直接读取已提交CSV和SHA清单，调用真实导入、DAO、投影及账户筛选；重复导入同批0新增、不同编码换行仍识别重复 | 验证2000条、两个账户、净额-294950分、成本294950分、完整逐笔累计独立算术对照及筛选同行余额；实际执行时输出耗时/堆采样，不设性能毫秒通过线 |
| 完整CI门禁/证据 | android.yml、verify-ci-tests.py：main与feature/life-os-v16-* push/PR/manual；pipefail保留真实失败；KSP成功后即使Bootstrap门禁失败仍执行全套unit tests，job继续红；检查实际JUnit无失败/跳过、V16类执行数量及本次SHA的2000条指标，全部通过才assemble/debug artifact；失败也上传XML/HTML/schema/logs | 工程门禁共9例本地通过（5例提交字节、4例报告解析），GitHub执行UNVERIFIED。所有artifact名含run number和commit SHA，debug含commit-sha.txt及SHA256SUMS.txt；feature不触及release签名步骤 |
| 测试自审 | TEST-AUDIT.md、audit-v16-tests.py、Media/ReviewSession/Contract测试：排除DAO未定义顺序依赖，分离可提交和未完成日期夹具，补T05完整分配及T08已有结算后更正/过期revision拒绝；不删除测试、不降低金额预期、不跳过失败 | 32个V16新增/修改测试文件、144个测试方法的源码清单；未发现Ignore/Disabled/Assume/return@runTest旁路。数量不是执行通过证据 |

对账边界已经 Owner 本轮明确确认：证据不足不能视为范围外。当前 EntryTiming 没有可证明范围外的未知日期上下界字段，不能从 occurredAt、recordedAt 或自由备注推断；这类记录保持 INCOMPLETE。若以后增加可靠的区间证据，必须有 typed 来源/边界验证及相应回归后才能用于排除。本轮没有新增猜测日期或更改旧 anchor。

版本是不同命名空间：Room DB 5（4→5显式迁移）；备份格式5（强制typed V16）；备份manifest schema 1；分段AEAD envelope 2；核对指纹算法2。不存在“把所有版本号改为5”的迁移。实体/DDL/typed备份结构本轮没有更改，官方5.json仍待KSP Bootstrap。

本轮diff自审：没有新增Canonical写入路径，核对保存仍走原Workspace事务；范围和指纹共享投影，算法版本只改变新结论，旧记录保持可读并提示重核。Home准备事实只在内存中派生，复用真实成本Projection，不持久化余额或另一套金额事实；tick不访问Repository/DAO，恢复门禁与撤销重建事实。Event/Entry/Cost/Obligation以及跨月调整算法均沿用原实现，金额预期未修改。备份变更仅纠正文档注释，Entity/DAO/Migration及typed结构无diff，历史1–4无diff。没有新增Composable或万能Manager，Overlay/IME/Back实现本轮无改动；这些现有设备行为仍待CI后的集中验收。Kotlin引用与新增Gradle配置完成静态自查，实际类型/KSP验证仍以CI为准。

已执行本地辅助验证：9例工程门禁通过（5例Schema提交字节、4例实际报告校验器的合成输入）、历史1–4完整性通过、结构检查406 Kotlin/75实体通过、真实SQLite旧27/新48/FK/3故障回滚通过、diff空白检查通过。严格官方Schema门禁本地实际FAIL，原因是5.json未提交；报告保存在review-fix-evidence。没有本地Kotlin、KSP、Room、Compose、fixture运行或APK PASS。

下一步由 Owner 授权推送匹配的功能分支。按 GITHUB-VALIDATION.md 完成Bootstrap与完整CI；Sol随后根据首个实际失败日志定位并提供新的本地增量补丁，不能要求Owner自行排查。完整CI通过且最终独立Review无阻断后，才启动现有OWNER-ONE-ROUND.md的一轮集中设备验收。厂商行为仍为DEVICE UNVERIFIED。
