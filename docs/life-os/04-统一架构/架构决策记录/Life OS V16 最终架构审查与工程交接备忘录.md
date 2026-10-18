# Life OS V16 最终架构审查与工程交接备忘录

审查日期：2026-10-18（Asia/Shanghai）  
修订状态（形成时历史快照）：**D1–D5 产品语义已由 Owner 最终确认；本版已落实裁决，G0 工程细案与实施交接方案待审查，尚未授权施工。**

> 后续授权与归档说明（2026-10-18）：本备忘录及 Owner/G0 记录已归档于 `life-os-docs` @ `4f1da7650efbc703a5ed3faba0f8243ccd5410a9`。形成时“尚未授权施工”的结论保留为历史；随后 Owner 已允许 Sol 在核实 R2 后进行本地 G0/G1 施工、合成 fixture、针对性测试及补丁交付。具体 DDL 仍须审查，不授权操作真实账本、远端推送/合并/PR、正式 APK 或真实云接入。最新权限见 [Owner 记录](../../06-路线图/2026-10-V16-Owner-决策与实施交接.md)，实际进展见 [当前开发状态](../../06-路线图/当前开发状态.md)；授权不代表实现、迁移或验证已完成。本轮仅交付文档补丁，尚未推送。

当前权威记录：[Owner 决策与实施交接记录](../../06-路线图/2026-10-V16-Owner-决策与实施交接.md)；Owner 私人裁决原文（由 Owner 本地保管，不公开归档）；[G0 领域契约与实施交接方案](V16-G0领域契约与实施交接方案.md)。本文件对裁决的归纳不是 Owner 逐字原话，工程建议仍明确区分。

**结论：有条件推荐现有 V16 收敛方向。保留 Finance Core，在现有命令、投影、Room 与恢复机制上补齐契约；40 项仍属于同一个 V16 版本。** 当前材料足以确定方向，尚不足以冻结数据库或直接分派全部编码任务。产品方向已定；进入实现前，仍须完成无本人现金流水的经济事项（含赠送、需归还、混合及关系未定）、结算归属、安全恢复、时间与余额起点、证据身份、可选云 AI 权限接口及备份兼容的工程契约。它们会影响多项功能，不能交给各页面分别决定。

本审查未发现足以证明当前用户数据库已经损坏的证据；下文“阻断”主要指照现有研究直接实施时的数据风险，以及发布前必须补齐的验证。

证据标签：

- **CODE VERIFIED**：在下述固定提交中直接核实；不表示已在手机上运行通过。
- **RESEARCH SUPPORTED**：现有研究、PRD、原始反馈或官方平台文档支持；不自动升级为 Owner 决策。
- **DESIGN RECOMMENDATION**：本次建议，供审阅后冻结。
- **DEVICE VALIDATION REQUIRED**：必须以指定构建、设备和场景验证。
- **OWNER CONFIRMED**：本次明确裁决的产品约束，归纳见第 9 节；不能据此声称具体工程实现获批。
- **OWNER DECISION REQUIRED**：仅用于未来新增实质产品冲突；D1–D5 已关闭，不再待选。

## 1. 真实基线与证据边界

### 1.1 上轮架构审查已核实的固定 GitHub 基线

| 项目 | 核实结果 |
|---|---|
| 仓库 | [cnsj-123/qqhome](https://github.com/cnsj-123/qqhome) |
| Android 分支 | `feature/life-os-v15-ui-convergence-p1-r2-final` |
| Android HEAD | [`245e1475cd1d47a4205ab49f75d5e06d22de701c`](https://github.com/cnsj-123/qqhome/commit/245e1475cd1d47a4205ab49f75d5e06d22de701c)；上轮审查远端核实与历史点一致 |
| 文档分支 HEAD | `life-os-docs` → [`73be278b80a3b31764de2fc8b733390629075542`](https://github.com/cnsj-123/qqhome/commit/73be278b80a3b31764de2fc8b733390629075542) |
| 上轮核实的最新 Android CI | [Android CI #50，success](https://github.com/cnsj-123/qqhome/actions/runs/38850752059)，同一 Android SHA；2026-10-16 12:32:54 中国时间完成 |
| CI 实际边界 | schema 生成与检查、`testDebugUnitTest`、debug APK 构建与上传成功；signed release 步骤跳过。工作流没有真机仪器测试或 Macrobenchmark 作业。[C01] |
| 数据库 | `LifeDatabase` 注解和常量均为 **Room v4**；备份格式也是 4，但它们是两个独立版本体系。[C02][C21] |

**CODE VERIFIED（上轮固定基线）。** 原审查通过远端 refs 与 GitHub Actions 接口核实，并从固定提交导出只读审查快照；没有以本地旧 checkout 的 HEAD 代替远端基线。未运行 Gradle、未构建 APK、未修改 App、Schema 或 PRD，未提交 GitHub、未创建 PR。本次是 Owner 裁决的文档修订，未再次将上述 SHA/CI 声称为远端实时状态；后续开工前重新核实。

### 1.2 材料覆盖及缺失

已核对 README、文档索引、当前开发状态、开发协作与交付约定、待核对清单、六份研究材料、Finance/Home 草案、UI 收口基线、相关统一架构及 Shopping/隐私已确认需求。六份材料均有内容可读，但有两处文件名问题：

- 任务所称《V15-R2至V16-架构与产品研究报告》，仓库实际文件是 **《V15-R2到V16架构与产品决策报告.md》**，本轮阅读的是该文件。[R02]
- 固定提交中的 Owner 记录实际是 **《2026-10-V16-Owner-决策与实施交接.md》**；旧索引指向另一个名称。[R07] 形成时的同名修订记录现已归档于文首所列文档 HEAD；旧固定提交快照不被覆盖。本轮后续说明仍只交付本地补丁。

原始反馈已找到并阅读正文：Owner 提供的私人材料《真机测试v15-r2.docx》（仅保留来源说明，不纳入公开仓库）。文中所引视频未取得；嵌入截图未逐张独立复核。本轮也没有设备操作、已安装 APK 的 SHA 证明或性能 trace，不能将“代码有适配器”表述为“七个宿主真机均支持”。

**RESEARCH SUPPORTED。** 统一架构多份文件仍标 DRAFT；Shopping、隐私需求已确认，但需求状态不证明实现。当前用户请求优先于旧研究的分期建议及旧四页 Finance IA；不重新表决 Gesture B、三主空间、先投影后缓存、身份绑定、Evidence/Proposal、可编辑 Overlay 或全部 40 项范围。

## 2. 两轮研究的独立复核与路线选择

| 审查问题 | 判断与处理 |
|---|---|
| 初版哪些仍有效 | 保留一套现金流水、区分实际资金与个人消费、按根因整合手势/编辑/查账、保留 App 内复核、先测再缓存、Home 与分享独立授权，均继续成立。[R02][R04] |
| 复审缺口哪些已补 | 后续研究已用 Owner 选择收敛整体方向，提出六条能力链、内部 Gate 和场景验收，不应被针对初版的批评整体否定。[R05][R06] |
| 哪些仍未补齐 | 原研究缺少完整不变量、迁移/恢复矩阵及无本人付款事项语义；本备忘录与本次 G0 细案已补契约，D2/D5 以最终裁决为准。独立 QueryState、迁移恢复、性能与设备仍需工程实现/验证，文档验收表不等于执行证据。 |
| 是否再做三套巨型架构 | 不需要。方向已明确；应比较风险和改动边界，集中验证争议契约，避免重新打开已关闭的投票。 |
| 一个版本、一次迁移、多 Gate 是否可行 | **一个产品版本 + 多 Gate 可行；一次对外升级路径有条件可行。** 先做 G0 原型与语义验证，再冻结 schema。已经安装过的内部数据库版本不可原地改定义；必要时保留内部迁移链。不能先承诺“必为 v5”。 |
| 是否有阻断风险 | 有，见第 3 节。现有 CI 对核心逻辑有价值，但不足以证明 Overlay/IME、宿主节点、恢复断电路径和大数据性能。内部工程真机验证必须前移，Owner 最终整体验收仍只针对完整 V16。 |

**RESEARCH SUPPORTED / DESIGN RECOMMENDATION。** 对后续研究新增假设作以下修正：

1. `postedAt ?: occurredAt` 不能直接替换旧余额时间轴，否则可能改变某笔账是否进入 Opening Anchor。先明确账户账面口径与银行核对口径。
2. 相同订单摘要不能成为相同资金交易的强证明；一个订单可多次支付、退款、分期。必须区分 `orderRef` 与 `paymentRef/refundRef`。
3. 增加 Party/Obligation 还不够；现有 Event 有效性和个人消费投影同时依赖本人付款，必须一起演进。
4. “固定负担”里的信用卡本金还款应归现金承诺/转账，不能作为消费预算再扣一次；利息和手续费另算实际费用。
5. 项目已经依赖 bundled 中文 ML Kit（`text-recognition-chinese:16.0.1`），并有本地 `OcrEngine`。F48 新工作是选图、敏感内容处理、字段提取、候选与凭据生命周期；不能把约 4MB 当成已测新增包体。[C18]
6. 原始反馈要求“自定义主题色不要默认展开”；初版 L2a 写成默认展开。按原始反馈做默认收起，保留五色网格及明确保存，不再次询问 Owner。
7. 本地 HMAC 身份摘要必须同时设计密钥丢失/换机后的退化方式；数据库恢复不等于系统权限、服务连接和旧摘要匹配能力全部恢复。

| 路线 | 收益 | 对当前目标的代价 | 裁决 |
|---|---|---|---|
| 保守局部修补 | 改动面小，录入和布局可较快改善 | F35/F36、F18、F43/F44 等仍无完整数据契约；继续堆页面状态会反复返工 | 不足以满足本次全部范围 |
| **在现有 Core 上补契约** | 复用转账守恒、Typed Room、命令事务、投影与恢复门禁；共用基础设施承载 40 项 | 需要集中处理关系、身份、恢复与查询边界 | **推荐** |
| 全面重构世界对象/事件溯源/通用手势引擎 | 长期形式统一 | 迁移和回归面大；当前 journal 不是完整事件流，不能据此重放；无必要重做全部领域 | 本次不采用 |

## 3. 值得保留的实现与必须改变的结构

### 3.1 保留的底座

**CODE VERIFIED。** 应保留并扩展：

- `Account / Entry / Transfer` 的 typed 表、分值金额与精确求和；转账双腿等额、同币种、方向相反且生命周期一致。[C03][C04]
- `FinanceRepository` 的事务命令入口与 `RestoreStartupGate`；UI 不直接写 DAO。现有 Event/EntryLink 将现金流与事项关联，个人消费由独立投影解释。[C05][C06]
- Import 的 staging、预览、重复保护、原子提交和受保护撤销；不能为加速把部分正式流水提前暴露。[C22]
- Proposal revision、确认幂等、强引用冲突和弱重复提示；原始通知不长期保存、来源默认受限。[C07][C17][C28]
- Typed backup、恢复协调与启动门禁；原图和媒体身份分离；Appearance draft 明确保存。[C31] 保留这些边界，无需把整个 App 改成事件溯源系统。[C21][C23]

### 3.2 进入 schema 冻结前的阻断点

| 优先级与触发场景 | 代码证据 | 必须改变的契约 |
|---|---|---|
| **P0：别人替我付、多人付款** | 有效 Event 必须有有效流水；本人份额不得超过有效 PAYMENT/FEE；个人消费由本人付款分配。[C04][C06] | 为外部付款提供真实经济事项依据；分开事项价值、个人承担、现金变化与义务；赠送不生应付，关系未定不默认 Gift/欠款，混合关系按分配表达。不能只删校验，也不能伪造本人 OUT。 |
| **P0：部分到账、放弃或历史结清** | 往来以 `pendingMinor == 0` 判断完成；只聚合三类 nature；取消的 ExpectedFlow 被过滤，随后可能回到金额差推算。[C09] | 明确应收/应付、结算分配、取消/豁免/历史声明与资料完整度。任务完成不等于现金证据完整；返利要有任务入口。 |
| **P0：误作废后恢复、批量改账户** | 作废会退役 Links、孤立 Event 和 Expected；journal 只有操作/版本元信息，没有完整前镜像；没有对应安全恢复命令。[C05][C08] | 按逻辑命令保存有限恢复清单与必要前值；预览影响，校验版本，原子恢复关联组。不能只清空 `voidedAt`。 |
| **P0：补入入账日、补历史或改起点后对账** | 当前余额按 `occurredAt >= balanceAnchorAt`；账簿排序为 occurred 降序、recorded 降序、ID 升序；不存在正式对账模型。[C03] | 保留旧起点语义；核对结论绑定范围、口径、版本和成员集合；新插入也必须使受影响结论失效。 |
| **P0：跨来源重复、改绑定后重识别** | `NormalizedTransactionEvidence` 临时有 extraction/channel 提示，转 Proposal 后未完整保存；sourceKey 主要表达 provider/ref，缺账号上下文与跨来源声明模型。[C07] | 小型身份/工具/绑定模型，Evidence 与 Extraction 分层，用户确认关联；原确认交易冻结账户，重新绑定不回写历史。 |
| **P0：增加证据与隐私字段后备份/换机** | 当前 typed 备份只覆盖既有表；普通 ZIP 导出；manifest 为 allowBackup=true，未见显式数据提取规则。[C21][C24] | 每个新持久对象同时有导出/导入/缺失语义/保留策略；限制系统自动备份；密钥、授权、会话单独处理。不能宣称已有端到端加密。 |
| **P1：Overlay 内编辑** | 已有半屏 `TYPE_APPLICATION_OVERLAY`，但用 `FLAG_NOT_FOCUSABLE`；已有显示、确认和 App 跳转，尚非编辑器。[C10] | 独立于窗口的复核会话；焦点/IME/系统 Back/锁屏/撤权生命周期先做工程验证，复用同一确认命令。 |
| **P1：导航与分析互相污染** | Shell 只知道 Finance 有横向页，不知道当前页能力；ViewModel 使用共享 filter；月历已有拖动进度，但缺完整释放停靠契约。[C11][C12] | 页级能力上报、局部手势执行；分离账簿/账户/分析/钻取 QueryState；月历离散状态和释放规则。[C29] |

F40 还有跨域前提：当前 Shopping 入口是占位页，通用 `LifeRelation` 的两端都引用 `LifeEntity`，不能直接把 Finance 表主键塞进去；媒体链接也需要有效 owner。[C19][C20][C32] 因此需最小 Shopping Purchase/Line 及受校验关系，或既有领域对象的一对一注册适配；不得借“复用通用表”复制现金真相。F56 的候选定义允许标签/专题视图，不要求本轮建完整 Travel/Hobbies 模块。

## 4. 推荐的目标架构与两个契约图

**DESIGN RECOMMENDATION。** 仍在当前 Android 单体与 Room 中工作。Repository 是写边界，Query/Projection 是读边界；必要的 typed 表补业务语义。Receipt 是 Proposal 的复核界面，既不是第二套事实，也不是新的账务 Owner。

### 4.1 Navigation / Gesture Ownership

```mermaid
flowchart TB
    systemGesture["Android 系统边缘返回与手势区"]
    shell["Life Shell：导航状态、遮挡层、能力协调"]
    topLayer["最上层：编辑器、复核、抽屉、Companion"]
    finance["Finance：当前空间及可消费方向上报"]
    ledger["账簿：总额、摘要、月历、流水"]
    account["账户：余额、逐笔账、对账"]
    analysis["分析：独立 QueryState"]
    calendar["Calendar：横向翻月；纵向 Month、Week、Collapsed"]
    listView["Ledger：纵向滚动；显式日期联动"]
    drawer["Drawer：首空间普通区域右滑；底栏显式入口"]
    pager["Pager：三主空间切换；非首空间右滑返回前页"]
    companion["Companion：系统手势区之外的约定区域上滑"]
    back["Back：IME、最上层、子页、模块返回"]
    systemGesture --> shell
    shell --> topLayer
    shell --> finance
    finance --> ledger
    finance --> account
    finance --> analysis
    ledger --> calendar
    ledger --> listView
    finance --> pager
    shell --> drawer
    shell --> companion
    topLayer --> back
    finance -. "能力契约，不转发原始指针" .-> shell
```

执行规则：系统边缘优先；遮挡层先于背景。按触点区域、轴向和当前能力，在 touch slop 后锁定一个 owner，直到 UP/CANCEL，不中途抢占。Calendar 横滑归 Calendar，不能以“Finance 在第一页”为由抢成 Drawer；普通内容区在账簿首空间右滑可开 Drawer，其他空间右滑归 Pager。底栏右滑单独声明 Drawer 能力；Companion 安全区域由 Shell 协调、局部 recognizer 执行，避开系统导航区、IME、Slider 和编辑器。

Shell 只消费能力与导航意图，不遍历子组件指针、不建根级万能引擎。系统 Back 先由 IME/最上层处理，再回钻取来源或模块；未完成内容保存为草稿/会话。无障碍按钮与点击入口同样可达，不依赖手势才可操作。

账簿首屏保留总额、轻量摘要、月历和流水；待确认/往来/对账为任务入口。**OWNER CONFIRMED（D1）：** 第一眼显示“纳入账户净额”；范围内负债按负数，不同币种分别展示，不以未经确认的汇率强行相加。展开范围和数据不完整项应易理解。普通摘要使用明确的收入/个人消费口径，账户详情用完整实际流入/流出。现有 `totals()` 会排除 Transfer 和统计排除项，不能拿来命名“账户全部资金流”。[C03]

QueryState 分为 Ledger、AccountRegister、Analysis、Relations 和 Drilldown；钻取携带不可变 QuerySpec 与来源返回位置。月历可见月份、选中日、列表锚点是 UI 状态，筛选与日期联动是显式动作。保存视图保存版本化条件，不保存结果流水。F62 只把自然语言转成受限、可检查的 QuerySpec，经权限过滤后执行已有查询；模糊词需澄清，不能执行生成 SQL 或获得写权限。D5 要求预留供应商无关的可选云 AI Provider，默认关闭；只在未来用户主动启用并授予用途/数据范围后才允许调用，本次无真实云接入或上传。

### 4.2 Evidence → Proposal → Receipt → Truth

```mermaid
flowchart TB
    input["白名单通知、可见详情、用户主动选图"]
    capture["有界采集：页面指纹、稳定检查、原因码"]
    evidence["Evidence Summary：来源与引用；可选凭据引用"]
    extraction["Extraction：版本、字段、精度、分歧"]
    identity["External Identity、Payment Instrument、Binding"]
    proposal["Proposal：待确认、稍后、冲突；有 revision"]
    rules["规则建议与试运行"]
    receipt["ReviewSession：App 内及跨 App 半屏共享"]
    confirm["用户明确确认或关联已有交易"]
    command["Command：权限、版本、重复声明、守恒、恢复门禁"]
    truth["Room 原子事务：唯一现金流及经济事项、关系、确认结果"]
    query["投影：余额、对账状态、往来、个人消费、分析"]
    newEvidence["已确认后新证据：补充或冲突建议"]
    manualDraft["手工 Finance Draft：用户未完成输入"]
    input --> capture --> evidence --> extraction --> proposal
    identity -. "账户建议及适用范围" .-> proposal
    rules -. "只填建议" .-> proposal
    proposal --> receipt --> confirm --> command --> truth --> query
    evidence --> newEvidence
    newEvidence --> proposal
    truth -. "已有 ID 与 revision 供核查" .-> command
    manualDraft --> confirm
    egress["用途授权、最小字段、出站检查"]
    cloudProvider["可选云 AI Provider 接口；默认关闭；本轮未接入"]
    assistantResult["本地校验后的建议或 QuerySpec；无写权限"]
    evidence -. "未来仅授权范围" .-> egress
    egress -. "未来可选调用" .-> cloudProvider
    cloudProvider -. "不可信结果" .-> assistantResult
    assistantResult -. "变更建议仍需复核" .-> proposal
    assistantResult -. "查询仍经本地权限检查" .-> query
```

Provider 表示采集宿主，SourceFamily 表示证据所属的支付/商业来源；ExternalIdentity 表示该来源下的外部账号，PaymentInstrument 表示余额、银行卡或信用工具，FinanceAccount 才是本地账务账户。来源未知就保持未知；绑定带范围、有效期与用户确认版本，提交时重新检查账户是否可用。

证据相同不代表提取相同；提取变化不创造第二笔交易。强引用只在已验证的 provider、external identity、引用类型和命名空间内成立；orderRef 只说明订单关联。弱匹配只产生可解释的候选关系。对已确认交易，新证据默认补充来源或生成变更/冲突 Proposal，禁止自动改金额、日期或账户。

`ReviewSession` 持有 proposalId、baseRevision、用户字段改动、待处理队列和保存状态；手工 Draft 与 Proposal 编辑会话分别持久化。新候选入队不覆盖正在编辑的内容。确认时同一事务写 canonical、Evidence/Proposal 关联与确认结果；唯一引用声明及幂等 operationId 防止两个入口重复提交，restore epoch 防止恢复后旧窗口提交。Entry 已作废的确认结果也应如实返回既有状态，不能因重试再建一笔。

## 5. 自动发现的实际边界与实施要求

**CODE VERIFIED。** Registry 注册七个宿主，默认均关闭；这是代码识别范围，不是实机认证。[C13][C14]

| 宿主 | 当前适配器可见范围 | 本轮证据结论 |
|---|---|---|
| 微信 `com.tencent.mm` | 指定转账/支付/收款详情指纹 | 有适配代码与测试基础；实际页面版本待验证 |
| 支付宝 `com.eg.android.AlipayGphone` | 指定账单、收支/退款详情 | 同上；订单引用必须与资金引用区分 |
| 淘宝 `com.taobao.taobao` | 已完成订单/部分退款等详情 | 不等于独立天猫 App 全覆盖；订单时间不必是付款时间 |
| 京东 `com.jingdong.app.mall` | 订单、账单、白条还款/退款等指定形态 | 还款只能形成转账候选，不能作为消费重复记账 |
| 美团 `com.sankuai.meituan` | 账单、服务订单、月付还款等形态 | 不宣称全部退款形态已覆盖 |
| 拼多多 `com.xunmeng.pinduoduo` | 已完成订单等指定形态 | 订单可多次支付；当前采用下单时间，语义需核实 |
| 云闪付 `com.unionpay` | 指定交易/退款详情 | 页面、账号身份提示与字段缺失需真机矩阵 |

现有无障碍服务做包名/活动可见窗口限制、500ms 节流、节点/深度预算和语义白名单；这值得保留，但平台 `notificationTimeout` 与节流不等于稳定采样。多条失败路径直接返回，用户看不到失败阶段。[C13][C15]

**DESIGN RECOMMENDATION / DEVICE VALIDATION REQUIRED：** 采集会话需绑定前台 package、window 和页面指纹；有限次数重采样，只在稳定且仍为同页时提取。G0 冻结次数/时限预算，超限明确返回。主动读取可由用户先发起一次性授权任务，再切回目标详情；到期或页面不符即结束。不得后台翻页、点击支付控件或全屏循环抓取。

诊断使用枚举：未授权、未连接、未开启来源、不支持宿主、不是详情页、页面不稳定、缺金额、缺时间、缺引用、疑似重复、字段冲突、候选保存失败、已进入待确认。仅保留必要的 provider/版本/时间/原因和计数，不保留页面正文。

通知目前只解析微信与支付宝，已有连接回调、脱敏诊断和 `requestRebind` 按钮；需完善进程重启后的状态、请求中/超时结果及最近成功信号，不能把按钮调用成功当成已连接。[C16] 平台要求以连接回调为依据，权限开启不保证监听器已工作。[Android 官方说明](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

Overlay 不应只删除一个 flag 就宣告完成：需要验证焦点授予/撤回、中文与数字 IME、调整高度、Back、旋转、锁屏时关闭、撤权、进程死亡和与 App 内窗口交接。`FLAG_NOT_FOCUSABLE` 本身不能接收键盘焦点；具体可编辑窗口组合须经目标机验证。[窗口官方说明](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_FOCUSABLE) 无悬浮权限时仍能在 App 内完成，但不能用这个兜底把 F44 标为已完成。

## 6. Finance 核心不变量与可复核例子

以下 D1–D5 产品语义为 **OWNER CONFIRMED**；具体字段与命令组织为 **DESIGN RECOMMENDATION**。与现有行为相同的部分须用回归测试保持，新语义在 G0 转成工程契约，不重新投票。所有金额使用币种最小单位和精确运算，禁止浮点累计、静默截断和跨币种直接相加。

| 不变量 | 可执行要求 |
|---|---|
| 唯一现金账 | 只有有效 Entry 影响其真实账户余额；Event、Obligation、Expected、Observation、草稿、预算和 Proposal 均不增加现金。 |
| 转账守恒 | 同币种双腿等额反向，一次命令一起新增/编辑/作废/恢复；手续费单独真实 OUT 并关联。允许将来双腿各有独立 postedAt，不破坏共同发生事项和金额守恒。 |
| 事项分配守恒 | 现有已关联流水的有效 EntryLink 分配和等于该流水金额；一笔流水可以分配多个事项，但不可重复完整计算。未分配状态要明确，不靠悬空 link 绕校验。 |
| 经济事项独立成立 | 无本人现金流水的真实事项可以成立；分别记录事项价值、个人承担、现金变化和应收应付。已知付款/事项事实可确认，关系仍可未定；未知份额不硬填。旧 Event 不自动推断赠送或欠款。建议用 basisMode 区分旧现金关联和新确认经济模式，投影只能选其一。 |
| 多人分摊 | 区分付款、受益与最终承担参与者；允许同事项部分赠送、部分需归还、部分未定。已分配与未分配金额可核对，未知本人承担不当 0；比例舍入保持精确总额。 |
| 赠送与欠款分离 | 确认赠送/请客：本人承担 0，不造本人 OUT/IN、无应付，可保留事项价值/商品/经历。需归还的垫付才建立本人承担与应付；关系未定不得自动选择任一种。 |
| 义务与预期有唯一权威 | Obligation 表达欠谁/谁欠我及已知金额；Expected 只表达未承诺/未确定的预期或到款计划。二者若有关联，不能在待收/待付中叠加。每个经济义务只有一个余额计算来源。 |
| 结算分配守恒 | 涉及本人账户的实际到账或还款必须引用真实 EntryLink 的可分配金额，再分配给义务；同一资金片段不得结算两次。收款方、付款方、币种、方向一致；超额进入待核对或明确的新义务，不能截断后假装完成。 |
| 三种完成状态分离 | 现金已到账、义务已清偿/豁免、历史声明已结束，分别记录依据。资料完整度独立展示；取消预期不生成现金，也不自动恢复旧的待收推算。 |
| 个人消费只记一次 | 个人消费来自已确认由本人承担的经济成本。明确需归还的外部垫付先计本人承担，后来真实还款只减少应付；已确认赠送本人承担为 0，未知关系保持待确认；退款/返利按归属修正成本，结算不是新收入/新消费；分摊事项的退款/返利先明确归属哪位参与者，不默认全部冲减本人份额。 |
| 退款保留事实 | 原付款金额和日期保留；本人收到的退款使用真实到账账户、金额和时间；其他参与方收到的退款用确认外部事实承接，不造本人 IN。成本调整按承担归属决定；无法归属或超过可解释成本的部分待核对，不自动变收入或任意负成本。 |
| 写入必须重验 | 所有编辑/作废/批量/恢复命令使用预期 revision 或同等明确版本；依赖的账户、事项、分配、绑定也需验证。现有可选 revision 参数不能作为新编辑入口的常态绕过。 |
| 恢复不能抹掉后续工作 | 保存命令组与必要历史前值、受影响 ID/版本。恢复前展示冲突及核对失效影响；转账与手续费按组处理。旧作废行缺完整恢复依据时显示不可一键恢复的原因，提供显式重新关联流程，不能猜回关系。 |

验收算例（单位元，测试中按分保存）：

- 本人付 500，最终本人承担 50，应收报销 450；到账 300、150 分别各一笔 IN。个人消费始终为 50，实际资金最终净流出 50，应收依次为 450、150、0。报销未批准时可为预期，不能谎称已有确定应收。
- 朋友请客 120，明确无需归还：事项价值 120，本人承担 0，本人现金变化 0，无应付；可以保留商品或经历，不造 OUT/IN。
- 朋友垫付 120，明确需要本人归还：本人现金变化 0，个人承担 120，应付 120；以后本人转给朋友 120，现金流出 120，应付 0，消费仍为 120。没有本人账户之间的两腿时，这笔还款不是内部 Transfer。
- 对方支付 300，其中赠送 100、本人需还 200：事项价值 300，本人承担 200，应付 200；真实还 80 后应付 120，消费仍为 200。分配和结算分别守恒。
- 只知对方付款 120，关系未确定：可以保存已知事项/付款事实；个人承担与是否应付待确认，不显示为确定的 0，不默认生成债务。
- 三人实际承担 100、80、120，共 300。只确认前两项时未分配 120；不能默认把余数计为本人。出资与最终承担分别记录；差额仅作核对线索，最终义务必须结合赠送部分和明确需归还关系确认，不由差额自动生成。
- 9 月本人消费 200，10 月实际退款 30、返利 10：9 月个人成本仍为 200，10 月调整为 -40，事项累计成本 160，原事项关联保留。若返利尚为预期，实际累计成本为 170；放弃预期不新增费用。月预算已用额可为负并解释，不静默把原月改成 160。
- 历史记录只知“已报销”，未知到账账户/日期：保留历史结清声明及来源，退出工作待办；不新增 IN，不伪造现金已核对。当前导入会阻止部分此类语义进入正式提交，需增加最小诚实承接方式。[C22]
- 信用卡消费 100 后从银行卡还 100：个人消费只有一次 100；还款是银行 OUT + 信用账户 IN。信用卡正负号语义未明确的旧账户不得仅凭 kind 自动反转金额。

### 6.1 Running Balance / Reconciliation 的专门契约

**CODE VERIFIED → DESIGN RECOMMENDATION。** 现有余额等于 opening 加所有有效、该账户、Entry.occurredAt 不早于 anchor 的 signed Entry。[C03] 旧账户逐笔余额保留这一兼容语义；日常账簿的经济发生轴不反写旧 Entry 时间：

`balanceAt(k) = openingBalance + sum(signedAmount(e), e 属于完整账户且在 anchor 后、排序键不晚于 k)`

先对完整账户账簿排序和扫描，再把余额挂到筛选后的展示行；筛选只决定看哪些行。账户账使用转账各自的真实腿，不能复用全局账簿合成的转账 OUT 行去推另一账户入账时间。日期相同用 recordedAt 与稳定 ID 决胜；展示反向时保持每行余额与同一计算顺序一致。记录只有日期精度时，显示计算顺序而不冒充银行交易先后。

- 分开 Event 的经济发生时间、Entry 的真实现金收付时间、确知的 `postedAt?` 和本地 `recordedAt`。当前 Entry.occurredAt 及旧 anchor 保持原数据语义；新读模型从正确事实取时间，具体字段映射见 G0 细案。需存精度/来源，不能复制经济日期冒充真实收付或已知入账日。
- **D1/D4：日常账簿按经济发生日，账户现金保留真实收付日，旧余额语义不静默改变。** 银行/信用卡核对显式采用确知 posted 口径及待核对集合；未知 posted 的已发生交易显示资料缺失，不自动认定 pending，也不以 fallback 混入“已入账余额”。F22 可引入必要字段和账期选择，但不因此扩张为未选 F23 的完整双视图产品。
- Opening Anchor 是有方向和边界含义的起点：新建向导明确“从零完整记录”或“从某时点余额接续”，解释当边界包含哪些交易。旧数据保持原 inclusive 规则与 opening 值。起点前的历史不能假装拥有可信逐笔余额；补录不重复扣起点已含金额。
- 修改起点或时间口径必须走预览命令：受影响账户、区间、结论、转账展示一并列出；不能静默迁移。信用卡周期还须定义当地日界、结账日遇短月规则、账期范围和还款对应；提醒不是还款凭据。
- Observation 保存用户/机构观察到的余额、账户、币种、时点/精度和口径；它只比较，不改 opening、不自动造调账流水。
- Reconciliation 保存范围/边界、口径、观测 ID、成员与版本、差额、结论及历史。新插入、改账户/金额/时间、作废/恢复、改 anchor 均触发范围影响评估；只检查原成员 revision 会漏掉新补录。建议账户账务 generation + 范围成员指纹，发生变化后保留旧结论并标“需重核”。仅改备注不必让现金核对失效。
- F20 显示“核对至某日”“起点后账面推算”“历史不足”“核对受修改影响”，不制造百分比可信度。Checkpoint 仅在测量证明需要时加入，可丢弃重建，带算法/账务 generation/anchor/口径版本，不能被备份当作权威。

## 7. 必要的持久模型、迁移和恢复设计

以下是 **DESIGN RECOMMENDATION**，表示数据责任，不是最终 DDL。逻辑概念不必一概拆成独立表；只有独立身份、生命周期、关系或可查询约束成立时才建实体。

| 数据组 | 必须持久化的最小信息 | 复用与边界 |
|---|---|---|
| 现有 Finance 核心 | 既有 Account/Entry/Transfer/Event/Link 的 ID、金额、关系与生命周期；必要 revision；category iconKey | F02 同步调整手工、候选确认、Event 校验和展示 fallback；不能把自动标题冒充用户备注。 |
| 账户/时间/账期 | 可空 postedAt 及精度/来源；anchor 口径/边界及版本；账户纳入/负债展示政策；信用卡周期配置 | 旧值不推断、不倒填；逐笔余额不落事实表。 |
| 对账 | Observation；Reconciliation 与范围/成员依据、结论、失效原因 | 不存第二本余额账；索引与摘要可以重建。 |
| 往来 | 本地 Party、事项价值/来源、付款贡献、受益/承担分配及赠送/需归还/未定状态；经确认的 Obligation、结算/历史 resolution | 四类金额不混用；Gift 不生应付，Unknown 不填 0；无现金事项不得被恢复逻辑误判孤儿。无需联系人同步或全局 Person 重构。 |
| 来源身份 | ExternalIdentity、PaymentInstrument、AccountBinding 的 scope、版本、有效区间、用户确认来源；未知/失效状态 | Provider、SourceFamily、外部账号、付款工具、真实账户分别表达。尾号/昵称只是提示，不能强合并。 |
| Evidence/Extraction | 最小来源摘要、引用类型/摘要命名空间、采集时间；提取版本、字段精度与冲突；Proposal/Canonical 关联 | 可用按版本的 typed extraction 行；不存全量节点树/通知正文。提取升级不改变 evidence identity。旧 Proposal 无原文时标不可重提取。 |
| 复核与草稿 | 手工 FinanceDraft；ProposalReviewSession 的用户改动、baseRevision、队列/恢复状态；operationId 确认结果 | 不能复用衣橱 JSON draft 来承载 Finance；临时窗口关闭不等于候选消失。系统授权和可用焦点不是可恢复业务数据。 |
| 安全恢复 | 支持命令的 RecoveryGroup、成员预期版本、必要 typed 前值/退役原因 | 只服务 F13/F14 等有限命令；历史快照不是可编辑的第二份当前 Truth，不扩张为完整 F57 任意时点回滚。 |
| 查询/预算 | 版本化 SavedQuery；可选固定/弹性分类政策；预算范围、周期、币种、金额与口径 | 预算已用额、异常提示、图表是投影。QuerySpec 可版本化序列化，因为它是用户查询配置，不能把资金事实塞进去。 |
| 附件/跨域 | MediaAsset/Resource 引用及 Finance 受限 owner 适配；最小 Purchase/Line 和金额归属引用；专题视图/关系 | F40 商品属于 Shopping，现金属于 Finance；F56 可复用 SavedView/标签形成专题，不伪造 Trip。PDF 等附件复用资源机制，不强称全是图片。 |
| 隐私/恢复代际 | 最小用途授权/策略版本、Provider 配置元信息、来源限制、恢复代际 | 云默认关闭，接口供应商无关；恢复不激活云权限、不恢复待发送请求或凭证，重新评估系统服务/绑定。 |

**无须新增业务 schema 的主要工作：** F01 金额键盘、F05 连续录入流程、F09/F16 月历与年月导航、F12 钻取入口、F25 阶段进度、局部手势、通知重连反馈。它们可能依赖其他已选持久能力，但不能各自造表。F17、F20、F51 已用统计、F54、F55 已用额和 F61 卡片内容属于投影；草稿、用户预算、核对结论和用户关系不是缓存。

还需明确全局备份的一致性边界：当前 Life OS typed payload 是单个 Room 事务快照，但导出 lease 只排斥 restore，不排斥所有普通业务写入；衣橱、Room、草稿和文件并非一个跨存储原子快照。[C30] V16 新增跨域关系后，必须在备份暂存阶段采用写入方共同遵守的一致快照协议，或捕获版本后校验并重试；媒体按内容校验，不一致就失败而非产出“成功”包。这是需封闭的并发窗口，不是已经观察到用户备份损坏的结论。

### 7.1 一次对外迁移的条件

1. G0 先冻结金额/事项、时间/anchor、身份/确认、恢复/备份四组契约，并用小型工程验证解决 Overlay 和真实宿主页风险。此时才确定最终数据库目标版本 N。
2. 对外保留当前 v4 → N 的完整升级路径；所有曾正式支持的旧版本通过现有链衔接。V16 是产品版本，不能据此命名 schema v16 或必定 v5。若内部构建已安装数据库版本，中途变表也必须递增版本并保留路径。
3. 迁移前创建可验证的恢复副本，先做空间/可读性检查；采用数据库一致快照与既有恢复协调，不能只复制有 WAL 活动的单个 db 文件。迁移事务失败保持旧数据可恢复，展示恢复入口；禁止 destructive fallback 或清库重来。Room 官方明确 destructive fallback 会永久删除相关数据。[迁移官方说明](https://developer.android.com/training/data-storage/room/migrating-db-versions)
4. 迁移只做有依据的转换：保留所有旧 ID、金额、anchor、recordedAt 和生命周期；新 posted/身份/参与方及赠送/欠款关系未知就保持未知，不把旧“代付”一律转换为应付。没有证据不得补出历史到账、强身份或完整对账结论。检查 FK、唯一约束、金额与关系不变量，再开放写入口。
5. 发布数据库与新备份格式同时冻结。旧客户端必须拒绝不认识的新格式，不能读到部分字段后假报成功。未曾发布的内部实验结构不要混入正式备份。

### 7.2 Backup 与 Restore 是每个持久变更的同一交付项

**CODE VERIFIED。** 当前备份格式 4 支持 typed Finance，旧格式兼容包含“某部分没有提供”的专门语义；旧版本恢复并不等于将缺失部分清空。现有恢复会按身份协调并退役不再有效的关系，值得扩展，不能用简单 INSERT/REPLACE 替代。[C21][C23]

V16 必须将“未提供”“明确空集合”“格式损坏/必需字段缺失”区分开。导出声明覆盖面和 schema/格式版本；新字段逐项纳入 exporter、validator、importer、applier、rollback 与 health check。历史 v1 无 Life OS 部分、旧包无新关系部分时，按文档化兼容策略保留/协调现状并报告；不得静默清除。涉及新经济模型而无法兼容的恢复预览，应明确冲突并拒绝部分提交。

恢复必须整体协调数据库、媒体和必要配置：暂存→校验→提交/文件发布→健康检查→开放业务；在每个边界注入失败和进程终止，验证恢复到完整旧态或完整新态。新图标、草稿、会话改动、绑定记录、规则、Gift/需归还/Unknown 分配、事项价值、预期/义务、结算与历史声明、预算和跨域关系均需覆盖；无现金事项不能因没有有效 EntryLink 而被退役。临时截图、可重建缓存、系统权限、活跃窗口不进入权威备份；缺原图显示 source missing，不能删掉业务事实。

建议恢复递增 `restoreEpoch` 并让旧确认窗口失效；恢复中的后台采集/自动保存同样服从启动门禁。Keystore HMAC 的密钥材料不可导出，因此不能假设恢复数据库后新设备还能生成同一摘要。[Keystore 官方说明](https://developer.android.com/privacy-and-security/keystore) 建议保留历史身份引用但把旧设备匹配范围标为不可用，重新确认绑定；密钥版本进入摘要命名空间，不降级为不带作用域的尾号匹配。

## 8. 全部 40 项的能力分组与内部 Gate

**DESIGN RECOMMENDATION。** 统一基础设施先于功能页。下表每个已选编号只列一次；六条能力链共享 G1 的数据与命令契约，全部进入 G7 最终验收。Gate 是依赖检查点，不是另发 V16.1/V16.2，也不是让 Owner 每做一小项就装一次 APK。

| 能力链 / Gate | 功能完整含义 | 复用与关键依赖 | 必须可见的验收结果 |
|---|---|---|---|
| 录入查账 G2 | F01 金额优先＋计算器键盘 | 一个金额解析器、编辑器 | 18＋12.5 得 30.50；非法/溢出拒绝；保存不被 IME 挡住 |
| G2 | F02 备注选填、详情渐进展开 | 全命令与 Event 校验同步 | 手工及候选均可空备注；展示 fallback 不写成用户备注 |
| G2 | F03 最近账户＋图标分类快选 | 既有账户分类；iconKey | 账户默认值清楚；图标重启/恢复后保留 |
| G2 | F05 连续补账 | 复用保存命令与 Draft | 锁定日期/账户始终可见；连续保存各一笔，失败不跳过 |
| G2 | F07 未完成草稿恢复 | FinanceDraft；G1 | 切 App/杀进程后恢复；草稿不计余额 |
| G2 | F09 月历与流水连续视图 | 局部手势与日期查询 | Month/Week/Collapsed 松手停靠；翻月不误开 Drawer |
| G2 | F10 日期/金额/账户/分类组合查账 | 独立 QuerySpec | 条件可组合，返回位置保留；不改分析条件 |
| G2 | F11 保存常用视图 | SavedQuery | 重开视图得到当前事实的查询结果，不复制流水 |
| G2 | F12 统计钻取构成流水 | 口径一致的 Drilldown | 明细按相同口径精确解释图表值，返回不丢条件 |
| G2 | F16 年月网格＋最近月份 | 纯导航状态/偏好 | 可直接到去年指定月，最近访问可用 |
| 余额导入 G3 | F17 账户逐笔余额 | 全账户 Derived Projection | 改筛选不改余额；补旧账后后续余额正确 |
| G3 | F18 对账工作台 | Observation/Reconciliation | 保存差额与核对依据；后续改账明确失效 |
| G3 | F19 余额起点向导 | anchor 契约及预览 | 从零/余额接续分明，旧账不重复扣款 |
| G3 | F20 余额可信度提示 | F18/F19 投影 | 核对/推算/不足/失效四种状态真实可解释 |
| G3 | F22 信用卡周期与还款核对 | 转账、账期、必要 posted 数据 | 刷卡消费一次；还款双腿可按各账户核对 |
| G3 | F24 多余额观测点比较 | 复用 Observation | 区间差额可定位，不自动改 opening 或生成平账 |
| G3 | F25 分阶段导入与摘要 | 既有 staging/事务 | 阶段和行错误清楚；取消/失败无半批正式账 |
| 往来凭据 G4 | F13 作废记录与安全恢复 | RecoveryGroup、命令 | 找得到作废项；转账/关系整组恢复或说明冲突 |
| G4 | F14 批量分类/标签/账户修正预览 | 版本化批量命令；恢复 | 预览笔数/金额/余额/核对影响；冲突不部分提交 |
| G4 | F32 附件与凭据跳转 | 共享 Media/Resource＋权限 | 原图一份、多处引用；缺源可见，备份往返可找回 |
| G4 | F33 独立往来工作页 | Event/Obligation/Expected 投影 | 待收/待付/报销/返利有统一任务入口，无重复账 |
| G4 | F34 部分报销/分次到账 | 结算分配 | 500/450/300/150 算例逐步成立 |
| G4 | F35 外部付款关系：赠送、需归还、待确认 | 事项价值/承担/现金/义务分离 | Gift 成本 0 无应付；需归还才有义务；Unknown 不填 0；还款不二次消费 |
| G4 | F36 多人不等额分摊 | 付款/受益/承担分离；关系按份额 | 支持部分赠送与部分需归还；金额/比例守恒，未定份额和舍入清楚 |
| G4 | F37 关联部分退款 | 原付款与退款真实 Link | 跨月/跨账户不改原付款；个人成本正确下降 |
| G4 | F38 返利预期/部分到账/放弃 | Expected＋结算/结束依据 | 未到账不增收入；放弃不造支出，不重新冒出待领 |
| G4 | F40 商品成本时间线 | 最小 Shopping Purchase/Line＋Finance 分配 | 一笔付款多商品、多次付款/退款/返利可解释；未分配成本可见 |
| 发现复核 G5 | F43 多来源同笔关联 | 身份、Evidence、Proposal | 同笔经可靠关联只入账一次；同订单多付款不误合并 |
| G5 | F44 跨 App 小票修改保存 | 共享 ReviewSession、Overlay IME | 外部宿主上修改账户/分类/备注后保存；回 App 同一结果 |
| G5 | F45 规则建议与试运行 | 既有 typed RuleEngine | 建议先展示命中/未命中；规则变更不回写历史、不自动确认 |
| G5 | F46 主动读取当前详情 | 同一白名单有界采集器 | 明确一次读取成功或原因；不后台翻页 |
| G5 | F47 连续复核与稍后恢复 | 会话队列、Proposal revision | 新候选不覆盖编辑；关窗/进程重启后能继续 |
| G5 | F48 用户选图 OCR→候选 | 复用本地 OCR、Evidence、Media | 只处理所选图；字段可改；明确确认后才入账 |
| 理解与携出 G6 | F51 固定负担/弹性消费 | 可选分类政策与消费投影 | 不增加每笔必填；本金还款不挤占消费额度 |
| G6 | F54 温和异常检查 | 只读规则与来源解释 | 重复扣费/迟到返利/余额跳变是可核查线索，不自动作废 |
| G6 | F55 轻量弹性预算 | 用户预算＋稳定退款/份额口径 | 只启用所选类别；不用预算仍可记账；退款归期明确 |
| G6 | F56 项目/旅行/兴趣专题成本 | 标签/SavedView；存在真实对象时关联 | 跨月跨分类汇总同一现金/成本事实，专题重叠不重复算总额 |
| G6 | F60 可读账单＋完整数据导出 | 查询/口径、typed 导出与关系 | CSV/可读账单可核算；完整导出保留 IDs、关系与版本 |
| G6 | F61 可选消费生活回忆 | 真实 Home provider＋授权 | 默认不上 Home/分享；选定范围后可见真实内容，撤销后消失 |
| G6 | F62 自然语言查账 | 受限 QuerySpec、权限层 | 显示解释条件与构成；歧义提示；无写入/执行 SQL 权限 |

未选编号不能借“基础设施”扩成新产品包。必要的诊断、绑定、统计口径、备份可见性以及历史结清承接，分别服务当前已确认方向和 F43–48、F12/F55、全局备份反馈与导入安全；不据此声称完整交付 F41/F42/F49/F59/F39。F13/F14 只要求有限恢复机制，不扩张为完整 F57。D1–D5 已确认的必需语义不再待选；若出现新的范围冲突须明确报告，不能保留编号却用占位页验收。

## 9. D1–D5 已确认的最终产品裁决

**OWNER CONFIRMED。** 来源为本次 Owner 原文，以下为归纳，替代原“推荐值/待批准”表；不将工程方案视为 Owner 原话。D1–D5 不再待选，本次批准不等于启动编码。

| 决策 | 状态 | 已确认边界 |
|---|---|---|
| D1 总额与时间口径 | 批准 | 首屏“纳入账户净额”；纳入负债按负数、币种分列；经济发生日记消费、确知入账日对账、现金保留真实收付日。旧起点与计算语义不静默改变；范围和不完整状态可理解。 |
| D2 关系与四类金额 | 修订后批准 | 外部付款不等于欠款；赠送/请客本人承担 0，无本人现金和应付；需归还垫付才形成承担与义务；支持混合分摊和关系未定。事项价值、个人承担、现金变化、应收应付分别表达；允许无本人流水事项与资料不完整的历史结清，不造 OUT/IN。 |
| D3 商品与专题 | 批准 | F40 最小真实商品/购买明细，付款、部分退款、返利、成本变化及关联流水；F56 专题/标签/保存视图；不做完整 Shopping/Travel/Hobbies 重构前置。 |
| D4 消费与退款归期 | 方案 A 批准 | 消费归经济发生月，实际退款/返利归实际发生月并关联原事项；不静默改写历史月份口径；允许负调整/负预算已用并解释；现金收付日期真实。 |
| D5 隐私与可选云 AI | 修订后批准 | 本地 OCR/受限查询优先，独立用途授权，敏感库不交系统默认备份；主动可验证加密备份、明文导出风险提示。必须预留供应商无关的可选云 Provider，默认关闭、主动启用授权、最小数据、可撤销、断云不影响本地、输出只作建议/待确认；本轮无接入/上传许可。 |

D5 的接口输入输出、授权及出站检查、撤销、错误退化、备份权限处理见 [G0 细案第 5–6 节](V16-G0领域契约与实施交接方案.md)。本轮只提交文档契约，不选择供应商、不获取密钥、不发送真实数据。

具体表名、索引、迁移细节、Overlay 参数和缓存属于工程设计责任；工程细案与验证证据尚待审查，不能把它们重新列为 D1–D5 的产品待决事项。

## 10. 每个 Gate 的可验证退出标准

所有标准都是**未来实施验收条件，尚未在本轮执行**。迁移、金额、权限与恢复的失败不能用平均性能或截图抵消。Gate 退出记录固定代码 SHA、测试数据版本、设备/系统信息和未解决项。

| Gate | 依赖及施工内容 | 退出证据 |
|---|---|---|
| **G0 契约与可行性** | 固定 40 项；落实已确认 D1–D5；审查 G0 细案并验证关键假设 | 四组契约：金额/赠送/欠款/未知、经济/现金/入账时间与 anchor、身份/确认、恢复/隐私/可选云接口；第 6 节及 G0 细案 T01–T12 有期望值。目标 vivo 及另一类 Android 环境完成可编辑 Overlay/IME、一次采集与恢复故障的最小工程验证。建立 R2 性能基线并冻结改进/回归容差；没有测量就不能声称可达某耗时。 |
| **G1 共同数据底座** | G0；最终 typed schema、命令、恢复、备份与权限 | 全部已支持数据库版本→N 的 populated fixture 迁移；旧 ID/金额/anchor/recordedAt 守恒。全套新实体备份往返、缺段/空段/坏包/新包拒绝旧客户端策略验证。导出与普通跨域写入并发时快照仍自洽；双击确认、并发编辑、恢复后旧会话被挡；外部付款/分配/义务算例通过。每个发布过的版本定义固定。 |
| **G2 录入、IA 与手势** | G1 的 Draft/Query/Category 契约 | 表中 10 项端到端用例通过；所有空间与方向、系统边缘、Calendar、Slider、IME、Companion、Drawer、Back 的冲突矩阵可复现。独立 QueryState 与钻取返回保持；进程死亡后 Draft 恢复；字体/主题/长文本及 reduced motion 可用。 |
| **G3 余额、核对与导入** | G1；可与 G2 部分并行 | 对 anchor 前/边界/后、同时间、跨日/跨月、两腿不同 posted、空 posted、补录、改账户、作废/恢复验证；筛选前后同一 Entry 的余额相等。核对后插入历史记录使相关结论失效。导入错误行可定位，取消与故障后无半批正式账，完整摘要与余额可核对。 |
| **G4 往来与跨域关系** | G1；引用 G3 核对失效机制 | 报销分次到账、赠送、需归还垫付、混合分摊、关系未确定、部分退款、返利放弃和历史声明全部覆盖；每次变更现金与个人消费各有独立期望。整组恢复/批量修正冲突拒绝；商品分配和附件恢复无悬空引用、不重复原图。F40 真实可用，F56 不依赖假 Trip。 |
| **G5 发现与复核** | G1/G4 稳定确认与关系命令 | 七宿主逐个记录包版本、页面形态和成功/不支持/缺字段/不稳定结果；同订单多支付、同笔多来源、两外部账号、重绑、重复/冲突、已确认后新证据逐项验证。Overlay 实际编辑保存、锁屏、撤权、旋转、IME、队列、杀进程均验证；通知真实连接/断开/重连有结果。OCR 离线、取消、拒绝权限、原图清理与保留凭据均通过。 |
| **G6 分析、专题与携出** | G2–G5 的稳定读取、关系及权限 | 七项与明细守恒；跨月退款/结算不双算；专题重叠有去重语义；异常只提示。导出保留币种、日期口径、原 ID/关系，可读表与完整数据可对账。Home 真实内容接通，关闭授权即撤回；自然语言越权/写指令/提示注入被拒绝；可选 Provider 用离线假实现验证默认关闭、最小范围、撤销和无写权限，不接真实云。 |
| **G7 完整 V16 发布验收** | G0–G6 全部通过，全部 40 项在同一候选构建可用 | CI、迁移/恢复故障矩阵、隐私扫描、性能报告和目标机测试绑定同一 SHA；Owner 集中走完整真机清单。不得用模块单测、模拟器成功或“可点入口”替代完整流程。所有已选项逐项签收，数据/隐私阻断未清零不发布。 |

当前仓库已有 Finance 核心、关系生命周期、导入、适配器、Proposal 隐私、迁移和恢复并发/故障测试，可扩展而非全部重写；迁移测试中包含 Robolectric，不能把它当目标设备真实升级证明。[C25] CI #50 的成功只说明当时已配置的工作通过，不证明本表新条件已满足。

原始反馈还需加入 G7 共享清单：冷启动白屏与 Finance 首开、首页真实照片/回忆数据、全局字体、背景真实虚化及能力不足时明确降级、自定义色默认收起/五色网格/保存取消、紧凑账户和日期层级、全局备份入口与覆盖说明。这些是已给出的 Shell/Home/Appearance 要求，不能因不占 40 个 Finance 编号而遗漏。当前 Home 默认 provider 返回空内容，必须接真实查询；开发演示样本不能混进用户正式内容。[C26]

可选六个月 Planning Horizon：月 1 聚焦 G0；月 2 聚焦 G1；月 3 聚焦 G2/G3；月 4 聚焦 G4；月 5 聚焦 G5 并推进 G6；月 6 聚焦 G6/G7 与回归。**这是资源与风险讨论窗口，不是承诺日期或按月发版。** G0 实测结果决定重估；能力可以在契约冻结后并行，未通过依赖 Gate 不得宣称下游完成。

## 11. 性能测量协议与隐私验证

### 11.1 先建立基线，再决定优化

**CODE VERIFIED。** 当前 ViewModel 对 snapshot/filter/error/busy 组合执行多种投影；写入后还可能主动读 snapshot。Import 提交逐行复用命令，分类、标签和完整校验的成本值得测。无障碍主回调执行有限树读取。它们都是测量候选，不是已证实“卡顿元凶”。[C11][C13][C22] 启动绘制门控也不能单独解释反馈中的全部白屏时长。[C27]

**DESIGN RECOMMENDATION / DEVICE VALIDATION REQUIRED。** G0 建立以下协议，后续同条件比较：

| 项目 | 测法与输出 |
|---|---|
| 可比环境 | 固定 R2 SHA 和候选 SHA、同签名/构建类型、设备型号/系统、刷新率、分辨率、编译模式、电量/温度、动画设置和数据快照。性能使用可分析的 release/benchmark 配置，不能拿 debug 与 release 直接比较。 |
| 数据档 | 小库约 100 笔、脱敏实际规模库、10k 和 100k 合成压力库；配多账户、长标签树、跨年记录、部分到账/多分配、附件和待确认。它们是拟定测试档，不是已测支持上限。 |
| 启动与首开 | 记录冷/温启动 TTID、TTFD；分别测恢复检查、数据库打开/迁移、Home 首次真实内容、Finance 导航到首个可交互列表。首次安装/升级与日常启动分开。 |
| 交互 | 月历收展/翻月、快速滚动、三空间切换、Drawer/Companion、编辑器 IME、Overlay 修改。用 Perfetto/帧时间与交互 trace，报告帧超预算比例和卡顿位置，不凭肉眼估 FPS。 |
| 计算与导入 | 分离文件读取、解析、映射、重复检查、校验、数据库提交和首屏刷新；测全账户排序/余额扫描、个人消费/往来、QuerySpec、批量修正。记录时长、CPU、峰值内存、GC、查询次数、读取行数。 |
| 备份与采集 | 测有/无媒体的导出、恢复、校验阶段及空间峰值；测无障碍每次节点数/读取时间/重试次数、通知→Proposal→复核就绪、OCR 首次/后续及大图内存。日志只写计数和耗时。 |
| 重复与判定 | 普通交互每组至少 30 个有效样本，冷/温分开；重型导入/恢复至少 5 次并保留逐次值，不用少量样本吹 p95。报告中位数、适用时的尾延迟、波动和异常 trace；控制热节流。G0 根据用户可感知任务和测量波动，记录各场景的目标、最大允许回退及审核人，随后冻结。 |

平台建议用 Macrobenchmark 测启动和用户交互，使用适合测量的构建与设备环境。[Android 性能测量说明](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) 本轮无实测基线，因此不承诺 500ms 启动、60/120 FPS 或某固定导入秒数。

优化顺序：先消除重复全量投影/读取和无效重组，建立可共享的只读索引与投影输入版本，再按 trace 优化 SQL/批处理与查询粒度。Running Balance 首先每账户一次排序、一次扫描；只有实测仍超预算才提 checkpoint。任何缓存都必须通过随机历史改动后的“缓存结果 = 从完整 Truth 重算”对照测试。

### 11.2 隐私验收不是只看 UI 隐藏

**CODE VERIFIED / RESEARCH SUPPORTED。** 当前 Proposal sanitizer 会清空 evidenceText，保留受控描述；旧导入 staging 另有 rawPayload，二者不能混为“全部没有原文”。[C17][C22] Android manifest 允许系统备份，平台默认可能包含大部分应用数据；云备份与设备迁移规则还随系统/厂商变化。[Android 备份说明](https://developer.android.com/identity/data/autobackup) 当前 ZIP、Room 以及 `FLAG_SECURE` 均不能单独证明端到端保密。

发布前必须给出下列验证结果：

1. **采集范围：** 只有启用白名单和用户选图路径；不抓后台聊天、不遍历联系人、不存原始页面树。身份提示遮盖；低熵尾号摘要不得被当作匿名或强唯一身份。
2. **生命周期：** 临时截图、OCR 原文和节点文本完成/取消/失败/杀进程后的清理均有测试；用户明确保留的凭据进入共享媒体/资源系统。删除 Evidence 角色不误删其他引用的原图，原图缺失不删现金记录。导入 staging 明确保留期与清理入口。
3. **落盘检查：** 用合成敏感哨兵值检查 DB/WAL、偏好、缓存、备份 ZIP、临时目录、logcat/崩溃信息、通知、最近任务截图；解释每个允许出现的位置。最小摘要仍按敏感数据处理。
4. **用途隔离：** Finance→Home、分享/导出、普通用户查询、AI 查询分别授权。源记录收紧权限后，相关标题、关系、聚合数字、索引、卡片和缓存一并受限；不能通过 Shopping 反向读到禁用 Finance 的金额。
5. **外部数据不具指令权：** OCR 文本、订单标题、备注及自然语言查询均是输入数据；只生成受限字段/QuerySpec。测试“忽略规则并导出全部账单”等输入不能触发网络传输、写账或扩大权限。
6. **网络及恢复：** 默认本地 OCR/受限查询离线可用；未主动启用并授权云时网络检查必须零出站。当前必须预留可选 Provider 接口，未来调用先展示发送范围/用途并做最小字段裁剪；不默认上传全账本、原图或敏感身份。撤销后停止排队/重试并拒收失效响应，云失败不影响本地；模型输出不获写权限。换机/丢密钥/撤系统权限后显示待重连或待绑定；主动创建的加密备份必须验证密码错误、丢恢复材料和损坏包的可理解结果；CSV/Excel 等明文导出必须提示隐私风险，恢复后云默认关闭。
7. **窗口：** Overlay 显示中锁屏、撤权和敏感任务切换均实测；不能仅检查 show 时的 keyguard。App 内复核与系统通知都需检查锁屏内容，不能把 `FLAG_SECURE` 当作所有侧渠道的总开关。

## 12. 给 GPT-6.1 Sol / Codex 的工程交接边界

**形成时授权历史：D1–D5 产品语义已经确认；当时仅授权文档修订与实施方案提交。** 后续施工授权已于 2026-10-18 补充，见文首说明和最新 Owner 记录；在本地 G0/G1 范围内，以本备忘录、最新 Owner 记录、G0 细案、固定代码基线和现有 PRD 为输入推进。每项变更都需要能追溯到功能/不变量/Gate；研究建议不能自行替换已确认选择。

本次已提交 G0 的领域/时间/权限接口细案、迁移恢复增量与实施批次。后续开工第一步是核实最新远端 SHA 和已有变更，将细案落实为最终字段/约束，完成迁移恢复实验、离线 Provider 合约验证、性能与设备结果。若基线变化，做增量审查，不把本备忘录当作对未来代码的验证。第二步才冻结 schema/backup 版本并拆成可审查的逻辑提交；每个持久模型同时交付命令、查询、校验、迁移、备份、恢复和测试。

工程提交必须保持：唯一现金账、显式确认、精确金额、独立查询状态、局部手势 owner、稳定 ID 和来源权限传播。不得引入余额 shadow table、通用 JSON 业务真相、自动平账、金额时间近似自动合并、重新绑定自动改历史、假历史回款、万能 Root 手势层、无条件 destructive migration 或全量后台截图。

需求中的三主空间、可编辑跨 App 半屏、主动读取、全部 40 项和最终集中真机验收保持不变。无权限兜底、旧设备降级和未支持页面的明确失败属于可靠性要求；不能用这些路径替代目标能力的实现验收。厂商原子岛仅保留扩展接口，不成为 V16 依赖。

原审查轮次交付修订备忘录、Owner 决策记录、G0 细案与实施交接方案；当时没有生成代码补丁、修改 App/Schema/PRD、构建、发布、接入云服务或上传数据。本轮仅更新文档与交接状态，未执行工程测试；后续本地施工遵守 2026-10-18 授权范围，不能将历史审查输出计为验证通过。

## 附录：固定提交证据索引

下面代码链接固定到本轮 Android SHA；文档链接固定到本轮 docs SHA。行号定位审查入口，相关结论来自同文件及调用链阅读。外部平台资料只用于核实具体机制，没有重做竞品研究。

### 代码

[C01]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/.github/workflows/android.yml
[C02]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/data/database/LifeDatabase.kt#L45
[C03]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceProjection.kt#L40
[C04]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceIntegrity.kt#L30
[C05]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceRepository.kt#L133
[C06]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinancePersonalProjection.kt
[C07]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/NormalizedTransactionEvidence.kt
[C08]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceIntakeEntities.kt
[C09]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceRelationsProjection.kt#L19
[C10]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/finance/FinanceReceiptOverlay.kt#L115
[C11]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/finance/FinanceViewModel.kt#L62
[C12]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/components/LifeFunctionPages.kt#L52
[C13]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/acquisition/FinanceAccessibilityService.kt#L42
[C14]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/acquisition/HostDetailAdapters.kt
[C15]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/acquisition/SemanticDetailReader.kt
[C16]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/finance/FinanceDiagnosticsCard.kt#L31
[C18]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/data/ocr/OcrEngine.kt#L14
[C19]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/modules/ModuleLandingScreen.kt
[C20]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/core/LifeRelationEntity.kt#L18
[C21]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/data/backup/BackupManager.kt#L23
[C22]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceImportRepository.kt
[C23]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/data/backup/FinanceV4BackupRecovery.kt
[C24]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/AndroidManifest.xml#L11
[C25]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/test/java/com/qq/closie/life/finance/FinanceV4MigrationTest.kt
[C26]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/home/HomeUiState.kt
[C27]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/MainActivity.kt

| 标号 | 主要用途 |
|---|---|
| [C01] / [C02] | CI 工作范围、Room 实际版本 |
| [C03] / [C04] / [C05] / [C06] | 余额、守恒、命令与生命周期、个人消费 |
| [C07] / [C08] / [C09] | 临时提取模型、持久候选/journal、往来推算 |
| [C10] / [C11] / [C12] | Overlay、共享查询状态、Shell 手势 |
| [C13] / [C14] / [C15] / [C16] | 采集、适配器、语义白名单、通知诊断重连 |
| [C18] / [C19] / [C20] | 既有 OCR、占位模块、跨域关系约束 |
| [C21] / [C22] / [C23] / [C24] / [C25] | 备份/导入/恢复、manifest、迁移测试 |
| [C26] / [C27] | Home 真实内容缺口、启动阅读入口 |

[C17]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceProposalPrivacy.kt
[C28]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/finance/FinanceAutomationRepository.kt#L76
[C29]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/finance/FinanceModuleScreen.kt#L204

补充入口：[C17] Proposal 脱敏；[C28] 确认事务；[C29] 月历滚动处理。

[C30]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/data/backup/BackupExporter.kt#L51
[C31]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/ui/lifeos/settings/AppearanceViewModel.kt
[C32]: https://github.com/cnsj-123/qqhome/blob/245e1475cd1d47a4205ab49f75d5e06d22de701c/app/src/main/java/com/qq/closie/life/media/MediaLinkEntity.kt#L16

补充入口：[C30] 导出一致性边界；[C31] Appearance 编辑草稿；[C32] 媒体 owner 约束。

### 六份研究与 Owner 记录

[R01]: https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/02-调研/专题调研/2026-10-V15R2至V16-研究/README.md
[R02]: https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/02-调研/专题调研/2026-10-V15R2至V16-研究/V15-R2到V16架构与产品决策报告.md
[R03]: https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/02-调研/专题调研/2026-10-V15R2至V16-研究/记账功能候选清单.md
[R04]: https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/02-调研/专题调研/2026-10-V15R2至V16-研究/案例与证据索引.md
[R05]: https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/02-调研/专题调研/2026-10-V15R2至V16-研究/V15R2-V16-初版研究复审意见.md
[R06]: https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/02-调研/专题调研/2026-10-V15R2至V16-研究/V16-决策收敛研究-40项范围与实施边界.md
[R07]: https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/06-路线图/2026-10-V16-Owner-决策与实施交接.md

[R01] 目录与材料关系；[R02] 初版研究；[R03] 40 项完整定义的原始候选表；[R04] 案例索引；[R05] 初版复审；[R06] 后续收敛研究；[R07] 已存在的 Owner 交接记录。R07 是旧固定提交历史记录；当前产品取舍以文首仓库内最新 Owner 记录所归纳的明确裁决为准；私人原文由 Owner 保管。

原审查快照、验证记录及私人真机文字提取由 Owner 本地保管，不上传私人原始材料；可公开的固定提交代码与研究证据见本附录。私人目录与提取文件不再作为仓库链接。

相关约束原文：[Finance 草案](https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/03-产品需求/草案/财务-v0.2.md)、[Home 草案](https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/03-产品需求/草案/首页-v0.1.md)、[UI 收口基线](https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/05-界面与体验/2026-10-UI与交互收口基线-v0.1.md)、[领域归属](https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/04-统一架构/04-领域归属与跨模块共享.md)、[Shopping 已确认需求](https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/03-产品需求/已确认/购物订单与价格-v0.1.md)、[隐私已确认需求](https://github.com/cnsj-123/qqhome/blob/73be278b80a3b31764de2fc8b733390629075542/docs/life-os/03-产品需求/已确认/隐私与AI权限-v0.1.md)。
