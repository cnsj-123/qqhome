# 40 项实现与验收追踪矩阵

全部保留在同一个 V16。以下状态指 V16 验收，R2 已有代码不算自动通过。
2026-10-19：前表保留施工前责任/验收范围；当前实际施工状态以下方代码映射为准，所有编译/设备验证仍 UNVERIFIED。
代码 Owner 是本地施工责任边界；人工施工者为本聊天 Sol。最终统一走 Owner 单轮验收，
设备无法替代的场景标 DEVICE UNVERIFIED，不要求 Owner 逐 Gate 参与。

| ID | 入口与真实业务路径 | 代码 Owner / 预计或已有位置 | 自动验证与最终验收 | 施工前状态（历史） |
|---|---|---|---|---|
| F01 | 记一笔→金额计算→保存 | FinanceMoney / EntrySheet | 18+12.5=30.50，非法/溢出；IME 保存 | TODO |
| F02 | 手工/候选→可空备注→详情 | FinanceRepository / AutomationRepository / ReceiptModel | 空备注真实保存、不生成假备注；真机录入 | PARTIAL，待运行 |
| F03 | 记一笔→最近账户/图标分类 | Category owner / EntrySheet | 持久/恢复/选中值；连续录入 | TODO |
| F05 | 连续记账→锁日期账户→多次保存 | Draft / EntrySheet | 每次一笔、失败保留；连续场景 | TODO |
| F07 | 编辑→离开/杀进程→恢复草稿 | FinanceDraft / Repository | 草稿往返、不计余额；重启 | TODO |
| F09 | 账簿→月/周/收起→连续流水 | CalendarInteraction / MonthLedger | 停靠/取消/Drawer 冲突；系统 Back | TODO |
| F10 | 账簿→日期金额账户分类组合查询 | QuerySpec / ViewModel | 条件组合、独立空间、返回位置 | TODO |
| F11 | 筛选→保存视图→重开 | SavedQuery / Repository | 保存的是条件，新增流水后动态读取 | TODO |
| F12 | 分析→构成明细→返回 | CostProjection / Drilldown | 精确解释数值、含非现金事项 | TODO |
| F13 | 作废记录→影响预览→整组恢复 | Recovery typed preimages / Repository | 转账关系全组、冲突拒绝；误作废恢复 | TODO |
| F14 | 多选→分类/标签/账户预览→批量确认 | Recovery / Batch commands | 版本冲突零部分提交、金额余额影响 | TODO |
| F16 | 账簿→年月网格/最近月份 | DateSheet | 跨年直达、状态恢复 | TODO |
| F17 | 账户→逐笔账→筛选 | FinanceAccountProjection.register | 完整账先扫描，筛选前后余额相同 | PARTIAL，待运行/入口 |
| F18 | 账户→观察余额→保存核对 | Observation / Reconciliation | 插入历史/修改使结论失效 | PARTIAL，待运行/命令/入口 |
| F19 | 建账户→零起点/余额接续 | Account command / AccountSheet | anchor 边界和旧值守恒 | TODO |
| F20 | 账户→可信状态解释 | AccountProjection | 已核对/推算/不足/失效真实状态 | TODO |
| F22 | 信用账户→账期→还款核对 | CreditCycle / Timing / Transfer | 短月/时区/未知入账日、消费一次 | TODO |
| F24 | 账户→多观察点→区间比较 | Observation / AccountProjection | 只比较不造调账；差额定位 | TODO |
| F25 | 导入→解析/映射/预览/提交/撤销 | Existing Import owner | 错误行、去重、取消、原子故障回滚 | TODO |
| F32 | 事项→附件→来源/缺源 | Media owner / Finance adapters | 单份原图、恢复引用、缺源提示 | TODO |
| F33 | 往来→待收待付/报销返利 | ObligationProjection / RelationsScreen | 不混现金和义务、不漏未知 | PARTIAL，待运行/入口 |
| F34 | 报销500/承担50→收300/150 | EconomicRepository / Obligation | 现金/成本/义务独立守恒 | PARTIAL，尚缺完整报销入口 |
| F35 | 经济事项→赠送/需还/未知 | EconomicRepository / CostProjection | T01/T02/T04，无本人现金事项 | PARTIAL，测试未运行/无入口 |
| F36 | 经济事项→不等额/混合分配 | EconomicRepository / Integrity | T03/T05，精确分配和未知余量 | PARTIAL，测试未运行/无入口 |
| F37 | 原事项→实际部分退款 | EconomicRepository.recordAdjustment | T07，跨月/账户、原支付不改 | PARTIAL，测试未运行/无入口 |
| F38 | 返利任务→部分到账/放弃 | Expected / Resolution / Adjustment | 未到账不增收入、结束不重现 | TODO |
| F40 | Shopping→Purchase/Line→成本时间线 | Purchase typed owner / Finance allocation | 多付款/部分退款/未分配值真实可解释 | TODO，只有模型 |
| F43 | 多来源→识别/绑定/关联同笔 | Evidence / Identity / Automation owner | 同订单多付款、跨账号、重绑、冲突 | TODO，只有模型 |
| F44 | 外部宿主→可编辑半屏小票→保存 | ReviewSession / ReceiptOverlay | IME/焦点/旋转/锁屏/撤权/Back；目标 vivo | TODO，DEVICE UNVERIFIED |
| F45 | 规则→试运行→展示建议→确认 | Existing RuleEngine | 不命中/命中、无自动 Canonical 写入 | TODO |
| F46 | 主动读当前详情→明确结果 | VisibleDetailReader / Accessibility | 七宿主白名单/有界采样/失败原因；目标机 | TODO，DEVICE UNVERIFIED |
| F47 | 候选队列→稍后→重启接力 | ReviewSession / Automation owner | 新候选不覆盖、并发/epoch 防护 | TODO |
| F48 | 选图→本地 OCR→复核→确认 | OcrEngine / Evidence / Media | 仅所选图、离线/取消/来源清理 | TODO |
| F51 | 分析→固定/弹性口径 | CategoryPolicy / CostProjection | 本金还款排除、非必填政策 | TODO |
| F54 | 分析→温和异常线索→原记录 | Read-only diagnostics | 重复/返利/余额线索，不自动修改 | TODO |
| F55 | 分析→轻量分类预算 | Budget owner / CostProjection | D4 本月-40、累计160、不裁零 | TODO，只有模型 |
| F56 | 标签/专题/保存视图→跨月汇总 | Query owner / SavedQuery | 重叠去重、未知成本，不依赖假Trip | TODO |
| F60 | 导出→可读账单/完整携出 | Backup / FinanceExport | 原ID关系/币种/CSV公式防护/明文提示 | TODO |
| F61 | 授权指定范围→Home真实回忆→撤销 | PurposeGrant / Home provider | 默认关闭、撤销立刻消失、无演示数据 | TODO |
| F62 | 本地自然语言→解释条件→查询 | Restricted QuerySpec / Permission | 歧义/写指令/注入拒绝，无SQL执行 | TODO |

V16 已完整验收：0/40。PARTIAL 不是完成，不计算为已交付。随施工持续更新。

## 当前代码映射（替代上表初始 TODO 描述，非验收通过）

| ID | 当前真实代码/入口 | 尚需验证或补齐 |
|---|---|---|
| F01 | FinanceCalculator / FinanceAmountInput；现金录入与复核统一计算 | 单元/Compose/IME未运行 |
| F02 | Repository/Automation允许空备注，UI不写假备注 | 端到端未运行 |
| F03 | 最近账户、分类搜索/图标持久政策与显示 | UI持久/大字体未运行 |
| F05 | Draft命令与EntrySheet连续保存，锁账户/日期 | 连续/失败回归未运行 |
| F07 | typed现金/经济分配草稿与ReviewSession，停止独立排队刷新/收起/继续；已完成/旧epoch不重建 | 进程死亡/提交后重建/Compose未验证 |
| F09 | 月/周/收起停靠、连续账簿、局部ownership | 设备Back/取消/手势冲突未验证 |
| F10 | QuerySpec组合条件、工作页/每空间过滤、主账簿金额/币种筛选；SavedStateHandle恢复 | Compose/进程恢复未运行 |
| F11 | SavedQuery条件持久化、动态重算/移除 | 进程状态/回归未运行 |
| F12 | CostLine构成、分析分类钻取/返回 | UI与未知语义回归未运行 |
| F13 | Recovery整组预览、作废、有限恢复入口 | 非现金依赖恢复/冲突测试待扩充 |
| F14 | BatchEditor选择真实流水、预览、原子修改与恢复 | Room/Compose未运行 |
| F16 | MonthPicker跨年年月选择、账簿独立月份 | 进程恢复/手势待验 |
| F17 | 完整register先扫描，账户页备注/方向/日期筛选，同行余额不重算 | 过滤一致性测试已写未运行 |
| F18 | AccountWorkbench观察点/核对保存；算法2计算和指纹共用证据范围，无法定位postedAt保持INCOMPLETE | 新增真实Room范围/后补/失效用例，UNVERIFIED |
| F19 | AccountSheet三种新起点、原anchor保留、旧起点影响预览/从零约束及版本保护 | 边界/旧版本测试已写未运行 |
| F20 | Trust读模型与净额范围展开；显式正债务只转换旧期初，原口径另列，币种分列 | 内部还款/超额/排除/符号测试未运行；独立复查待补 |
| F22 | 信用账期、确知入账编辑、短月/时区推导、原双腿转账还款入口 | 新还款Compose及未知入账设备未验 |
| F24 | 多观察点同口径区间比较，精确(first,last]流水钻取；posted未知不列入 | 筛选余额一致性/边界测试已写未运行 |
| F25 | R2导入入口/阶段反馈，空备注保留，撤销保留后续typed关系和账户配置；2000条已提交CSV接真实导入/DAO/成本/净额/筛选余额/重复回归 | 旧故障回滚用例保留，新回归实际执行时输出耗时/堆采样，无固定毫秒通过线；UNVERIFIED |
| F32 | Finance专用typed媒体引用，事项/购买/小票入口；单原件复用、缺源检查/同摘要重连 | URI权限/恢复/取消与设备回归未运行 |
| F33 | Workspace确认义务列表/部分结算/历史声明/复用已有真实结算 | 旧关系合流与结算UI回归未运行 |
| F34 | 本人实际垫付→显式报销草稿模板→确认个人承担/报销方承担→分次真实结算 | 500/50/450与300/150回归已写未运行 |
| F35 | EconomicEditor支持外部赠送、需还、未知，无假Entry | 草稿/修改影响与测试待补 |
| F36 | 多出资/多份额不等额分配、混合/未分配 | 并发与复杂编辑回归待补 |
| F37 | 事项实际退款/Typed复核显式冲减；D4不回写 | 商品/跨币种冲突与设备测试未跑 |
| F38 | FinanceExpectedTasks统一计划/修改/部分到账/明确结束/历史；未知金额及历史多轮不混加 | FinanceExpectedTasksV16Test未运行，进程/Compose待验 |
| F40 | Purchase/Line、商品分配更正/解绑、显式释放调整归属、时间线原事项/真实流水钻取 | 分配校验与跨事项/现金保持测试已写未运行，跨域设备待验 |
| F43 | 采集typed出处，显式身份/工具/有效期绑定、复核采用建议、已有正式流水关联 | 独立静态修复已接；多来源/恢复/并发测试未运行 |
| F44 | 可编辑原生半屏Overlay、焦点/IME/Back/旋转/锁屏/撤权 | 全部设备行为UNVERIFIED，七宿主不能冒充PASS |
| F45 | 现有规则建议/试运行路径保留 | 新证据连接/回归待补 |
| F46 | 现有七宿主主动有界读取保留 | typed出处/逐宿主真实验证待补 |
| F47 | durable ReviewSession/Expected、稍后/接力/epoch/版本保护 | 队列UI与生命周期故障回归待补 |
| F48 | OpenDocument选图→本地MLKit→有限字段→持久候选/草稿→标准复核；明确保留原图 | 本地引擎/离线/取消/权限/进程死亡仍UNVERIFIED |
| F51 | 分类固定/弹性政策编辑与按成本聚合 | 入口已接，测试未运行 |
| F54 | 相似扣费/未结义务/核对差额只读线索，原流水/事项/账户入口 | 性能与Compose回归未运行 |
| F55 | Budget独立纳入政策、负已用/剩余及说明；编辑范围/上限与停用 | D4综合/Compose测试未运行 |
| F56 | 标签组合/专题保存条件，真实成本全量聚合、结果分页，tab条件/滚动独立保存 | Compose/大数据设备性能未验证 |
| F60 | 完整typed+CSV携出/明文提示/读回校验；加密备份；单SHARE授权片段预览与发送前复验 | Manager真实URI故障/跨库用例已写未运行，分享chooser/设备测试未运行 |
| F61 | HomeStateProvider真实Canonical有限授权；依赖变化准备事实，时钟只检查字段到期，撤销/恢复清空 | 新真实Room/生产投影计数、到期/撤销/恢复用例，UNVERIFIED |
| F62 | 固定本地语法→可见QuerySpec→独立授权范围读取 | 歧义/权限/IME/解释UI回归待跑 |

这些均为 PARTIAL 或待验证；代码路径接入不等于验收通过，不得宣称40项已交付。
