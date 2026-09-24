# Life OS 设计系统（v0.1）

Life OS 与 Closie 共用同一套 MaterialTheme 与色板。Life OS 的 `LifeColors` 是**指向
`ClosieColor` 的语义别名**，不是第二套色值——两个半边共用一个 Shell，两套十六进制色值
必然漂移。

## 色板语义

| 语义名 | 角色 | 映射到 |
|---|---|---|
| `Paper` | 页面底色（暖白，不用纯白） | `Porcelain` |
| `ColdWhite` | 卡片 / 弹层 | `Paper` |
| `Mist` | 分组内嵌面（也用于禁用态卡片） | `Mist` |
| `Line` | 1px 分隔线，刻意极淡 | `Hairline` |
| `Ink` / `InkSoft` / `InkFaint` | 主 / 次 / 三级文字 | `Ink` / `Graphite` / `Stone` |
| `Accent` / `AccentSoft` | 唯一强调色 | `Fig` / `FigSoft` |

`Accent` 只允许出现在三处：选中的 Tab、主 CTA、采集进行中的状态。多一处就失去强调作用。

## 留白节奏

- 页面左右 20dp，顶部 28dp；
- 区块之间 **32dp**（比 Closie 的 20–24dp 更松）——首页内容极少，
  节奏不够大就会显得"没做完"而不是"刻意留白"；
- 列表项之间 12dp，卡片内边距 16dp，卡片圆角 18dp，弹层圆角 24dp。

## 首页：故意空

首页只有三样东西：日期、一行 Today、最多 5 条最近记录。
**没有**工具栏、搜索框、统计网格。首页唯一的任务是让"开始采集"成为最明显的下一步，
每多一个控件都在和它抢注意力。空状态卡片本身可点，点击即打开采集弹窗。

## 禁用态：必须看得出是禁用

Coming soon 的模块（饮食 / 健康 / 阅读 / 出行）、媒体库、同步，一律：

- `Modifier.clickable(enabled = false)` —— **不是**传一个空 `{}` lambda。
  空 lambda 仍然会有水波纹和按压动画，用户点了没反应只会觉得"这按钮坏了"；
- 文字降为 `InkFaint`，底色降为 `Mist`；
- 副标题写明"Coming soon"或"尚未实现"。

同步那条尤其重要：项目**没有**任何 web/server 同步能力，一个看起来能点的"同步"
是这个 App 里最容易误导人的东西。

## 点击区域

行的写法是 `.clickable(enabled) { }` **放在 `.padding()` 之前**，
这样整行含内边距都响应点击（行高 ≥ 48dp）。顺序反过来会把热区缩到只有文字，
在小屏机上表现为"点了没反应"。

## 按钮/Sheet

`＋` 是底部导航的第 3 项，**永远不进入选中态**，它唯一的职责是打开 `CaptureBottomSheet`。
Sheet 用 `ModalBottomSheet`（`skipPartiallyExpanded = true`），五个入口：
快速采集 / 从相册（禁用）/ 粘贴文本 / 链接 / 手动记录。
