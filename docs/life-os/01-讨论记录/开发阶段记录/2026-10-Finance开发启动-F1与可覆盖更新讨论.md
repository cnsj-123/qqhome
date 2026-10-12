思考了 4m 26s

可以，正式开始开发。

我看过你上传的 V11 交互原型和《需求落实与差距》。这次不能让 Codex 直接“把 HTML 改成 Android”，因为 V11 自己也明确区分了：它是本地可交互原型，“本地已具备”并不代表 Android 真机/云同步/生产架构已经完成；Android Room、跨端同步等仍属于正式工程阶段。 

而真实 main 现在还是已有的 Kotlin/Jetpack Compose Closie 工程，HEAD 是 ccae3dfa506d5b5a0a54a8159130bda8bcfc69d9，已经有 Compose、Navigation、现有衣橱数据和 Quick Capture，不能推倒重写。

所以第一步我建议做一个真正能跑起来的 Life OS Android 外壳 + Home/Nav/Theme 第一条纵切片。V11 里已经有负一屏左右手势、底部上滑聊天、照片大浮窗等明确交互，可以原生 Compose 重做。

你把 life_os_v11.html 和 Life_OS_V11_需求落实与差距.md 两个文件一起上传给 Codex，然后把下面整段原样发它：

你现在开始正式实现 Life OS Android App。

这是正式工程开发，不是继续修改 HTML 原型。

本轮只做：

Task 1 — Life OS Native Android Shell + V11 Home / Navigation / Theme Vertical Slice

不要试图一次完成整个 Life OS。

==================================================
0. 重要背景
==================================================

Repository:

cnsj-123/qqhome

正式 Android 开发基线：

branch:
main

开始 HEAD 必须是：

ccae3dfa506d5b5a0a54a8159130bda8bcfc69d9

产品 / 架构文档参考分支：

life-os-docs

固定文档快照：

d83ed9fe7d311f0525785332fdf2115d79b15d8b

另外我提供两个附件：

1.
life_os_v11.html

这是当前暂定的可交互 UI / UX 原型。

2.
Life_OS_V11_需求落实与差距.md

这是 V11 与需求文档的落实 / 差距对照。

==================================================
1. Authority 顺序
==================================================

遇到冲突时，严格按照：

CONFIRMED PRD
>
CONFIRMED Architecture v0.1 semantic baseline
>
V11 交互原型
>
旧 Closie UI
>
你的个人判断

注意：

V11 是 UI / 交互参考。

它不是新的业务事实模型。

不要因为 HTML 里存在某字段或 demo 数据，
就在 Android 里发明新的 Canonical 数据模型。

不要修改 CONFIRMED PRD 来适配 V11。

Owner 已经确认 Architecture v0.1 作为后续开发基线。

本轮不要修改产品或架构文档状态。

==================================================
2. 首先检查 Git
==================================================

执行：

git fetch origin
git checkout main
git status --short
git rev-parse HEAD

必须确认：

branch = main

HEAD =
ccae3dfa506d5b5a0a54a8159130bda8bcfc69d9

working tree clean

否则：

STOP。

报告实际状态。

不要自行：

merge
rebase
reset --hard

==================================================
3. 创建开发分支
==================================================

从上述 main 基线新建：

feature/life-os-shell-v11

禁止直接修改 main。

禁止 merge life-os-docs。

读取文档时使用例如：

git show origin/life-os-docs:docs/life-os/...

不要把整个 docs 分支合进代码分支。

==================================================
4. 开始写代码之前先 Inspect
==================================================

先实际阅读现有：

app/build.gradle.kts

app/src/main/AndroidManifest.xml

MainActivity.kt

ClosieApplication.kt

navigation/ClosieNavigation.kt

现有 ui/
data/
repository/
backup/
quickcapture/

以及已有测试。

必须先理解现有代码再修改。

不要另起一个完全独立 Android App。

不要删除现有 Closie 功能。

不要复制一套新的 Wardrobe Repository。

==================================================
5. V11 原型的使用规则
==================================================

必须实际打开并阅读：

life_os_v11.html

不要只看截图。

重点分析：

Home
Drawer
Chat entrance
Photo stack
Photo viewer
Calendar entrance
Map entrance
Theme
Gesture
Modal
Empty state
Typography
spacing
colour roles

V11 中以下交互作为本 Task 的主要 UI 参考：

- Home 是生活页，不是 Dashboard
- 小而克制的日期区域
- 左滑打开“生活的抽屉”
- 抽屉只占部分宽度
- 右滑 / 点击露出的 Home 收起抽屉
- Home 底部上滑进入 Companion
- 同时保留明确可点击的 Chat 入口
- 照片堆具有实体照片层叠感
- 照片可左右 / 上下翻
- 有倾斜、抬起、回弹感
- 点击照片使用大浮层
- 浮层不能完全替换 Home 页面
- Home 明确有历史 / 未来
- Home 有 Map / Calendar 入口
- 不使用传统 5-tab Bottom Navigation
- Calm Shell · Rich Life
- 极简框架 × 极繁生活

但是：

不要把 HTML 放进 WebView。

不要把 HTML / CSS / JS 当生产 runtime。

必须用 Native Kotlin + Jetpack Compose 重做。

==================================================
6. 本 Task 的真正目标
==================================================

本轮完成 Android 的“产品外壳”。

完成后第一次打开 APK：

用户看到的应该已经是：

Life OS

而不是旧 Closie 首页。

但原来的：

Closet
OOTD
Outfit
Quick Capture
Editor
Detail
Backup

不能因此被删掉。

它们需要逐步成为 Life OS 的专业模块。

==================================================
7. 本轮严禁大规模 rename
==================================================

不要现在把：

com.xiaoming.closie

整个 package 重命名。

不要修改：

applicationId

不要 mass rename 所有 Kotlin package。

不要为了产品名 Life OS 做几十个无意义 rename。

本轮允许：

Android 可见 app label：

Closie
→
Life OS

但是：

namespace
applicationId
底层历史 class name

本 Task 暂时保持。

后续会单独做 migration Task。

==================================================
8. 新的 Root Shell
==================================================

不要继续把旧：

ClosieNavHost

直接作为整个 App 的产品根导航。

建立新的：

LifeOsRoot

和：

LifeOsNavHost

命名可根据现有 package 风格微调，
但职责必须明确。

推荐结构：

ui/lifeos/
    LifeOsRoot.kt

ui/lifeos/home/
    LifeOsHomeScreen.kt

ui/lifeos/drawer/
    LifeOsDrawer.kt

ui/lifeos/chat/
    CompanionShellScreen.kt

ui/lifeos/components/
    LifePhotoStack.kt
    LifePhotoViewer.kt
    LifeEmptyState.kt

ui/lifeos/settings/
    AppearanceSettingsScreen.kt

navigation/
    LifeOsNavigation.kt

不要机械照抄路径；
如果现有项目已有更合适组织方式，
保持一致。

==================================================
9. Root Navigation 原则
==================================================

新的 Life OS root：

Home

为默认入口。

不要 Bottom Navigation。

主导航形态：

                    Companion
                        ↑
                        │
Drawer / Functions ← Home
                        │
                  Calendar / Map etc.

Home：

- 左滑 → Drawer
- 上滑底部 Chat Entry → Companion
- Map / Calendar 按钮 → 对应 route
- Capture 有明确入口

Drawer：

- 部分宽度
- Home 仍露出一部分
- 点击露出的 Home 收起
- 右滑收起
- Android back 优先收起 Drawer

不要破坏系统 Predictive Back。

不要把“从屏幕最左边向右滑”占掉 Android back gesture。

开启 Drawer 的主要手势应为：

Home 内容向左滑

而不是：

占用系统左边缘向右滑。

==================================================
10. Drawer 内容
==================================================

参考 V11 的分组。

分组至少表现为：

随手与回看
- Capture
- Inbox
- Calendar
- Search

生活的一格一格
- Closet
- Shopping
- Items
- Food
- Skincare
- Garden
- Reading
- Hobbies
- Travel
- Place
- Plans
- Life Marks
- Life Map

记录与作品
- Plog
- Gallery / Portfolio
- Knowledge
- Media
- Companion Chat
- Creative Vault

只属于自己的设置
- Finance
- Membership
- Health
- Privacy
- Backup
- Settings

注意：

尚未实现的模块：

不得伪造完整功能。

点击后只进入清楚标记的：

ModuleLanding / 尚待后续 Task

页面。

不要写 fake database。

不要伪造“已经记录了 XX 条”。

==================================================
11. 保留现有 Closet
==================================================

Closet 是现在真实已有的模块。

Drawer 点击：

Closet

必须可以进入现有衣橱功能。

现有：

ClosetScreen
DetailScreen
EditorScreen
OotdScreen
OutfitListScreen
OutfitStudioScreen

尽可能复用。

不要重新写一套 Closet。

现有衣橱数据必须仍然可读写。

现有 external intent：

ACTION_SEND

Edit item

Open Add

仍然必须正确路由。

不要因为换 root navigation 就破坏 Quick Capture
或已有分享入口。

==================================================
12. Home 第一版
==================================================

实现一个真正原生 Compose 的 Life OS Home。

视觉暂时以 V11 为准。

但不要追求 1px HTML 复刻。

重点是：

感觉
层级
留白
交互
节奏

必须符合：

Calm Shell
Rich Life

Home 至少包含：

A. Header

- Life OS 品牌文字
- Calendar
- Map
- Companion / light entry

不要大量工具按钮。

B. Date

日期不要巨大到支配页面。

要克制。

C. Life Photo Stack

建立：

LifePhotoStack

组件。

必须支持：

- 多张层叠
- 每张轻微 rotation
- z-axis 感
- drag
- threshold
- spring-back
- flip to next
- 左右手势
- 可扩展上下手势
- reduced-motion 时降级

不要复制 HTML pointer event 代码。

使用 Compose：

pointerInput
Animatable
spring / decay
appropriate gesture APIs

组件 API 必须是数据驱动。

例如：

photos: List<HomePhotoUi>

不要把照片写死在 Composable 里。

重要：

本 Task 没有 Canonical Media Store。

因此正式 App：

没有真实 eligible media 时：

显示安静 Empty State。

不得用假照片冒充用户人生记录。

可以：

在 @Preview
或 androidTest preview fixture

使用 demo HomePhotoUi。

不能把 demo 数据写入用户正式存储。

D. Photo Viewer

点击 Photo：

打开大浮层。

要求：

- Home 仍在背景
- 背景 dim / blur 可以轻微
- 不是导航到全新 fullscreen page
- 左右切图
- 支持 close
- Back 关闭
- 为未来 Motion Photo / paired video 保留 UI contract

本 Task 不需要真正生成 Motion Photo。

E. History

必须明确存在“历史”区域。

没有数据：

显示安静 empty state。

例如：

“以后，这里会慢慢留下以前的日子。”

不要编造记录。

F. Future

必须明确存在“未来”区域。

没有 Intent：

显示轻 empty state。

Intent 和 Event 在 UI 类型中不要混成同一个状态。

G. Bottom Companion Entry

Home 底部：

有很轻的 Companion entry。

支持：

点击进入 Companion

以及：

在这个 entry 上明确 upward gesture
进入 Companion。

正常 Home vertical scroll：

不能被拦截。

==================================================
13. Companion 本 Task 只做 Shell
==================================================

Companion 本轮不是 AI 工程 Task。

只实现：

CompanionShellScreen

视觉参考 V11 Chat。

需要：

- 消息页基本布局
- 返回 Home
- 输入区域
- 历史 / 新话题视觉入口预留

但是：

不要接 OpenAI
不要接 Anthropic
不要写假的 AI 回复
不要假装 Ombre 已连接
不要把本地用户文本自动变成 Canonical Fact

如果还没有真正 chat repository：

输入发送可以保持 disabled
或显示明确：

“伙伴连接将在后续工程接入”

不要 fake success。

==================================================
14. Theme / Appearance 本 Task 要完成
==================================================

V11 已经实现了主题概念。

本轮 Android 要把 Appearance Theme 真正 Native 化。

至少支持：

1. 暮山紫原色
2. 薰衣草信笺
3. 睡莲花园
4. 珍珠与旧纸
5. 水母来信

主题具体色值：

从 life_os_v11.html 提取。

不要凭感觉重新配。

建立 semantic color roles。

至少：

paper
paperSecondary
card
ink
inkSecondary
muted
accent
accentDeep
line

不要在页面里到处硬编码 Color(...).

Theme 必须：

语义角色驱动。

==================================================
15. 自定义主题
==================================================

Appearance Settings：

允许：

主色
纸张色
点缀色

自定义。

可以：

HEX 输入
Color preview

需要：

实时 preview
Save
Cancel
Restore original

保存后：

重启 App 仍保留。

这属于 Appearance Setting，
不是 Canonical Life Fact。

本 Task 使用：

AndroidX DataStore Preferences

可以。

不要为了主题引入 Room。

==================================================
16. 不实现 Canonical Room
==================================================

非常重要。

虽然正式 Life OS 后续会使用 Android Room
作为 Canonical local-first core，

但本 Task：

不要一边写 UI
一边随便设计一套 Room Schema。

Canonical Room：

是 Task 2。

因此本 Task：

- 不新增 world_object 表
- 不新增万能 Event 表
- 不新增万能 JSON Entity
- 不迁移 Closet JSON
- 不重复保存 Closet
- 不创建第二份 Life Truth

只建立：

UI Shell
Navigation
Appearance Settings
UI contracts

==================================================
17. Home 数据接口必须为 Task 2 留口
==================================================

Home 不直接查询：

WardrobeRepository

来假装整个 Life OS。

不要写：

repository.items -> Home facts

这种耦合。

建立 UI contract：

HomeUiState

示例语义：

date
eligibleMedia
todayFacts
historyItems
futureIntents
isLoading
emptyState

具体命名你根据 Kotlin 风格决定。

现阶段可以：

DefaultHomeStateProvider

返回真实 empty state。

后续 Task 2：

Canonical Query Layer

会替换 provider。

UI 不应该重写。

==================================================
18. Calendar / Map
==================================================

本 Task 只建立 route 和视觉 shell。

Calendar：

可以做 native 月历骨架。

但是：

不要创建假的事件。

必须为未来：

photo thumbnails
Event
Intent
time-bearing content

留 UI 数据 contract。

Map：

只做 landing / shell。

不要现在接 Google Maps。

不要申请 Location permission。

不要写

对，刚才截断了。下面这段直接接在上一条 “18. Calendar / Map” 后面发给 Codex 就行，不用重复前半段。

不要写假的地图数据。 Calendar / Map 本轮的目的只是： - 导航结构成立 - UI 风格成立 - 数据 contract 成立 - 后续 Canonical Query 接入时无需重写页面 ================================================== 19. Capture 入口 ================================================== Home 和 Drawer 都必须保留 Capture 入口。 但不要重写现有 Quick Capture。 现有项目已经有： QuickCaptureActivity CapturePreviewActivity QuickCaptureService 本轮优先： 复用现有 Capture 能力。 如果现有 Quick Capture 与新的 Life OS Root 入口不完全匹配： 只做最小 adapter / navigation bridge。 不要在本 Task： 重新设计 Capture 数据模型 重新实现 OCR 重新写截图服务 重新写悬浮球 重新写 Draft Store 已有能力能保留就保留。 ================================================== 20. Navigation 兼容要求 ================================================== 需要明确处理以下场景： 1. 正常启动 App → Life OS Home 2. Drawer → Closet → 现有 Closet 3. Closet item detail / edit → 继续正常 4. ACTION_SEND text/plain → 继续进入原有 import / add 流程 5. EXTRA_EDIT_ITEM_ID → 继续进入指定衣物编辑 6. EXTRA_OPEN_ADD → 继续新增衣物 7. Quick Capture 完成后返回正式 App → 不应该落入一个已经废弃的旧 NavHost 状态 8. Android Back 优先级应该合理： Modal > Photo Viewer > Drawer > Module detail > Home > system exit 不要通过大量 Boolean 手写一个无法维护的返回栈。 使用 Navigation Compose 和明确的 UI state。 ================================================== 21. 不要建立第二套 Bottom Navigation ================================================== 旧 Closie 当前有： 首页 衣橱 OOTD 搭配 Bottom Navigation。 Life OS Root 不再使用这个作为全局导航。 但是： 进入 Closet 专业模块后， 如果短期为了保留旧功能必须保留局部导航， 可以先作为兼容层存在。 优先方案： 把 Closet / OOTD / Outfit 整理成 Closet domain 内部的局部页面。 不要让旧四 Tab 继续主导整个 Life OS。 不要在本 Task 为了消灭 Bottom Navigation 而大规模重写所有 Closet 页面。 目标是： Root 级不再出现旧 Closie Bottom Navigation。 局部兼容可以暂留， 并在报告中说明 technical debt。 ================================================== 22. UI 视觉规则 ================================================== 当前暂定 V11。 不要自行换成： Material Dashboard Notion Apple Health Todo App 金融 App 卡片瀑布流 视觉方向： Calm Archive Editorial Life Digital Scrapbook Personal Life Archive 关键词： 暖纸张 低饱和 细线 轻纹理 柔和层叠 照片实体感 杂志式留白 轻手账 克制装饰 安静 私人 有生活气 但是： 不要为了“手账感” 堆很多： 胶带 贴纸 邮票 阴影 花纹 核心界面仍然要克制。 “极繁”来自用户未来的数据、照片、经历。 不是来自 UI 装饰本身。 ================================================== 23. Typography ================================================== 不要为了追求原型效果 直接塞大量未知版权字体进 APK。 优先： 系统字体 Android 可合法使用的字体 项目已有字体 如果 V11 中的： Noto Serif SC Noto Sans SC 需要用于视觉接近： 确认依赖和许可证合法后再引入。 不要提交未经授权的 font 文件。 建立 typography roles： display title body caption handwritten-accent（如果确实需要） 手写字体只能点缀。 不能用于： 大量正文 设置 关键操作 数据字段 ================================================== 24. Motion ================================================== Motion 要克制。 优先使用： spring fade small translate small rotation subtle scale Photo Stack 可以最明显。 Drawer： 应该有自然的 lateral motion。 Viewer： 小范围 scale / fade。 不要： 炫技 page transition 3D cube 大量 parallax 持续动画背景 粒子 自动播放花哨 motion 必须尊重： MotionDurationScale reduce motion / accessibility 用户关闭动画或动画比例为 0 时： 关键功能仍正常。 ================================================== 25. Accessibility ================================================== 至少做到： - 所有 icon button 有 contentDescription - 可点击目标尽量 >= 44dp / 48dp - TalkBack 可以理解主要入口 - 不只靠颜色表达 selected / pending / future - Theme 自定义后仍检查基本文本对比度 - Photo Viewer 有明确关闭入口 - Drawer 可通过 Back 收起 - 字体放大后不应出现核心按钮完全不可用 - Landscape 不要求完美，但不能 crash ================================================== 26. State / Architecture 边界 ================================================== 本 Task 不允许出现： UI 直接写文件 UI 直接操作 SharedPreferences UI 直接知道磁盘路径 Composable 里直接执行业务 mutation Appearance： Repository / Store ↓ StateFlow ↓ ViewModel or presentation state ↓ Compose Home： HomeStateProvider ↓ presentation state ↓ Compose Navigation： 只负责 route / destination。 不要把业务 repository 塞进每个 Composable 然后到处直接调用。 同时： 不要为了一个 Task 引入过度复杂的 Clean Architecture 模板。 保持： 清楚 可测试 足够薄 ================================================== 27. DataStore ================================================== 本轮新增 DataStore 仅用于： Appearance / UI preferences 例如： selectedThemeId customPaper customAccent customSecondary motionPreference（只有需要时） 不要用 DataStore 存： Life Facts Transactions Meals Events Media metadata Chat history Items 正式事实后续进 Canonical Store。 ================================================== 28. Demo 数据规则 ================================================== 非常重要。 HTML V11 有大量： demo seed 示例照片 示例记录 Android 正式 App 不得 在第一次启动时偷偷导入这些内容。 正式构建： 空用户 = 空 Life OS 应该是好看的空状态。 可以有： @Preview fixtures test fixtures debug-only sample provider 但： Release / normal debug runtime 不能自动写 demo 人生记录。 ================================================== 29. Existing user data safety ================================================== 绝对禁止： 删除旧 Closie 数据 修改现有 JSON 为不兼容结构 清空 filesDir 重建用户 wardrobe 破坏 BackupManager 改变图片路径语义 本 Task 做 UI Shell， 不是数据迁移。 如果必须修改旧结构： STOP。 在报告里说明为什么。 不要自行 migration。 ================================================== 30. App name / Manifest ================================================== 可把用户可见 label： Closie 改为： Life OS 最好改为 string resource， 不要直接硬编码 Manifest。 但是暂时保持： applicationId = com.xiaoming.closie namespace = com.xiaoming.closie 已有： QuickCaptureActivity CapturePreviewActivity QuickCaptureService 全部必须继续注册并工作。 不要删除权限， 除非你确认它已经完全无用且不影响旧能力。 不要本轮顺便“清理 Manifest”。 ================================================== 31. Theme 实现建议 ================================================== 不要把 V11 色值简单全部塞进： MaterialTheme.colorScheme 然后结束。 建立： LifeOsColors 或等价 semantic palette。 例如： data class LifeOsColors( val paper: Color, val paperSecondary: Color, val paperTertiary: Color, val card: Color, val ink: Color, val inkSecondary: Color, val muted: Color, val accent: Color, val accentDeep: Color, val line: Color ) 通过 CompositionLocal 提供。 MaterialTheme： 可以作为底层技术基础。 Life OS semantic theme： 作为产品层。 现有 Closet： 本轮不要求完全视觉重做。 但至少保证进入 Closet 后： 不会因为主题切换 crash。 ================================================== 32. Theme Preset ================================================== 从 V11 HTML 中实际提取配色。 不要只读名字。 创建 enum / sealed model，例如： MOUNTAIN_PURPLE LAVENDER_LETTER WATER_LILY PEARL_OLD_PAPER JELLYFISH_LETTER CUSTOM 具体英文 ID 可自行选择稳定命名。 显示名保持中文。 Theme ID 保存稳定， 不要保存中文显示名作为主键。 Custom theme： 如果用户后来切换 preset， 旧 Custom value 不要丢。 用户再次选择 Custom： 可以恢复上次自定义颜色。 ================================================== 33. Home Photo Stack API ================================================== 组件应该是可复用的 presentation component。 例如概念上： data class HomePhotoUi( val id: String, val uri: Uri?, val title: String?, val caption: String?, val mediaKind: MediaKind, val motionAvailable: Boolean ) 不要求名称完全相同。 关键是： UI 只拿 Presentation Model。 不要让组件知道： Canonical DB Wardrobe JSON file storage implementation Photo Stack： 处理交互。 外层： 处理数据来源。 ================================================== 34. Media loading ================================================== 现有工程已有 Coil。 优先继续使用。 不要新增第二套图片加载库。 处理： null missing file decode failure 都不能 crash。 缺失图片： 显示安静占位。 未来： MediaAsset source missing 是正式状态， 不是直接把 UI item 删掉。 本 Task 只需要为这个语义留出显示能力。 ================================================== 35. Tests — 必须写 ================================================== 不能只写 UI 然后 assemble。 至少增加以下测试。 A. Theme persistence test 验证： - preset save - custom save - app repository reload - custom value still exists - restore original works 尽量测试 store / serializer， 不要依赖截图。 B. Home state test Default empty provider： - no fake history - no fake future events - no fake media C. Navigation / route test 至少测试： - default route = Life OS Home - Closet route exists - old external commands Import / Edit / Add 仍可解析到正确 destination 如果 UI navigation 测试成本过高： 把 route resolution 提取成纯逻辑单元测试。 D. Theme validation 非法 HEX： 不能 crash。 应： reject or preserve previous valid value E. Existing tests 全部必须继续通过。 ================================================== 36. CI ================================================== 现有 GitHub Actions 至少会执行： ./gradlew :app:assembleDebug 本轮本地必须跑： ./gradlew test 以及： ./gradlew :app:assembleDebug 如果项目已有： lint ktlint detekt 也运行已有检查。 不要未经要求新增一整套 lint framework。 ================================================== 37. Screenshot / Preview ================================================== 请给核心 Compose 页面加 Preview： 至少： LifeOsHomeScreen LifeOsDrawer PhotoStack AppearanceSettings CompanionShell Preview 使用假的 presentation data 完全可以。 但必须明确： PreviewOnly / Sample 不能注入 runtime repository。 ================================================== 38. 第一轮不做的事情 ================================================== 本 Task 明确不做： - Canonical Room DB - World Object - Unified Event Store - Cross-domain Fact Registry - Full Finance - Food production implementation - Shopping production implementation - Membership production implementation - Garden production implementation - Health production implementation - Knowledge production implementation - Real Search Engine - Vector Search - AI Search - Ombre Brain integration - OpenAI / Anthropic integration - Agent - MCP - Plugins - capability token production system - cloud sync - multi-device sync - E2EE sync - Vault final cryptographic architecture - Google Maps - GPS tracking - Health Connect - Motion Photo generation - full Media Library migration - old Closie data migration into Life OS Core - notification engine - Work Scheduler - Attention Gate production engine 不要觉得“顺手可以写”。 不写。 ================================================== 39. 不允许的捷径 ================================================== 禁止： 1. WebView 加载 life_os_v11.html 2. 把整个 HTML 放 assets 然后称为 Android 实现 3. 一个 3000 行 Compose 文件实现所有页面 4. 所有状态 mutableStateOf 塞 MainActivity 5. 用 SharedPreferences 保存所有 Life OS 数据 6. 为每个模块创建假的 hardcoded 数据 7. 为了显示完整产品，伪造： “3 个会员” “20 杯咖啡” “¥1234 本月支出” 等 8. 重写整个现有 Android 工程 9. 删除旧衣橱测试 10. 删除 BackupManager 11. 改 applicationId 12. merge life-os-docs 13. 修改 CONFIRMED PRD 14. 因为架构复杂就自创简化事实模型 15. 一次提交几十个未来 Task 的半成品 ================================================== 40. 文件规模 ================================================== 避免： God File God Composable God Repository 粗略原则： 一个文件 > 500–700 行时， 检查是否职责过多。 不是机械限制， 但不要重新出现一个： LifeOsScreen.kt 4000 行 的情况。 组件按职责拆。 ================================================== 41. 本 Task 的 Acceptance Criteria ================================================== 完成后必须满足： [ ] App 冷启动进入 Life OS Home [ ] 用户可见 app 名称为 Life OS [ ] Root 不显示旧 Closie 四项 Bottom Navigation [ ] Home 左滑能打开部分宽度 Drawer [ ] Drawer 右滑 / 点击露出 Home / Back 可关闭 [ ] Drawer 中包含主要模块入口 [ ] Drawer → Closet 可进入现有真实 Closet [ ] 旧 ClothingItem 数据仍可读取 [ ] Item detail/edit 仍可工作 [ ] ACTION_SEND 仍可工作 [ ] Quick Capture 没有被破坏 [ ] Home 有 Calendar 入口 [ ] Home 有 Life Map 入口 [ ] Calendar shell 可进入并返回 [ ] Map shell 可进入并返回 [ ] Home 有明确 History [ ] Home 有明确 Future [ ] 空数据不伪造记录 [ ] Photo Stack 为 Native Compose [ ] Photo Stack 有 drag + spring-back + next-card behavior [ ] 点击照片为浮层 viewer，而不是 WebView/full page replacement [ ] Home 底部 Companion entry 可点击 [ ] Companion entry 上滑可进入 Chat Shell [ ] 普通 Home vertical scroll 不被上滑聊天手势破坏 [ ] Companion Shell 不伪造 AI 回复 [ ] 5 个 V11 theme presets 可切换 [ ] Custom theme 可编辑 [ ] Save / Cancel / Restore original 工作 [ ] Theme 重启 App 后保留 [ ] 页面无主要硬编码颜色散落 [ ] TalkBack 核心按钮有描述 [ ] Back 行为合理 [ ] Existing unit tests pass [ ] New tests pass [ ] :app:assembleDebug 成功 ================================================== 42. 实施顺序 ================================================== 严格按这个顺序做： Phase A Inspect existing project Phase B 建立 theme semantic layer + DataStore Phase C 建立 LifeOsRoot / LifeOsNavHost Phase D 把旧 Closet 接进新 Root 先确保： 现有功能没坏。 Phase E 实现 Home empty production state Phase F 实现 Drawer Phase G 实现 Photo Stack / Viewer Phase H 实现 Calendar / Map / Companion shells Phase I Appearance Settings Phase J tests Phase K build 不要先花几个小时做漂亮 Home， 最后才发现 old navigation 全坏了。 ================================================== 43. Git 纪律 ================================================== 本 Task： 只允许在： feature/life-os-shell-v11 工作。 不要 push。 不要直接修改 main。 不要 merge。 不要 rebase。 不要 force push。 不要提交： APK build/ .gradle/ IDE cache local.properties HTML 原型附件副本 需求附件副本 除非仓库规则明确要求。 ================================================== 44. Commit ================================================== 最终尽量整理为 1 个正式提交： feat(android): establish Life OS shell and V11 home 如果确实需要多个工作提交： 最后 squash 成一个。 但是： 不要 rebase 远端历史。 只整理你自己本地 Task 分支。 提交前： git status --short 必须确认没有： build artifact 临时 screenshot 本地配置 附件 无关文件 ================================================== 45. 不 Push ================================================== 完成后： 不要 push GitHub。 不要开 PR。 不要 merge main。 人工会先审查。 ================================================== 46. 最终交付 ================================================== 完成后只给我以下报告： A. Baseline - branch - base SHA - final commit SHA B. Architecture / implementation summary 说明： - 新 Root 如何工作 - 旧 Closet 如何挂接 - external intents 如何保留 - Home state contract - theme architecture - DataStore 用在哪里 - 哪些地方刻意没有实现 C. Files changed 逐文件说明。 D. Tests 给出你实际执行的命令和结果： ./gradlew test ./gradlew :app:assembleDebug 以及其他实际运行的检查。 不能写： “应该通过” “理论上通过” 必须是真实执行结果。 E. Acceptance checklist 把第 41 节逐项： PASS PARTIAL FAIL 并说明证据。 F. Technical debt 只写本 Task 真正遗留的问题。 不要把整个未来 roadmap 都抄一遍。 G. Diff summary git diff --stat <base>..HEAD H. Patch 生成： git format-patch -1 HEAD --stdout \ > life-os-shell-v11.patch 注意： 不要把 patch 文件本身加入 Git commit。 告诉我 patch 的绝对路径。 ================================================== 47. Stop conditions ================================================== 以下情况不要自行扩大范围解决。 必须 STOP 并报告： 1. 真实 main HEAD 不是指定 baseline 2. 工作区不干净且改动不是你的 3. 需要破坏旧 Closie 数据格式 4. 需要修改现有 Backup format 才能继续 5. 必须改 applicationId 才能继续 6. 必须重新设计 CONFIRMED Canonical architecture 才能继续 7. V11 与 CONFIRMED PRD 存在无法兼容的语义冲突 8. 现有 Quick Capture 架构会因为新 Root 必然失效， 且修复需要超出 navigation adapter 9. Gradle / Android 工程本身在 baseline 就不能构建 这种情况： 不要顺手修十几个历史问题。 只给出： 实际证据 最小原因 推荐下一步 ================================================== 48. 你不是产品经理 ================================================== 本 Task 已经定义。 不要： 重新做产品 discovery 重新决定 Life OS 应该是什么 自行砍模块 自行把产品改成 productivity app 自行“优化”用户需求 自行换 UI 方向 遇到小实现选择： 选择： 最小 可逆 可测试 不锁死未来架构 的方案。 ================================================== 49. 最终产品感觉 ================================================== 这一轮跑起来以后， 我不要求它已经拥有 Life OS 的全部能力。 但我要求： 打开 APK 的第一感觉已经从： “一个衣橱 App” 变成： “这是我的 Life OS， 衣橱只是其中一个专业模块。” 同时： 它不能只是一个漂亮空壳。 旧的真实 Closet / Capture 能力 必须继续存在并可进入。 这就是本 Task 的成功标准。 现在开始。 先执行 Git baseline check 和 repository inspection。 不要先写代码。 先在回复中给出一个非常简短的实施计划， 确认你读取了： - 当前 Android 工程 - life_os_v11.html - Life_OS_V11_需求落实与差距.md - life-os-docs 中相关 CONFIRMED PRD / Architecture 然后立即执行 Task。 不要等我再次确认。 

这一轮我刻意把范围卡得比较死：先把 Life OS 的“骨架”换上，但不碰核心事实库。 这是因为你现在的 V11 原型已经把 Home、负一屏、照片堆、聊天入口、主题等交互表达得比较完整，而正式 Android/跨端/同步仍明确属于工程接入阶段。 现有 Android 工程又已经有真实衣橱、导航、分享入口和 Quick Capture，所以第一步最重要的是“换壳不毁数据”，而不是推倒重写。

等 Codex 做完这一轮，把它的完整报告 + patch发给我。我会按这一份 Task 逐项独立 Review，过了之后我们再下 Task 2：Canonical Local Core / Room 数据底座。


——————————————————————————————————————————

你还记得我们要做的东西吗，你再复习一下https://github.com/cnsj-123/qqhome/tree/life-os-docs/docs/life-os
我们不要做太快，搭完架子之后每个板块细细修改一下，v11目前只能说勉强入眼


——————————————————————————————————————————

思考了 2m 6s

我记得，而且我刚重新把 life-os-docs 的入口、索引、原始愿景、Home、跨模块、Companion 和统一架构又过了一遍。你提醒得对：现在最危险的不是做得慢，而是因为 V11 已经“能点了”，就误以为产品已经设计完了，然后 Codex 一口气把它固化成正式 App。

我们不是要做“V11 的 Android 复刻版”。

Life OS 的根本目标还是这一套：它不是衣橱 App 扩张出来的一堆菜单，而是一个长期的、local-first 的个人生活操作系统 / 个人世界模型。购物、衣橱、物品、财务、食物、健康、兴趣、园艺、旅行、阅读、媒体、AI 等不能变成十几个彼此不认识的小 App；同一真实事实应当被不同模块引用，而不是重复录入。

所以 V11 的定位我现在重新定得更准确

V11 = 第一份“交互骨架参考”，不是最终 UI。

它现在有价值的是帮我们验证这些东西：Home / 负一屏、上滑 Companion、照片堆、弹窗、Calendar、Map、主题切换，以及“这个 App 大概怎么走起来”。

但颜色、字体、版式、模块页面、细节密度、动画、照片表现，甚至某些页面的信息结构，全部还可以推翻。这其实和 Home PRD 本身一致：Home 明确还没有冻结最终视觉、字体颜色、动画参数、功能页布局和顶层导航。

你说 V11 “目前只能勉强入眼”，我会按这个标准对待它，绝不会以后拿一句“原型就是这样”来卡你。


---

我们真正要做的东西，我现在理解成 4 层

第一层是“生活事实”。 Life OS 要保存的是你真实的人生，而不是页面上的卡片。一次购买、一次付款、一件衣服、一次吃饭、一场演唱会、一趟旅行，各有自己的语义，但可以互相关联。架构里已经明确了 One Canonical Type, One Owner，而 Home、Calendar、Search、Life Marks、Capture 等只是 Surface，不应该再造第二份事实。

第二层是“专业模块”。 Closet 就像真正的衣橱；Finance 要像财务；Food 要像食物日记；Garden 要像植物档案；Travel 要像旅行册。我们不要为了所谓统一设计，把它们全做成一模一样的圆角卡片。

第三层才是 Home / Calendar / Search / Map 这些跨生活入口。 Home 不是 Dashboard，而是“个人生活封面 / 今日手账”；没东西时允许安静，生活丰富时才自然长出来。历史、未来、照片、回忆都应该有，但不能把它塞成管理后台。

第四层是 Companion。 它仍然是“网友 / 伙伴”，不是任务管家，更不是 Agent 控制台。聊天历史、Ombre Memory、Life OS 正式事实各自独立；AI 可以查授权信息、提出 Proposal，但不能偷偷改正式生活数据。


---

开发速度我们改一下

我不赞成我上一条最后说的那种：

> Task 1 Shell → Task 2 一口气做整套 Canonical Core → 然后飞快铺模块



太快了。

现在改成：

> 先搭不会限制未来的架子，然后一个板块一个板块做。



当前 Codex 的 Task 1 只完成 Shell：

Home + 功能负一屏 + 基础导航 + Theme 基础 + 保留现有 Closet + 几个空 Shell

到这里就停。

它完成以后，我们不马上给第二个大开发任务。


---

后面每一个板块都按同一个循环

例如我们下一步决定细修 Home，那么我会先：

1. 重新读 Home PRD + 相关讨论 + V11 Home。


2. 把“必须有的需求”和“V11 只是暂时长这样”分开。


3. 跟你细聊 Home：日期、照片怎么翻、历史怎么出现、未来怎么出现、地图、空白日、长图导出……


4. 必要时再找专门针对 Home 的参考，不做全局乱搜。


5. 先做 2～3 个局部交互/视觉方案给你挑。


6. 你觉得“这个真的可以用了”，我们才冻结 Home v0.1 UI。


7. 然后我才给 Codex 一个只实现 Home 的严格 Task。


8. Codex 做完 → 我审代码 → CI → 你真机玩。


9. 不满意继续改 Home，不急着跑下一个模块。



然后才去下一个。

这才比较符合你这个项目。


---

模块顺序也不用现在一次定死

我目前只会先定很近的几步：

Shell → Home / 功能页 → Calendar / Capture / Search 这些基础入口 → Closet

因为 Closet 已经有真实代码和真实数据，正好可以检验：

> Life OS 的壳是不是能承载一个真正完整的专业模块，而不是只会展示漂亮 Demo。



之后 Shopping / Items / Finance 等跨模块，我们再一起细做。不会今天就写一张“未来 30 个 Task”然后机械执行。


---

还有一个我刚复习仓库时发现的小状态问题：GitHub 当前 README、文档索引和 Architecture 文件本身仍写的是 DRAFT / 架构草案，虽然你在我们这里已经明确说“确认 Architecture v0.1”。

这个不影响我们现在做 Shell，但在开始真正的数据底座之前，我建议把仓库里的状态也同步一次，免得以后 Codex 一边收到我说“CONFIRMED”，一边读文件看到“DRAFT”，自己猜。

所以现在不用急。

先让 Codex 把 Task 1 的架子搭完。它一完成，我们先审架子，不自动进入 Task 2。然后从 Home 开始，一个模块一个模块慢慢雕。

这个节奏我觉得才对。

——————————————————————————————————————————

OK，就是不用想着之前写的那些代码怎么使用，实在不行，就是可以推翻重写的。然后我们就是除了审代码审bug，还有一个就是要审架构，尽量减少屎山代码的出现

——————————————————————————————————————————

对，这个我非常赞成，而且我觉得我们现在应该把它正式变成开发原则：

旧代码不是资产负债表上的“必须复用资产”，它只是参考实现。

能复用就复用；复用会把新架构拖歪，就宁可重写。

但这里要区分一件事：代码可以推翻，用户数据和已经确认的产品语义不能随便推翻。 比如旧 Closet 某些 UI、Repository、Navigation，如果以后发现结构不合适，完全可以重写；但衣物、穿着记录、图片、购买关系这些真实数据，迁移时必须有明确方案，不能为了“代码干净”直接丢掉。

后面我会把 Review 从以前的“有没有 bug”升级成四层一起审：

需求 Review：有没有做错产品，是否偷偷简化/扩张 PRD。

架构 Review：Domain ownership、依赖方向、数据边界、状态管理、写入路径有没有被破坏。

代码 Review：可读性、职责拆分、重复代码、God Object、耦合、测试性、错误处理。

运行 Review：构建、测试、真机、交互、数据安全、异常恢复。

以后我不会只因为：

assembleDebug 绿了

就说“通过”。

尤其我们要主动防这几类“屎山来源”：

一个 LifeOsRepository 什么都管；

一个 MainViewModel 管几十个模块；

一个 NavHost 里塞所有业务判断；

Composable 直接读写数据库/文件；

UI 为了方便直接跨 Domain 改数据；

每个模块各存一份相同事实；

到处 Map<String, Any> / JSON 万能字段；

“先做出来再说”留下十几个临时兼容层；

为了复用旧 Closie，不断在旧结构上套 adapter；

一个文件几千行，所有状态靠 Boolean；

每个新功能都去改一个中央 switch；

测试只能靠真机手点；

数据 migration 没有版本；

“暂时”代码没有退出条件，最后永远存在。

这些以后我看到会直接卡住，不会因为“功能能跑”就放过。

我建议从现在开始，每一个 Codex Task 都加一个固定的 Architecture Gate。

开发前它必须先说明：

这次新增什么职责？
放在哪一层？
谁 Own 数据？
依赖谁？
谁不能依赖它？
是否新增持久化？
是否影响已有 Domain？
是否产生 migration？

如果回答不清楚，先不写。

开发完成后，我再反过来检查：

实际代码有没有按照这个边界写？

这样就能防止 Task 描述是一套，最后代码为了方便又全耦合到一起。

还有一个我觉得对我们项目特别重要的原则：

不做“未来架构预支”

不要因为未来有 30 个模块，现在就先造：

UniversalEntityManager GenericLifeObjectService EverythingRepository DynamicModuleEngine

这种听起来很高级的万能系统。

我们确认的是语义架构，不代表现在就要把所有抽象一次写完。

更好的方式是：

先有明确边界，再让抽象从 2～3 个真实模块中长出来。

例如先把 Home、Closet、Shopping 做好，真的发现三者都有相同的 Relation / Media / Proposal 流程，再抽公共层。

而不是一开始猜一个“万能框架”。

所以开发节奏我现在会调整成：

搭 Shell → 审 Shell 架构 → 细做一个模块 → 审需求 + 架构 + 代码 + 真机 → 必要时重构 → 再进入下一个模块。

并且每做完几个模块，我们做一次专门的 Architecture Health Review，不加功能，只看：

模块依赖图有没有变乱；

有没有循环依赖；

Domain ownership 有没有漂移；

公共层是不是越来越肥；

有没有重复模型；

有没有 temporary hack；

test coverage 是否跟得上；

migration / storage 是否开始危险。

如果发现问题，宁可花一个 Task 重构，也不要一直往上堆功能。

我后面给 Codex 的指令里也会明确一条：

“旧代码可删除、重构或替换；不要以最大化复用为目标。目标是保持产品语义正确、数据安全、架构清晰和长期可维护。”

这样比较符合我们现在真正要做的 Life OS，而不是把旧 Closie 一层一层包成最后谁都不敢动的遗迹。

——————————————————————————————————————————

没关系，之前没有数据，所以影响不大

——————————————————————————————————————————那就更好办了。既然旧工程里没有真正需要保护迁移的用户数据，我们就不用为了“兼容历史数据”背太多包袱。

这样我们的原则可以更激进一点：

旧代码能复用就复用，但不以复用为目标。

旧数据模型如果不适合 Life OS，可以直接替换。

旧导航、Repository、ViewModel、页面结构如果妨碍新架构，可以推翻。

只保留真正有价值的实现经验，比如 OCR、图片处理、Quick Capture、某些衣橱交互，必要时抽出来重写。

不需要为了兼容空数据做复杂 migration 层。


这会明显降低后面形成屎山的概率。

所以我会把后续审查标准再收紧一点：不是“旧代码有没有被破坏”，而是“新代码是否值得成为未来 2～3 年继续长的基线”。

Task 1 我们还是先让 Codex 把 Shell 搭起来；但等它回来，我会重点看三件事：Root/Navigation 有没有搭歪、Theme/UI state 有没有过度耦合、它有没有为了兼容旧 Closie 留下一堆没必要的 adapter。该删就删，该重构就重构，不会为了“已经写了”而保留。