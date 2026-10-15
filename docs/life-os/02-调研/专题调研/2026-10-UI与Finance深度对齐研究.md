# Life OS 当前 UI 与 Finance 深度对齐研究

## 新增
Finance 自动发现的目标不限定微信/支付宝通知。Life OS 的长期采集层支持多个 Evidence Source，包括 Notification、用户主动打开的 Accessibility 可见交易详情页、Legacy Import，以及未来明确授权的官方 API。各来源只产生 Evidence/Proposal，不直接拥有 Finance Truth。Accessibility 采用 provider adapter 架构，覆盖常用支付、电商、本地生活与出行 App；只解析用户当前主动打开的已识别详情页，不后台遍历、不自动操作第三方 App。

## 研究范围与核心判断

我这次不是只看你最后发的几张图，而是把几条线重新叠在一起看：**当前真实 GitHub 代码、Finance v4 数据模型、旧账 XLSX、你之前的 iCost 参考、这次的 Figma 截图、你给的 Soft Spatial Journal HTML prototype，以及 Actual Budget、GnuCash、Beancount、Plaid、Monekin、Finapp、iCost 官方资料。**

先说明一个限制：我尝试直接通过 Figma connector 读取你给的设计文件结构，但本轮连接器碰到了 Starter plan 的调用额度，因此我**没有假装已经拿到了 Figma 的精确节点、字号和间距参数**。这次视觉判断基于你上传的 Figma 截图和 HTML prototype；真正落实时，Codex 应再次尝试读取 Figma，并用你本地的字体文件对齐精确 typography。

另外，你说“我们已经做完 V11，现在是在 V11 上继续改”这个上下文我没有丢。不过**工程上不能 checkout 回 V11 重做**：GitHub 当前独立确认的真实绿色基线仍是 `feature/life-os-v14-device-refinement @ 997e17639b3b82b6c4431ca5a25455441f1a9446`；V11 应继续作为视觉/交互历史基底，而当前代码与数据架构从 V14 继续演进。fileciteturn3file0L2-L2

我的总判断是：

> **你现在感觉“不对劲”的根源，不是再调几个圆角、字号、padding 就能解决，而是目前很多页面仍然沿用“每个功能都是一个模块/一个框/一个选择器”的 UI 组织方式。**
>
> Life OS 真正应该走的是：  
> **Home 是生活手账；Finance 是高密度轻账簿；Appearance 是编辑器；Closet/Items 是收藏册；Plans 是工作台。**
>
> 它们共享字体、纸张、颜色、留白、图标和动作语言，但**绝不能所有模块都套成同一种“大圆角卡片 App”。**

你在 Figma 的 **Home A** 已经把这个方向说得很清楚了。它应该成为当前 Life OS 的主要视觉 North Star；Finance 和 Appearance 可以比 Home 更密、更工具化，但依旧属于同一套纸张/编辑式语言。

研究成熟财务产品以后，你最后提的 Finance 七个问题也基本都成立，而且有些不只是审美问题：

| 你的反馈 | 研究后的判断 |
|---|---|
| 页面到处都是“模块” | **成立，是当前视觉组织模型的问题** |
| 记一笔应该一个页面完成 | **成立，当前交互跳转成本过高** |
| 分类很丑且难找 | **成立，需要层级 + 图标 + 最近使用 + 原地展开** |
| 账户不能一直往下滚 | **成立，需要分组、折叠和紧凑索引** |
| 旧账里的代拍/报销应该自动识别 | **成立，而且现有 parser 实际已经读到了，只是语义提升只做了一半** |
| 分类应自选图标 | **成立，但应该进入 Category canonical metadata，不应另建 UI 偏好表** |
| 流水金额下应显示该笔后的余额 | **成立，而且是成熟账本很标准的对账能力；但不能用当前 `recordedAt` 排序硬算** |

Actual 的账户 register 可以显示每笔交易的 running balance，并明确把它定位为和银行余额核对的工具；GnuCash 的 register 同样直接展示交易后的更新余额；iCost 官方功能说明也明确写了资产详情中的账单可显示对应账户余额用于对账。你的这个要求不是“额外装饰”，而是**真正的账本功能**。citeturn3search3turn3search4turn2search29turn4search3

## 视觉语言与 Figma 对齐

### Home A 应该锁定的不是“某一张图”，而是一套语法

我认为你现在 Figma Home A 最值得锁的是这几个原则：

**页面首先是一张纸，而不是 Card 的容器。**

Today、Memory、Ahead 不应该分别被三个大圆角框圈起来；它们应该靠字号、空间、位置和内容节奏形成层级。照片堆可以成为一个真正的“对象”，所以它可以有边界；普通文字区没必要为了证明“我是一个模块”再包一层卡片。

**一屏只允许极少数强 Surface。**

比如 Home 首屏可以有照片堆，但 Memory 不需要一张卡、Ahead 不需要一张卡、“今天留下的事”也不需要再来一张卡。Finance 总览可以有一个本月摘要 Surface，但“资金事项”“钱的位置”不应该全部再变成等权大卡。

你的 HTML prototype 本身已经写得很清楚：Home “不是 Dashboard”，要通过留白而不是横线分区；Finance 更像轻账簿；Appearance 更像编辑器。这一组原则我认为比某张静态稿更重要。

### 你之前说“Life OS 和日期一样大，看着很怪”，我找到代码根因了

当前 typography 本身设计得其实不错，字体都通过 `LifeFonts` 集中管理，而不是每个页面乱指定；Noto Serif SC、Noto Sans SC、Caveat、Special Elite 都是 bundled font，说明我们已经有一个可以继续演进的统一字体基础。fileciteturn13file0L2-L2

但当前 `LifeType.Brand` 的注释和实现**明确把两个不同的角色绑在了一起**：

> “The app wordmark and the home date hero.”

也就是说，现在代码从设计层就认为：

```text
Life OS 品牌字
=
首页日期 Hero
```

都使用同一个 28sp Serif role。fileciteturn14file0L2-L2

这正是你觉得怪的根源，不应该再在 Home 里局部写 `fontSize = xx.sp` 补丁。

应该把 typography role 正式拆开：

```text
Wordmark
    Life OS
    使用你 Figma 里的品牌/手写字体

DateHero
    10月12日
    独立 Serif hero

PageTitle
    账目 / 设置 / 外观

SectionTitle
    TODAY / MEMORY / AHEAD
    或各模块 section

Body
    正文

Meta / Caption
    年份、星期、账户说明

Money
    对齐金额 / running balance
```

你说本地有对应字体，非常好。后面字体文件应该直接作为 APK bundled resource 加到现有 `LifeFonts` 体系中，由 `LifeType` 统一映射；**不能让 Codex 在 Home、Finance、Appearance 各自写一套 fontFamily/fontSize**。当前字体基础已经足够干净，应该演进它而不是绕过它。fileciteturn13file0L2-L2turn14file0L2-L2

如果你 Figma 里的中文手写字也要正式进入产品，而本地字体确实有 CJK 字形，那么就单独形成一个例如 `JournalHand` 的角色。当前 Caveat 是英文装饰字，现有代码刻意禁止它承担中文内容，所以不能为了模仿 Figma 又让系统字体 fallback 回来。fileciteturn13file0L2-L2

### 其他模块不应该复制 Home，但要继承 Home 的“气质”

我建议以后 Life OS 不按“所有页面一个模板”做，而按几种页面原型发展：

| 页面原型 | 适合模块 | 气质 |
|---|---|---|
| **Journal / 手账** | Home、Plog、回忆 | 大留白、照片、叙事文字 |
| **Ledger / 账簿** | Finance、Membership | 高信息密度、行式信息、少 Card |
| **Collection / 收藏册** | Closet、Items、Reading、兴趣 | 图片/对象成为主角 |
| **Editor / 编辑器** | Appearance、Settings、资料库编辑 | 分组折叠、即时预览、明确 Save |
| **Workspace / 工作台** | Plans、Shopping、Travel | 层级、状态、操作，但不 Dashboard 化 |
| **Projection / 投影面** | Calendar、Map、Search | 只组织事实，不拥有第二份 Truth |

这样既能“按你这个感觉来”，又不会让未来十几个模块都变成 Home 的复制品。

这是我认为最能防止以后 UI 变屎山的一点：**共享 Design Tokens 和视觉语法，不共享一个万能 `LifeModuleCard`。**

## 记一笔、分类与账户的重构方向

### 当前“记一笔”确实不是你想要的记账方式

现在真实 V14 `FinanceEntrySheet` 是这样的：

金额一个 `OutlinedTextField`，账户走 `AccountChoice`，日期文本框再加“从日历选择日期”，分类和标签分别再打开 `FinanceChoiceSheet`，同时还有分类、子分类两个文字输入框，关系字段再单独展开。fileciteturn11file0L2-L2

`FinanceChoiceSheet` 自己又是一个最高占屏幕 85% 的 ModalBottomSheet：搜索 + 最近 + 常用 + 所有条目的纵向 `LazyColumn`。fileciteturn12file0L2-L2

所以你感受到的是：

```text
我要记一笔账

→ 进入一个表单
→ 点账户
→ 又一层 sheet
→ 返回
→ 点分类
→ 又一层 sheet
→ 返回
→ 点标签
→ 又一层 sheet
→ 返回
→ 点日期
→ 又一层
```

它在数据逻辑上没错，但是**记账路径的空间连续性被破坏了**。

成熟产品也把“快速记录”看成核心能力。iCost 官方列出的快速记账同时支持账户、分类、报销、标签、模板等字段，并强调极简快速流程，而不是把每个字段都变成独立导航。citeturn4search0turn4search3

我建议把 Life OS 的“记一笔”正式改成**一个连续编辑上下文**：

```text
记一笔                                      保存

支出       收入       转账

                    ¥ 27.36


最近
[早餐] [咖啡] [地铁] [日用品] [打车]

分类
[三餐] [零食] [交通住宿] [日用品] [医疗]
               ↓ 点“三餐”

[早餐] [午餐] [晚餐] [夜宵] [下午茶]


备注
瑞幸咖啡


[支付宝主号]  [今天 12:05]  [标签]  [个人 ▾]

┌─────────────────────────────┐
│        数 字 / 计算器       │
└─────────────────────────────┘
```

关键不是一定长这样，而是：

> **选择分类、账户、日期、业务性质时仍然处在“这一笔账”这个页面里。**

较少使用的内容可以原地展开；不能一层层把人传送到不同 sheet。

### 视觉简洁和信息密度不是一回事

你说“熵值太低”其实很准确。

现在的问题不是“信息太多”，而是：

**一个字段占一整行、一大框、一大段空间，导致屏幕能同时看到的信息反而很少。**

Finance 本身应该是 Life OS 中**信息密度较高的模块**。

Home 可以呼吸。

账簿不能为了“极简”变成：

```text
一个账户
占 80dp

一个分类
占 80dp

一个日期
占 80dp
```

Life OS Finance 应该做到：

> **视觉噪声低，但操作信息密度高。**

### 分类要真正变成“看得见的树”

当前数据库其实已经有：

```kotlin
FinanceCategoryEntity(
    id,
    name,
    parentId
)
```

所以从数据模型角度，分类从一开始就不是纯平面。fileciteturn6file0L2-L2

问题在 Projection 和 UI。

当前 `categoryPath()` 只找一层 parent：

```text
parent / leaf
```

并没有真正递归，所以实际上只支持“看起来是二级分类”。fileciteturn10file0L2-L2

这应该改成真正树：

```text
餐饮
  ├─ 三餐
  │   ├─ 早餐
  │   ├─ 午餐
  │   └─ 晚餐
  └─ 零食饮料
      ├─ 咖啡
      ├─ 奶茶
      └─ 水果
```

但 UI 不需要每天把三级树完全展开。

日常操作应该：

```text
最近使用
↓
一级分类图标
↓ 点击
当前一级原地展开二级
↓ 必要时
三级
```

Monekin 当前明确支持 unlimited categories/subcategories 和自定义图标；Finapp 也采用 parent-child hierarchical categories 加 custom icons/colors。说明“层级分类 + 自定义 icon”是成熟且自然的账本模型，不需要我们发明一个特殊 hack。citeturn3search7turn3search6

而 iCost 本身官方就支持二级分类及自定义分类，和你给的截图一致。citeturn4search3

### 自定义图标应该放在哪里

这个不能用：

```text
DataStore:
category_餐饮_icon = fork
```

这种旁路做。

当前 `FinanceCategoryEntity` 只有：

```text
id
name
parentId
```

并没有图标元数据。fileciteturn6file0L2-L2

所以如果你确认分类图标是正式 Finance 功能，我建议 Room v5 正式加入：

```text
iconKey: String?
```

这里应该保存：

```text
food.coffee
transport.metro
shopping.cosmetics
```

这种**稳定的语义 key**，而不是 Android `R.drawable.xxx` 的整数 resource id。

图标 Registry 属于 Presentation：

```text
food.coffee
    → Coffee icon
```

以后 APK 资源编号变了，数据库不会坏。

你的旧 XLSX 本身没有 icon 列，因此导入时可以按分类名称赋一个默认 icon，但最终用户可以自行换；这与 Monekin 等项目允许 category 自定义 icon 的做法一致。citeturn3search7

### 账户页也不该是二十行长名单

我直接看了你上传的真实旧账文件：

**4548 笔记录里有 18 个不同的来源账户名。**

而你现在真机截图已经显示“20 个在用账户”。所以扁平纵向列表显然会越来越糟。

Actual 最近也开始强化 account grouping：可以把账户放进命名分组、折叠，并支持账户/分组重新排序；这就是账户数量增加以后从 flat list 向 group index 演进的典型方向。citeturn2search10turn2search19

Life OS 不需要照搬它的 sidebar。我更建议移动端变成：

```text
账户

总余额
CNY  -65,292.13
USD  ...

日常资金
农业银行      ¥...
建设银行      ¥...
微信          ¥...
支付宝        ¥...

信用支付
花呗          -¥...
京东白条      -¥...
美团月付      -¥...

投资与储蓄
支付宝基金     ¥...
农行时时付     ¥...

其他
...
```

每个组可以收起。

组内用**紧凑的两列/短行索引**，而不是一个账户占一个巨大卡片。

进入“记一笔”选择账户时则：

```text
最近
[支付宝] [微信] [农业银行] [花呗]

日常资金
[...]

信用
[...]

全部
```

仍然在当前编辑器内完成。

## 旧账导入与报销代拍语义

这里我发现了一个非常关键的事实：

> **你说“我的表格明明有报销、代拍，为什么不能直接识别”是完全正确的。**
>
> 这不是 XLSX parser 读不到。  
> **它已经读到了，但当前代码故意只处理了一半。**

我直接解析了你上传的真实 XLSX。

它不是一个模糊的第三方文件，而是非常结构化的 **22 列、4548 笔记录**：

```text
日期
金额
账本
类型
分类
子分类
备注
标签
账户
转入账户
债务人
转账手续费
是否为报销
账单状态
报销金额
报销日期
退款金额
退款日期
原价
不计入预算
不计入收支
账单图片
```

而当前 `LegacyLedgerReader` 的 `headers` **恰好就是这 22 列**。fileciteturn7file0L2-L2

我统计了你的文件：

| 旧账语义 | 实际数据 |
|---|---:|
| 总记录 | 4548 |
| 支出 | 3775 |
| 收入 | 565 |
| 转账 | 207 |
| 债务 | 1 |
| 标记“是否为报销=是” | 278 |
| 已报销 | **195** |
| 未报销 | **83** |
| 已退款 | 32 |
| 退款账单 | 7 |
| 分类直接叫“代拍” | 4 |
| 分类直接叫“分账” | 23 |
| 一级分类 | 35 |
| 子分类 | 95 |
| 来源账户名 | 18 |

更关键的是：195 条“已报销”里，**全部都有报销日期**；其中 140 条还有非零的报销金额。

所以绝对不应该让你：

> “4548 笔账一条条进去重新标报销。”

### 当前代码到底干了什么

Parser 已经判断：

```kotlin
reimbursement =
    是否为报销 == 是
    || 账单状态 contains 报销
    || 报销金额 != 0
```

同时也识别退款。fileciteturn7file0L2-L2

但随即给它们加了提示：

```text
报销字段仅保留证据，不生成到账
退款字段仅保留证据，不生成到账
```

fileciteturn7file0L2-L2

Commit 时，报销支出只会被提升为：

```text
FinanceNature.REIMBURSABLE
```

却没有把“未报销 / 已报销 / 报销日期 / 报销金额”的完整关系恢复出来。fileciteturn9file0L2-L2

而“代拍”“分账”目前只是普通 category text；当前 import commit 并不会把：

```text
分类 = 代拍
```

提升成：

```text
FinanceNature.PROXY_PURCHASE
```

同样也不会把“分账”提升为 `SPLIT`。fileciteturn9file0L2-L2

可与此同时，Finance v4 **明明已经正式有这些业务语义**：

```text
PERSONAL
SPLIT
PROXY_PURCHASE
REIMBURSABLE
STORED_VALUE

PAYMENT
REFUND
REIMBURSEMENT
SETTLEMENT
REBATE
CASHBACK
FEE
```

fileciteturn8file0L2-L2

所以这里不是“Finance 架构做不到”。

而是：

> **Import Adapter 没有完成 Legacy → Current Domain 的语义迁移。**

### 正确的旧账迁移方式

不应该逐行 review。

应该按规则批量预解释：

```text
4548 条

✓ 普通收入/支出/转账
✓ 代拍
✓ 分账
✓ 待报销
✓ 已报销
✓ 已退款
✓ 退款账单
? 债务 / 少量真正无法映射的特殊情况
```

然后导入页显示：

```text
识别结果

普通流水       4xxx
待报销            83
已报销           195
代拍               4
分账              23
退款相关           39
需人工确认          N

[查看规则]       [确认导入]
```

**只让你处理真正不确定的那几类，不是 4548 条。**

Firefly III 的 importer 也是“导入 → mapping/rules → duplicate handling”这种批量规则处理，而不是让用户逐行重新造账；Actual 的 importer 同样先映射字段、跑规则并做 duplicate reconciliation。citeturn1search9turn0search0turn0search3

### “已报销”有一个必须保持诚实的边界

这里不能为了让 UI 好看就造假账。

旧 XLSX 能告诉我们：

```text
这笔支出是报销
状态 = 已报销
报销金额
报销日期
```

但未必告诉我们：

```text
这笔报销的钱
具体打进了 Life OS 哪个真实账户
```

所以不能凭空生成：

```text
建设银行 +¥100
```

否则当前账户余额会被污染。

正确语义应该是：

```text
原始付款 -100
        │
        └── Reimbursement Event

Legacy evidence:
已报销
金额 100
日期 2024-xx-xx
但到账账户未知
```

UI 可以正式把它显示在：

```text
已报销
```

并标：

```text
旧账已确认报销 · 到账账户未绑定
```

但它不是一笔伪造的 canonical cash inflow。

如果以后发现一条明确匹配的真实收入，再把真实 `FinanceEntry` 用 `REIMBURSEMENT` role 关联进去。

这个边界尤其重要：**业务关系可以确定，而真实资金流仍然可以未知。**

当前 `FinanceExpectedFlowEntity` 只有 expected amount、note、cancelledAt，没有“已完成但未绑定真实 Entry”的干净表达能力。fileciteturn8file0L2-L2

因此我认为 Finance v5 应该给 Expected Flow / relation 增加一种**resolution/provenance 语义**，而不是滥用 `cancelledAt`：

```text
expected reimbursement
↓
resolved by canonical entry

或

resolved by legacy evidence
but cash entry unknown
```

这以后对“代拍已经结算，但历史到账账户缺失”“分账已结清”等也有用。

## 每笔余额与乱序补账

你最后一句：

> “金额下面为什么是时间？不应该是那个账户的余额吗？”

我现在可以非常明确地说：

**是。**

而且这不是单纯 UI 改字。

Actual 把 running balance 定义成“每一笔交易发生后账户剩多少钱”，并说明它尤其适合在某日期与银行余额对账；GnuCash 的 account register 也把 updated balance after current transaction 作为基本字段。citeturn3search4turn2search29turn2search33

iCost 官方甚至直接把：

> 资产详情中的账单显示对应账户余额，方便对账

列成产品功能。citeturn4search3

所以 Life OS 流水最终应该类似：

```text
☕ 瑞幸咖啡
   餐饮 › 咖啡 · 支付宝

                       -¥27.36
                 余额 ¥429.60
```

时间不应该消失，而是移到左侧 metadata 或日期组：

```text
10:36 · 支付宝
```

右侧金额区域则承担：

```text
这一笔多少钱
↓
这一笔之后账户还剩多少
```

这就是银行 register 的阅读习惯。

### 但现在绝不能直接算

当前 Finance v4 `ledger()` 的排序实际是：

```kotlin
occurredAt DESC
→ recordedAt DESC
→ id
```

fileciteturn10file0L2-L2

而你已经明确会：

```text
今天补：

9/20
9/2
9/17
9/1
```

所以：

```text
recordedAt
=
你今天录入的先后顺序
```

绝不能决定：

```text
9 月账户余额历史
```

因此我反对下一轮为了满足 UI，简单写：

```kotlin
var balance = currentBalance
rows.forEach { ... }
```

然后把一个可能错误的数字显示出来。

### 正确结构仍然是 Derived Projection

当前代码已经有一个很正确的原则：

> `No independently persisted balances.`

账户余额是：

```text
openingBalanceMinor
+
balanceAnchorAt 之后的真实 Entry
```

实时投影。fileciteturn10file0L2-L2

这个绝对保留。

未来：

```text
FinanceEntry
    不能增加 balanceAfterMinor
```

而是：

```text
FinanceRunningBalanceProjection

entryId
→ balanceAfter
```

每次历史交易新增、修改、void 后，Projection 自动重新计算后续流水。

### 时间也需要正式拆清

Plaid 真实银行数据就明确区分了用户实际授权/消费时间和银行 posted 时间：对于 UI，authorized time 更接近用户真正消费发生时刻；而 posted transaction 的 `date/datetime` 表示金融机构真正记入账户的时间。citeturn3search0turn3search1

所以 Life OS 后续建议继续沿用我们之前定下的语义：

```text
occurredAt
用户实际支付 / 事情发生时间

postedAt?
机构入账时间

recordedAt
Life OS 什么时候记录

updatedAt
Life OS 什么时候最后修改
```

其中：

**UI 日常时间**优先展示 `occurredAt`。

**账户 running balance 的账务次序**在拿到银行 posted 信息时应以账务 posting order 为依据；手工账只有 occurredAt 时则用 occurredAt。citeturn3search0

同一时间多笔还需要一个稳定 tie-break：

```text
ledgerEffectiveAt
+
institution sequence（真的存在时）
+
persistent local tie order
+
stable id fallback
```

不能再拿 `recordedAt` 顶上。

Beancount 对 balance assertion 的讨论也专门指出了同日交易的 ordering ambiguity：只按日期并不能表达日内每两笔之间的精确余额，这正说明稳定账务顺序是实际 ledger 问题，不是我们在过度设计。citeturn0search5

### anchor 前的旧账也可以算，但要诚实

当前账户已有：

```text
openingBalanceMinor
balanceAnchorAt
```

fileciteturn6file0L2-L2

如果：

```text
10/1 已知银行余额 = 10,000
```

之后你补：

```text
9/1  -100
9/10 -200
```

当前余额仍应是 10,000，因为 9 月历史已经包含在 10/1 的 anchor 余额里。

但历史 running balance 可以从 anchor **倒推**：

```text
10/1               10,000

9/10 -200
该笔后余额          10,000
该笔前余额          10,200

9/1 -100
该笔后余额          10,200
该笔前余额          10,300
```

如果中间旧账漏了一笔，则倒推余额会与银行 statement 不一致。

这恰恰是 reconciliation 的作用。GnuCash 的 reconciliation 明确以 statement date、starting balance、ending balance 对账户交易进行核验，并警告修改已对账日期之前的交易会使后续 reconciliation 不再一致。citeturn1search7turn1search8

所以之前讨论的 `BalanceObservation / Reconciliation` 不是多余功能，而会成为你大量补历史账时非常有价值的“校验锚点”。

## Room v5 与代码边界

这次重新看完后，我对 Room v5 的边界比之前更明确了。

### 不用等 v5 就能改的

这些完全可以在现有数据结构上先做：

| 能力 | 原因 |
|---|---|
| Home 按 Figma A 重做视觉 | Presentation |
| 全局 typography role 拆分 | Presentation |
| Finance“记一笔”做成单页连续编辑器 | UI |
| 分类按 `parentId` 原地展开 | parent 已有 |
| 分类路径改递归 | Projection |
| 最近使用分类 | 从 Entry 派生 |
| Account 按 kind 分组显示 | kind 已有 |
| 账户列表折叠/紧凑排列 | Projection |
| `代拍` → `PROXY_PURCHASE` 导入映射 | Nature 已有 |
| `分账` → `SPLIT` 导入映射 | Nature 已有 |
| 报销批量识别和 import preview 汇总 | parser 已有字段 |
| 不让 4548 条逐行确认 | Import UX |

当前 Event Core 已经拥有 `SPLIT / PROXY_PURCHASE / REIMBURSABLE` 以及 settlement/refund/reimbursement 等 role，所以这些不需要再造一套业务模型。fileciteturn8file0L2-L2

### 真正应该进入 v5 的

下面这些都是**需要持久化新语义**的，应该一起做对：

| v5 语义 | 为什么必须持久化 |
|---|---|
| `FinanceCategory.iconKey` | 用户自选图标是分类本身 metadata |
| `postedAt?` | 账务入账时间 |
| stable ledger ordering metadata | 同时刻多笔确定顺序 |
| External Payment Identity | 多微信 / 多支付宝 / 多卡 |
| Account Binding | 外部身份 → Life OS Account |
| Finance Evidence | 多来源 evidence，不保存完整通知 |
| Proposal ↔ Evidence | 同一交易多来源聚合 |
| Reconciliation observation | statement balance 校验 |
| relation resolution/provenance | 旧账“已报销但到账账户未知” |
| 可选的 category/order metadata | 用户排序若最终确认需要 |

而：

```text
balanceAfter
currentBalance
category full path
最近使用分类
统计结果
待报销金额
```

仍然是 **Derived**，不应该永久塞进表。

### 这也决定了下一步不能做一个超级 Patch

如果现在让 Codex 一口气：

```text
重做 Home
重做 Finance UI
重做 Entry
改分类
改账户
Room v5
running balance
外部身份
旧账迁移
报销
Evidence
```

这是非常典型的“功能都能写完，但半年后没人敢动”的 mega patch。

我不建议。

我们之前已经明确不要屎山，所以应该按**架构边界**切，而不是按“我今天想到几个 feature”切。

## 推荐落地顺序与验收标准

我建议接下来不要再一轮轮“改这个 padding、再补那个卡片”。

先正式进入一次 **UI Convergence**，把视觉体系收拢。

### 视觉与交互收口

这一刀不动 Room schema。

目标就是：

```text
Figma Home A
= Home 的主视觉标准

Finance
= 同一套字体/纸张/色彩
  但明显更高的信息密度

Appearance
= 编辑器

记一笔
= 单连续页面

分类
= icon tree

账户
= compact grouped index
```

同时正式修 Typography：

```text
Wordmark != DateHero
```

用你提供的本地字体文件，而不是继续用系统字体或各页面自己调字号。

Home 不再“模块堆模块”。

Finance 不再“大卡片堆大卡片”。

Settings / Appearance 用折叠与编辑器结构。

这一步应该**尽量没有业务数据变化**，所以 Review 可以集中盯 UI、状态 ownership、Composable 职责和手势。

### Finance v5 核心

接着做一次真正的 Finance Core migration：

```text
Category icon metadata
External Identity
Evidence
Account Binding
postedAt
stable ledger ordering
relation resolution
Balance Observation / Reconciliation
```

然后 Running Balance Projection 正式建立。

不是先把 UI 上“余额”画出来，再补底层。

### 旧账语义迁移

v5 数据能力到位后，把你这份 **4548 条真实 XLSX** 直接作为迁移设计的真实验收样本：

```text
278 报销
├─ 195 已报销
└─ 83 未报销

4 代拍
23 分账
39 退款相关
18 个历史账户
35 个一级分类
95 个子分类
```

导入应该最终做到：

> **绝大多数行自动正确迁移，用户确认“规则”，不是确认“每一行”。**

真正不确定的才进 Review。

这才符合 Life OS 一开始的产品哲学：

> **不是让你把以前的复杂表格重新在手机上填一遍，而是 Life OS 替你理解这张表，你只确认它不确定的地方。**

### 最终流水 UI

等账务顺序成立以后，流水才正式切到：

```text
10月14日

瑞幸咖啡
餐饮 › 咖啡 · 支付宝
                         -¥27.36
                      余额 ¥429.60

地铁
交通 › 公共交通 · 农业银行
                          -¥3.00
                    余额 ¥3,280.45
```

这时“每笔余额”不是装饰，而真正可以拿来审查：

> “我补这一整个月的旧账补对了吗？”

Actual、GnuCash 和 iCost 都已经证明 running balance / transaction balance 是成熟账本中非常实用的核对工具。citeturn3search4turn2search29turn4search3

**所以这轮研究最后最重要的决定不是“分类要换个漂亮图标”。**

而是我们现在终于可以把两条线分清：

> **Figma Home A 决定 Life OS 应该长什么样；  
> Finance Core 决定这些漂亮页面下面的数据能不能永远算对。**

前者要大胆一点、有个性一点；后者继续保持现在这种保守、可验证、不能造假账的标准。
