# Life OS V15 UI Convergence P1 — 待独立 Review

日期：2026-10-15。交付对象是本地代码补丁，不是已构建 APK。实现包含 P1 UI 收口及追加的可见交易详情采集；字体按 Owner 后续要求扩展至全应用。未执行 Gradle、KSP、Room generation、单元测试、instrumentation 或真机安装。

## 1. 实际基线与提交

- Repository：`cnsj-123/qqhome`。
- 指定并已核对的起点：`c60230cd33af57039aa907cfe49fce67f4941f48`，对应 `feature/life-os-v15-final`；工作分支 `feature/life-os-v15-ui-convergence-p1`。
- 文档参考：`origin/life-os-docs` 的 `ba86d42` 快照。未修改或推送该分支。
- 在独立 checkout 完成；原 `qqhome` 工作区既有的暂存删除状态未改动。
- 本机未配置提交身份；本系列使用仅对提交命令生效的 `Codex <codex@local.invalid>`，未修改全局 Git 配置。

| 提交 | 内容 |
| --- | --- |
| `f6c2bfb` | 全应用 Home A 字体角色、字体资源、字形回退及引用替换 |
| `307fd8c` | Home A 排版、启动首帧等待、伙伴手势反馈与系统边缘避让 |
| `352b50f` | Finance 紧凑账户、连续编辑、草稿及周/月历边界 |
| `ac6b127` | 外观统一草稿、一次保存、背景效果与预览共用计算 |
| `5556fa0` | 旧账业务状态识别、分组预览与正式写入限制 |
| `4d294d8` | 可见详情采集边界、显式开启设置、规范化证据 |
| `1dfeae1` | 微信已收款转账详情适配器 |
| `993d2ee` | 共用 Proposal 审核、来源展示及弱重复人工核对 |
| `d189678` | 字体预览说明同步、原生金额字重及许可文本空白整理 |
| 本报告所在提交 | Review 说明与合并真机验收清单 |

按整个系列审查；中间提交按职责拆分，未逐提交构建。补丁通过二进制 stdout 导出，不使用 PowerShell 文本重编码。导出范围严格为上述起点至最终 HEAD，文件为 `life-os-v15-ui-convergence-p1.patch`。未 push、merge、远端 rebase 或创建 PR。交付后等待独立 Review。

## 2. 文档与讨论阅读清单

文档目录均相对于该文档快照的 `docs/life-os/`；这里列出本轮实际阅读范围，不代表全库每份文档都已审阅。

| 范围 | 阅读及约束 |
| --- | --- |
| `README.md`、`文档索引.md`、`待核对清单.md` | 按已确认 PRD、架构、草案、讨论的顺序判断权威；讨论中的历史命令不是本轮执行指令 |
| `03-产品需求/已确认/隐私与AI权限-v0.1.md`、`跨模块数据与事件-v0.1.md`、Capture 相关确认需求 | 本机识别不构成 AI 授权；采集只能提出候选；专业领域拥有事实 |
| `03-产品需求/草案/首页-v0.1.md`、`财务-v0.2.md` | 首页安静投影、事实与计划分离；逐笔复核、真实账户流与个人成本分离 |
| `01-讨论记录/原始讨论/home讨论.md`、`finance讨论.md` | 首页空态与留白；代拍、报销、分账语义；不能用批量未解释流水取代即时复核 |
| `01-讨论记录/开发阶段记录/2026-10-Android正式开发-Shell与架构审查上下文.md` | 单 Root、专业模块边界、恢复门禁、禁止虚构业务数据 |
| 同目录 `2026-10-Finance开发启动-F1与可覆盖更新讨论.md` | 实际内容含 Shell 与开发讨论；读取其上下文，不照搬旧基线、旧提交及旧字体规定 |
| 同目录 `2026-10-V14-V15真机反馈与Finance自动发现讨论.md` | 小票、账户密度、主题统一、四页 Pager、月历折叠、通知覆盖反馈及后来否定的过度采集方案 |
| `04-统一架构/02-数据分层与事实生命周期.md`、`03-命令流与查询流.md`、`04-领域归属与跨模块共享.md` | Proposal、Evidence、Draft、Truth 分离；确认时重新校验；Import staging；跨域只引用 |
| 同目录 `07-存储模型与API边界.md`、`09-媒体架构.md`、`10-安全与隐私.md`、`12-架构不变量与工程红线.md`、`14-领域归属注册表.md` | Repository 写入、媒体身份与 Evidence role 分离、隐私继承、唯一 Finance owner |
| `05-界面与体验/`、`06-路线图/` | 此快照仅占位文件，没有可替代 Figma 的已上传新规范 |

同时检查当前代码的实际调用链、现有 Finance v4 文档与 CI 配置。没有把讨论中的旧自动银行/SMS方案恢复进来。

## 3. 外部研究、采用与拒绝

| 实际访问来源 | 本轮判断 |
| --- | --- |
| [Compose Bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets)、[Insets](https://developer.android.com/develop/ui/compose/system/insets-ui) | 保留单一 ModalBottomSheet 生命周期，正文滚动与底部操作分开；使用消费型 IME padding，避免手算并叠加键盘高度。拒绝给普通字段再套一层 Sheet |
| [Compose 手势](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)、[Android 启动迁移](https://developer.android.com/develop/ui/views/launch/splash-screen/migrate) | 手势按起点、方向、消费顺序仲裁；首帧等保存的外观完成合成，并设 1.5 秒故障上限。没有凭静态修改宣称解决设备闪烁或量化掉帧 |
| [Android AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo) | `root.window` 元数据需要 retrieve-interactive-windows flag；只检查当前 root 对应窗口是否 active/focused/application，不枚举其他窗口、不执行动作 |
| [TextView 字体 API](https://developer.android.com/reference/android/widget/TextView)、[Material3 Text 源码](https://android.googlesource.com/platform/frameworks/support/+/3a4466973420cc644ccbda8179f5becf786d8b35/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Text.kt) | 原生悬浮小票与 Compose 使用同一角色；Noto 可变字重显式设置。包装器保留 Text 参数、布局回调与 AnnotatedString 样式 |
| [Actual Budget 导入](https://actualbudget.org/docs/transactions/importing/)、[API](https://actualbudget.org/docs/api/reference/) | 采用预览、字段确认和稳定来源标识的幂等思路；拒绝照搬仅日期/金额接近就自动合并或替换日期的方式 |
| [GnuCash Transactions](https://www.gnucash.org/docs/v5/C/gnucash-guide/chapter_txns.html) | 区分真实流水、余额锚点与对账状态；running balance 留待有正式排序与锚点语义。拒绝伪造平衡流水 |
| [Monekin 账户讨论 #297](https://github.com/enrique-lozano/Monekin/discussions/297) | 多账户页面需要控制信息密度；总览限制预览、账户页分组/搜索，不再复制一份余额真相 |
| [iCost 官网](https://www.icostapp.com/)、[帮助入口](https://help.icostapp.com/) | 仅获得简洁快速的产品定位与有限页面信息；不足以验证内部账户或数据库策略，未声称已调查其实现 |
| [Compose issue 289824811](https://issuetracker.google.com/issues/289824811) | 页面内容不可充分访问，未把它当成本项目故障根因或已修复证明 |
| [微信官方应用条目](https://play.google.com/store/apps/details?id=com.tencent.mm) | 核对包名；详情字段结构依据 Owner 给出的 2026-10-14 微信已收款截图。截图不是无障碍节点树实测 |

字体来源与许可证见 `third_party/fonts/home-a/PROVENANCE.md`。Shalimar、Pacifico、Almendra 来自 Google Fonts；UKai 来自 Debian 上游分发；汇文正楷来自作者 `huozi.cool`；平方乔木、浅夏、悠然为 Owner 给定文件。保留 OFL、ARPHIC 和作者许可材料；仅整理部分文本行尾空白，未改许可内容。平方乔木与浅夏的文件本身不包含完整再分发授权，公开分发前应补齐其授权凭据。

## 4. 各页面实现与反馈对应

### 字体：全应用，而非只改首页

通过已安装的 Figwright 读取 Figma 文件 `57fBzB2YkI5bPZkGeNrMtR` 的首页前两张 Frame `3:6`、`3:37`。已有最小写入/读回/删除验证后停止插件测试，没有额外制作测试图。

`LifeFonts → LifeType → LifeText / 两套 Material Typography` 是唯一角色定义链。文本经 `BundledText` 按字体 cmap 选择整段回退，保留字号、行高与间距；输入框同样按内容回退。原生小票及快速采集菜单也接入角色。系统通知、系统权限页和键盘继续由 Android 自己绘制。

| 角色 | 字体与规格 |
| --- | --- |
| Home wordmark | Shalimar 32sp / 38sp |
| Home 日期、功能页大标题 | AR PL UKai TW 23sp / 30sp |
| Home memory、ahead | 平方乔木、汇文正楷，18sp / 28sp |
| Home 英文分段、历史小字 | Pacifico 13sp / 24sp；Almendra 13sp / 20sp |
| Home 年份/轻提示、品牌副标 | 点字浅夏 13sp / 19sp；副标 11sp / 16sp |
| Home 空态主句 | 演示悠然小楷 30sp / 40sp |
| 应用正文 / 次级正文 / 导航 | 平方乔木 16/25、14/22、13/19sp |
| 分组标题 / 模块标题 | 平方乔木 17/25、18/28sp |
| 元信息、按钮 | 浅夏 13/19sp；乔木 15/23sp |
| 金额 / 紧凑金额 / 日历金额 | Noto Sans SC Medium，23/31、17/25、10/14sp，等宽数字；数值编辑保持清晰 |

字距按 Home A 分角色定义，不把首页装饰字距机械套到密集表单。未限制系统字体缩放。字体缺字先回退完整 UKai，再回退现有 Noto Sans；极生僻字和 emoji 仍需设备验证，未承诺所有 Unicode 都由这些字体覆盖。

### Home、Finance、编辑器、外观、手势

| Owner 反馈 | 实现 |
| --- | --- |
| 首页以 Figma 前两张为准 | 日期/品牌/三个入口、today/memory/ahead 与末尾今日事实按安静手账层级排列；移除强横线/重标签感；复用原有照片栈，空态不填假记录 |
| 账目大卡过多、账户过散 | 缩小余额和事项卡密度，账户预览最多四个；完整账户按类型分组、折叠、搜索；流水时间放在左侧元信息，金额独立对齐 |
| 输入要连贯，不要 Sheet 套 Sheet | 分类搜索/层级/创建、账户与最近账户、时间、标签、业务关系在同一小票内展开；分配明细也改成内联。普通字段无嵌套 sheet；必要的作废确认仍保留 |
| 保存与返回不要丢输入 | 主草稿和关系使用 saveable state；模块保存不同记录的草稿；成功保存才清理对应草稿。分配列表、搜索输入可恢复；分配单独明确确认，针对已保存金额 |
| 主题操作难理解 | 分组折叠、四种样式两列、五个色块；HEX 仅在颜色弹窗中；照片取色位于颜色区之后、实时预览之前；滑轨变细并保持可操作控件 |
| 颜色与效果应整体保存 | 颜色、名称、删除、样式、首页/功能页各自的模糊和渐变全部先改草稿；顶部保存一次 DataStore 事务；返回/取消丢弃尚未保存的修改 |
| 模糊/透明预览不一致 | 实际页与预览调用同一背景函数；不透明度覆盖真实 0–100%，不淡化正文；blur 在背景层，外层裁切；低 RAM、旧系统、减少动画条件下不启用 RenderEffect |
| 启动闪屏、切换跳动 | 首帧等待主题完成合成；四种功能页样式共用已挂载内容树；保留样式差异；减少动画时停止相关运动。帧率及白闪改善仍需设备测量 |
| 周历滑动/收起不自然 | 折叠状态按周移动、展开按月移动；整周包含跨月日期；日历汇总共用搜索与维度筛选；折叠距离来自实际测量行高；重复点选当天可取消 |
| 系统返回与伙伴手势抢占 | 尊重系统左右 back insets，伙伴仅底部内容带起手；拖动显示反馈，取消也复位；临时编辑层沿用手势占用规则 |

**现有基线限制**：`DefaultHomeStateProvider` 仍然只返回空 `HomeUiState`。本轮完成真实 Home 组件排版与空态，未暗接 Closet、制造照片/记忆/未来假数据，也未扩展新的跨领域聚合架构。因此生产端“有内容首页”完整数据链不是本次已验证能力。

## 5. 架构边界与静态审查

- Finance UI 不新增 DAO 写入口；金额、账户、转账、关系校验仍由现有 Finance Repository 与事务处理。UI 查询复用投影；跨月 `calendarRows` 仅为内存派生列表。
- `LegacyBusinessSemantics` 只在导入边界解释 source payload。Canonical 查询不读取导入原文来猜业务状态。
- 采集分为薄 Service、有限节点读取、provider adapter、规范化 Evidence、共用 Proposal Repository、现有交付和收据审核。Service 不调用确认、不构造正式 Entry/Transfer。
- 默认两个详情开关均关闭。显式披露之后才开启；系统授权另走 Android 设置。通知开关与详情开关独立，共用已存在的投递偏好。
- 只读 active/focused application 的当前可见 root；最多 400 节点、深度 30；跳过不可见、密码、编辑子树。先匹配完整页面标签，再取局部 label/value。无点击、滑动、全局动作、历史遍历、聊天采集、截图或原始树落盘。
- 微信 adapter 仅输出金额、币种、方向、到账时间、观察时间、版本和交易编号 SHA-256。没有读取发送人姓名、订单商品或商户。缺少必要字段即跳过，不编造内容。
- 强编号重复只产生一个候选；编号相同但金额/时间冲突时拒绝覆盖。只有金额/时间接近的记录保持分离，必须在小票中核对；“另一笔真实交易”的确认包含候选 revision，Repository 在同一事务内再次校验。
- Room 保持 v4；实体、schema、备份格式、构建依赖、签名与应用身份未改。新增的是现有外观/设备偏好字段，不是第二套账本。
- 未扩展 SMS、银行通知、MediaProjection/OCR、任意应用自动化；既有快速采集功能仅调整文字渲染。
- 未运行或修改用户真实账本；私有 XLSX 只做本地只读汇总，未将其行内容、截图、原始详情或视频放进源码、测试或补丁。

## 6. 旧账：能正式迁移与只能识别的状态

以下表示代码允许的确认路径，**不是已经把 Owner 的 4548 行写入了正式账本**。真实工作簿未执行导入提交。

| 来源状态 | 本轮处理 | 正式语义 |
| --- | --- | --- |
| 普通收支、无歧义转账 | 继续 staging→映射→预览→显式导入 | 既有 Entry/Transfer 与事件关系，保留批次幂等及安全 undo |
| 待报销支出 | 单独分组，确认后导入 | REIMBURSABLE 事件与实际支出；不制造到账 |
| 已报销、到账账户未知 | 明确显示“旧账已报销／待迁移业务状态”，禁止组级及单行强行提交 | 保留 staging 证据；不降成待报销，不伪造收入，不用 cancelledAt 假结清 |
| 单一明确“代拍/代付” | 独立可选分组 | PROXY_PURCHASE；业务标签从消费分类剥离，剩余子类可提升为主类 |
| 单一明确“分账” | 独立可选分组 | SPLIT；个人分摊未明确时沿用现有待整理/保守统计语义 |
| 退款支出/退款到账来源行 | 保留真实来源方向与行，按现有审核政策处理 | 不凭退款金额列再造一笔现金流；未归属退款到账不计普通收入 |
| 已有部分报销金额/日期但状态不明、业务性质冲突、无效数据 | “需核对”，保留来源 | 不做自动正式迁移 |

原 22 列读取、账户锚点、手续费、行指纹、提交与撤销仍沿用现有链路。新增分组默认不批量勾选；前端与 Repository 都禁止不支持状态绕过。测试只含极小合成 fixture。

## 7. Finance v5 所需 schema / 语义决策

本轮没有执行以下迁移：

1. **iconKey**：在 Account/Category owner 上增加稳定的可空键，引用版本化内置图标目录；迁移已有记录为默认/空值，未知键回退通用图标；连同命令、导入、备份、恢复及 UI 选择器一起落地。当前没有无法持久保存的假图标按钮。
2. **历史结清 resolution**：新增有 provenance、revision、明确状态的历史结清事实，能表示“来源已报销、到账账户未知”。它不是真实现金流；将其与后续找回的到账关联时不能重复结清或重复计算个人成本。需 Owner 确认 source amount/time 与 unknown account 的表达及撤销规则。
3. **跨来源 Evidence 关联**：单独的 Evidence identity、provider/reference、extraction version、候选关联与冲突审查。当前 v4 只能保存规范化 Proposal 与 sourceKey，不能声称已经有可编辑的多证据图。未来换 adapter 版本必须迁移/规范化强身份，不能因版本变更复制同一交易。
4. **账户身份绑定**：provider wallet/card identity 与 Finance Account 显式绑定；不能仅按包名、银行名或末四位猜账户。影响备份、隐私、冲突及用户解绑。
5. **postedAt / running balance**：区分业务发生与账户记账时间；定义同刻稳定排序、历史补录、转账双腿、起始余额锚点和对账 revision 后，在 Finance Projection 计算逐笔余额。当前账户余额不能冒充历史余额。

这些决定需包含前向迁移、v4 导出/恢复兼容、失败回滚、历史来源与 UI 冲突处理，单独立项审查。

## 8. VERIFIED / UNVERIFIED / MANUAL-CI

**VERIFIED（静态或资源事实）**

- Git 起点、分支与职责提交已核对；没有改 schema/数据库 owner/构建配置。
- Figwright 读取首页前两张实际字体与节点，导出图已查看；插件最小写入验证已完成并清理。
- 字体内部 family、cmap、Owner 三份字体的 SHA-256 与打包文件核对；未按固定页面文字裁字体。
- Kotlin 词法括号结构扫描、统一字体角色引用扫描、XML 解析、完整基线 diff 空白检查。括号检查不是 Kotlin 编译器。
- 最终二进制补丁将逐封应用到临时 Git index，与最终 HEAD tree 比较；验证结果另附 `life-os-v15-ui-convergence-p1-verification.json`，不改用户 checkout。

**UNVERIFIED**

- 没有编译、单元测试或 Android UI 测试结果；新测试仅覆盖字体回退、导入状态和合成可见证据/弱重复规则，尚未运行。
- IME、字体 1.3/1.5 倍、长账户名、复杂分类、系统 Back、冷启动与低端设备模糊性能需真机确认。
- 用户视频未做逐帧性能分析；本环境未发现现成解码工具，未为此下载安装依赖。不得称为已经复现并消除了视频中的闪烁。
- 无障碍节点层级、OEM 后台存活、微信版本兼容与实际弹出/通知回退均未设备验证。
- 没有重新验证旧基线 CI Run；其 success 是任务提供的历史基线信息，不能当作本补丁结果。

| Provider | IMPLEMENTED ADAPTER | 页面证据 | DEVICE-VERIFIED BY OWNER |
| --- | --- | --- | --- |
| 微信“已存入零钱”转账收款 | 是 | Owner 当前截图字段已核对；没有实测节点树 | 否 |
| 微信支出、其他微信页面 | 否 | 不套用收款模板 | 否 |
| 支付宝、淘宝、京东、美团、银行等详情 | 否 | 当前材料不足，不猜 view ID/包结构 | 否 |

**MANUAL-CI（待独立 Review 之后由 Owner 决定执行）**

现有 `.github/workflows/android.yml` 包含 KSP、Room v4 schema 检查、`:app:testDebugUnitTest` 和 `:app:assembleDebug`；release 延续既有签名链。应检查新增纯逻辑测试及既有 Finance/Appearance/恢复/架构回归，随后只生成一份供下节验收的 APK。本轮没有触发这些命令，没有下载 JDK/SDK，没有提供未验证 APK。

## 9. 一次安装的最小真机验收

以下均是**待执行**，每项的预期不是通过声明。先独立 Review、再决定 CI；使用可恢复的测试账本完成写入场景，不向正式账本重复导入整份旧账。

| 场景 | 操作 | 预期 |
| --- | --- | --- |
| 启动与字体 | 已保存不同主题时冷/热启动；依次打开首页、Finance、衣橱、Capture、设置；系统字号 1.0/1.3/1.5 | 首帧不先闪默认主题；页面按同一字体层级；无裁字/金额混淆；系统键盘与通知不强改字体 |
| Home A | 空账本查看；如由正式 provider 提供照片/事实则再看内容态 | 空态安静，入口不重复；不出现种子照片/假记忆。当前默认 provider 为空须如实记录 |
| 支出/收入/转账 | 新建三类记录，转账选择不同账户；编辑已有金额与时间 | 仅保存后入账；转账不计个人收支；未修改时间保留原精度；错误不关闭草稿 |
| 连续编辑 | 输入金额→账户→子分类→新建子类→标签→性质→时间；开关 IME、返回收起后重新打开 | 常用选择内联；保存仍可触达；输入保留；没有叠加 Sheet 抢焦点 |
| 账户与分配 | 搜索长账户名，展开/折叠类型；对已保存流水分配到两个事件 | 无巨型空卡；币种清楚；分配总额受校验，不复制真实流水；主编辑草稿仍在 |
| 月/周历 | 选月末周并折叠，左右滑两次，点邻月日期，再重复点同日、展开整月 | 周模式每次七天、月模式每次一月；跨月七格完整；日期/筛选/流水一致；重复点取消选日 |
| 手势 | Finance 四页内容左右滑；日历内滑；系统左右边缘返回；底部上滑并中途取消/完成；打开编辑层再试 | Calendar/Pager/Shell 分工；系统返回可用；伙伴反馈随手指且取消复位；编辑层不被抢走 |
| 外观草稿 | 改五色、名称、样式、首页/功能页 blur/gradient/opacity；删自定义主题后先取消，再重做并保存 | 取消全部回退；保存一次生效且重启保留；HEX 仅颜色弹窗；图片取色不保存原图 |
| 效果边界 | opacity 0/100、blur 0/100，切换四种样式、减少动画；快速滚动 | 正文不透明、无矩形 blur 溢出；预览与真实页一致；记录设备掉帧，不能仅凭目测定量 |
| 通知回归 | 微信/支付宝各产生明确支付通知；普通聊天通知；完整与缺金额通知 | 合法证据进入原有小票/通知回退；聊天不入账；缺金额要求完善；不保存通知正文 |
| 详情默认关闭 | 不开新开关，手动打开微信已收款详情 | 不产生详情候选 |
| 详情开启 | 同意披露、打开微信 provider、系统授予权限，再手动打开支持的已收款页 | 只读当前页；金额/方向/到账时间正确，来源明确；事由/分类不足时要求补齐；不猜商户/商品 |
| 详情重复 | 多次进入同一转账详情 | 稳定编号只产生一个候选，已确认后不再制造另一笔 |
| 跨源弱重复 | 同笔通知已出现后再打开详情；再模拟相同金额附近时间的另一笔 | 显示相近记录；不能直接确认；同笔忽略当前候选；只有人工核对另一笔才能继续，确认前重新校验 |
| 详情隐私 | 聊天、输入框、密码、非支持页面；取消/稍后审核；关闭总开关或 provider | 不触发通用抓取，不写正式流水；关闭立即停止新识别；日志、候选、备份中无原始节点树或私人页面文本 |
| 旧账预览 | 只读选择真实 XLSX，映射到预览；另用小型合成样本确认/撤销 | 普通/待报销/已报销/代拍/分账/退款/需核对分别可辨；已报销未知到账禁止写入；幂等与撤销保护后续编辑 |

若微信当前节点结构不满足五个字段的局部 label/value 关系，预期安全结果是跳过；此时标为适配未通过，收集去标识的字段结构后修 adapter，而不是放宽为任意页面金额正则。

## 10. 建议补充到 life-os-docs 的差异

由 Owner 独立维护文档分支，本轮仅提出差异：

1. UI 字体规范改为 Home A 全局角色表，记录 Owner 后续覆盖旧 Noto 主体方案的决定；区分装饰文字、功能正文、金额及缺字回退。
2. 首页明确当前 production provider 为空，列出未来照片/事实/计划聚合 owner 与隐私筛选，避免把排版完成误写成数据链完成。
3. Finance 编辑 PRD 增加单一连续小票、内联选择、草稿保留、成功保存清理；补充分配独立确认与已保存金额边界。
4. Appearance 明确所有编辑事务统一保存/取消、opacity 是背景不透明度、Home/function 独立 blur/gradient、低端回退和同源预览。
5. Gesture 规范写明系统边缘、功能页、Finance Pager、Calendar、Companion、临时编辑层的优先级与减少动画行为。
6. 导入字段说明加入历史已报销阻断表与 v5 resolution 待定，不写成“已全部迁移”。
7. Privacy / Capture 增加独立可见详情开关、最小字段、provider 状态矩阵、不得自动操作第三方应用、弱匹配只能人工复核。
8. 路线图列出 iconKey、账户绑定、多证据 identity、历史结清、postedAt/排序/锚点/对账；每项注明迁移与备份影响。
