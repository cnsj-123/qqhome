# V16 连续施工 checkpoint（2026-10-19）

最新指令：本聊天粘贴的 G0–G7 全量施工要求。旧资料中的 G0/G1 限制已被替代。
Owner 后续明确：本机没有 Android 工具链，不要下载；继续代码施工，后续交给 GitHub 验证。

- 基线：245e1475cd1d47a4205ab49f75d5e06d22de701c。
- 工作区：D:/codex/lifeos/工作区/qqhome-v16。
- 本地分支：feature/life-os-v16-g0-g7-local。
- 文档 HEAD：60bdce7361649c47699c2a328a0cc1ecd746b1b5；独立历史，未合并进 Android。
- 远端 refs 和 R2 CI #50 已由 GitHub connector 只读核实；命令行网络不可用。
- 原 qqhome checkout 的大量暂存删除未修改。
- 禁止 push/PR/merge/真实云/真实财务数据/实际手机操作；禁止下载工具链。
- 当前无 V16 APK，无 V16 编译或 Kotlin 测试 PASS。
- 已有本地逻辑提交：1d6a5755373c7e1ed4acafb28386d9ef90f1f16b（typed底座/工作页）；db16286a6e81e641a39ac757b57d9c5cb899c9d8（来源身份/已有流水关联/附件/OCR/筛选）；8df0329ad0319da28f68a29df489442c6a339c47（经济草稿/连续录入代际/预算/分享）。均非候选完成。
- 本次增量：预计返利任务、商品分配更正/解绑/原流水钻取、账户起点/净额/还款/区间、导入撤销兼容、工作页分页与返回状态、Manager故障回归。当前唯一提交以 git HEAD 为准，交接包另记录准确 SHA。

| Gate | 实际状态 | 证据与剩余工作 |
|---|---|---|
| G0 | IN_PROGRESS | T01–T12 已写 Kotlin 契约测试，未运行；Overlay 可编辑原生实现已接入，厂商/IME/Back 可行性仍 UNVERIFIED |
| G1 | IN_PROGRESS | 48 个新 typed 表、75 个总实体、迁移/备份/epoch/幂等/有限恢复已接；增加独立经济草稿/出资/份额3表，运行验证未完，Gate 未通过 |
| G2 | IN_PROGRESS | 三空间、精确计算器、持久现金/经济草稿、连续录入、金额币种过滤、每空间SavedStateHandle筛选、局部手势已接；返回状态和UI测试待补 |
| G3 | IN_PROGRESS | 增加建账户三种起点、净额范围/核对解释、只转换显式旧期初符号、真实还款转账和精确区间钻取；导入撤销保留后续typed配置/关联，运行测试未完 |
| G4 | IN_PROGRESS | 经济分配/赠送/归还/混合/未知、真实收付/历史结束、实际退款、预计返利任务、Purchase/Line/分配更正解绑/原流水时间线、批量修正与整组恢复均有入口；运行及复杂关系回归未完 |
| G5 | IN_PROGRESS | 可编辑半屏原生 Overlay、持久复核草稿/队列、应用内接力、typed Evidence/身份绑定、选图OCR已接；七宿主与设备/运行验证仍未完 |
| G6 | IN_PROGRESS | D4、预算编辑/独立政策、固定/弹性、线索原记录、保存查询、本地受限查询、分用途授权、Home真实读取、单授权范围分享/复验、分段AEAD备份、明文携出已接；全链路测试及UI完善未完 |
| G7 | IN_PROGRESS | 工作页分表面保存状态、分页限制组成行、查询避免每秒重复全量计算；补写Manager/导入/还款/精确区间回归与GitHub/单轮真机交接。设备性能、最终独立审查与候选APK仍未完成 |

已执行的验证：生产 v4→v5 SQL 在真实 SQLite 中保留旧 27 张表数据，创建新 typed 表，
外键检查通过，三个故障位置事务回滚通过。见 migration-sqlite-result.json。
这不等于 Room/KSP/Android 迁移通过。Schema 5.json 只能由 KSP 生成，未手工伪造。

恢复入口：先读本文件、feature-matrix.md、baseline.json、docs-verification.json，再核实 git status/HEAD。
生成器是构建期工程工具，运行时不解释 schema DSL：

    node engineering/v16/generate-schema.cjs
    python engineering/v16/check-migration.py

    node engineering/v16/integrate-schema.cjs
    python engineering/v16/check-source.py

当前源码结构检查：403 个 Kotlin 文件、75 个 Room 实体，PASS；它不解析或编译 Kotlin。
SQL 检查：旧27表逐行保留、新48表、FK检查、3个事务故障点回滚 PASS。
全部 Kotlin/Room/Compose 测试、KSP、assemble 和设备场景仍 UNVERIFIED。

独立只读 Reviewer 已分轮审查 G1、Workspace、Shopping、权限、Review/Overlay，并推动修复：
预算/统计分离、本金不重复消费、旧义务转换不叠加、核对顺序、typed退款显式冲减及重分配旁路、
Home未知/排除政策、整笔现金独立授权、复核并发确认、自动保存提交后取消与监听器销毁刷新。
复查记录见 REVIEW-LOG.md。新增 Kotlin 用例已覆盖这些问题，但未执行，不能算测试 PASS。

下一施工入口（不是终止清单）：
1. F43进行中：采集typed出处、显式来源身份/工具/有效期绑定及复核分类已写；关联已有Entry/Transfer只补来源，不造现金。已有实际退款/结算可复用OTHER现金并写真实关系，现金字段保留。v3采集不静默合并未知身份，订单编号不冒充付款编号。独立审查7处缺陷已修复，包括恢复身份去重、旧版本证据恢复、退款/币种旁路、最终日期绑定校验与显式采用工具建议；编辑数据改为同事务快照。全部Kotlin测试仍UNVERIFIED。
2. F32/F48：已接 Finance专用Media引用桥、PDF/图片、缺源检查/同摘要重连/多目标复用、用户选图本地OCR、未知字段候选/ReviewSession和明确保留原图；媒体跨域软引用的恢复理由见MEDIA-AND-OCR.md。待独立复查与运行回归。
3. 主空间筛选统一200字与恢复校验；账户筛选后余额不重算。经济草稿3表已接自动保存/停止独立队列/继续/舍弃；编辑器等完整快照，退役/旧epoch终态可清除Holder；连续现金草稿editGeneration在提交时递增，旧内容即使取到最新rev仍拒绝。独立静态复查已确认以上修复，全部运行测试UNVERIFIED。
4. 预算编辑、线索钻取、单SHARE范围预览与发送前复验已写并静态复查。报销快捷填写已接到经济草稿，明确替换份额、确认后建立义务；新例500/50/450→收300/150已写未运行，模板自身仍待独立复查。
5. F38预计返利/返现统一建立、修改、累计目标、部分到账、明确结束和历史查看已接；未知不当零、历史多轮累计目标不相加。F40分配更正/解绑可明确释放商品调整归属，按事项+商品校验承担，现金/事项总成本保留。对应测试已写未运行。
6. G3明确旧正数负债只转换期初，真实IN/OUT不取反；旧余额/register/reconcile保留。净额汇总一次扫描流水。账户起点新建与旧版本保护、实际还款、观察点精确开闭区间/确知入账筛选均已接。新单元与Compose用例未运行。
7. G7分页只控制组成的历史行，不裁剪财务汇总；独立tab保存滚动/输入条件。购买表单和选择版本保留，取消或进程恢复不默取新版本。导入保留空备注及R2重复候选兼容；撤销保留后续附件/草稿/授权/复核引用以及已配置账户。加密Manager实际URI故障和跨数据库用例已写未运行。
8. 独立Reviewer本轮因账户用量限制未能完成G3复查，新增改动只完成自查；不能把上轮审查视为当前HEAD最终通过。下一步依次处理GitHub编译/KSP/完整测试的真实失败、最终独立审查、再按单轮清单完成设备性能/七宿主验证。执行顺序见GITHUB-VALIDATION.md，真机统一清单见OWNER-ONE-ROUND.md。

不要以这个 checkpoint 代替任务完成。所有40项最终验收仍0/40，不代表没有已写代码。
后续 GitHub 必须先 KSP，再运行全套 Kotlin/Room/Compose 测试和 assembleDebug；本地不下载工具链。
