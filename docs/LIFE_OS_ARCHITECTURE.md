# Unified Android baseline — engineering self-review

本文件记录当前实现取舍，不修改或替代 confirmed PRD / 统一架构。只读审查的需求来源为 `origin/life-os-docs`，SHA `d83ed9fe7d311f0525785332fdf2115d79b15d8b`；施工 base 为 v0.3 `d7c3c859d900f749625b72be0ce9fb62fec0e1d6`；V11 / signing 参考为 `a6d46729416a4f7d2fc48e966dc048ecbeef6811`。

## 保留

- v0.3 typed Capture / Reference / Plan repositories、Media resource/link backend、Room v2 及显式 1→2 migration：当前业务字段在专属表中，metadata registry 没有万能 JSON payload。不为尚未实现的领域预建表。
- RestoreStartupGate、恢复前快照、恢复协调器和 backup/restore regression：先 recovery 再读业务数据，失败关闭屏障；没有 destructive fallback 或盲目级联。Application 的恢复顺序不变。
- Closet：保留真实 repository、editor/detail/OOTD/outfit、Quick Capture 和局部导航，作为 legacy 专业模块。其 purchase/price 字段不定义未来 Shopping / Finance owner。
- Closet navigation 只保留 embedded module 契约，删除无调用方的 Standalone 模式；默认进入衣橱，退出 callback 必须由 Life OS owner 提供。
- V11 Home / drawer / Calendar / Map / Companion / photo stack/dialog viewer / semantic Appearance：保留已确认的产品方向。runtime Home 仍为诚实空 projection，无假 canonical rows。

## 重写

- 单一启动链：MainActivity → LifeOsApp（composition）→ LifeOsRoot → LifeOsNavHost。MainActivity 只接收 Android intent、生命周期、system bars。Navigation 仅做 route/screen/callback wiring；按 Capture、Reference、Plans、Closet 拆分 route registration。
- ExternalCommandViewModel 保存 pending/consumed UI request，旋转或 recreation 不重放已消费 intent。ProductImport/Edit/Add 委托 Closet；ReferenceLink/CaptureText 由独立 intake controller/coordinator 保存证据并路由。固定 request id 防重，metadata 失败保留 Capture detail；旧异步完成不能清掉新请求。
- Capture 收件箱/详情 state、查询、保存由各自 ViewModel 管理，不在 Composable 发起 repository mutation。当前不开放单条永久删除，presentation/repository/DAO 的单条删除 API 已移除；受控 backup/restore 的 deleteAll 保留。手动编辑取消/空白保存不创建行。删除未被使用的 rawText 覆写 API，用户注释不能改变原始证据；Capture 状态确认不代表 domain Truth commit，DISMISSED 也不是 Trash lifecycle。
- Reference 是 unclassified source archive，不等于最终 Knowledge。原始 share 保留在 Capture，自动提取内容存 `ocrText`，`summary` 仅存用户文字；系统 summary 能力尚未实现。自动提取不会被冒充用户笔记。
- module UI 的 LifeColors 变成 V11 semantic palette 的薄别名，删除独立旧产品主题；保留仍被真实 module 使用的字体/尺寸 roles。AppearanceRepository 单独持有 UI preferences，不触发 Room/wardrobe。
- 唯一 LIFEOS signing 管线、main-only release、debug package 隔离，版本沿用 300000+CI run number。

## 删除 / 关闭

- 五项底部旧 LifeShell、重复 Home/Profile/module catalog/placeholder navigation：已由 V11 壳及唯一模块目录取代，未留死 root。
- 旧 product LifeTheme / Material typography fallback：不再有独立 paper palette/产品主题 owner。Closet 保留局部 legacy theme，因为它仍有真实调用方。
- public PhotoPicker → managed original copy → Reference flow 与 container 的 MediaStoreImporter accessor：与“优先原相册引用”冲突，撤下相册入口及 importGalleryImage。实验 managed-copy backend 留作独立测试/旧 media backup 路径定义，不被 Application/container/user-facing picker 调用。
- 旧 ANDROID signing 与 stable-sign debug fallback、过时工程 roadmap：已被唯一 update-safe 方案取代。

## 明确延后

- 最终 Media original-resource reference / reconciliation / grant recovery。现在不建立第二份 canonical 原图；暂不对外宣称完整媒体库。
- Plans 当前是 typed 最小 Intent 能力：`dueAt` 仅 soft planned date，不是 hard deadline，更不是 Event。Goal/Project/Task 树、waiting/paused、recurrence 后续在 Plans owner 扩展；当前 schema 不能被当作已完成的 Plans 总模型。
- Reading 当前是已保存阅读资料 projection，不声称已有 ReadingWork/ReadingSession domain。
- 完整 Knowledge raw/extraction/system-summary/user-note 体系、跨域 query layer、Vault secure world、AI、sync：没有在此实现。metadata registry 不假设自动纳入 Vault，不做全库 AI 索引。
- Finance、Shopping、Items、Membership 等仅留模块入口，没有实现或 schema。
- confirmed 架构要求的正式 Truth MutationKernel、ChangeSet 与 transactional outbox 尚未实现。本轮保留的 intake/archive、最小 Plan Intent 和 legacy Closet API 不是未来 Finance 等正式事实提交的模板；专业领域开始写 Truth 前，必须接入其 typed owner 和原子提交/审计边界，不得沿用普通 CRUD 来绕过这些不变量。

## 依赖与所有权

UI → ViewModel/controller → typed repository/importer → storage。Home/Calendar/Map 不依赖 DAO、WardrobeRepository 或 OOTD UI。共享媒体 DTO 属于 `ui.lifeos.media`，不归 Home。Calendar 的 MonthGrid 是纯日期算法；Intent/Event 是不同 UI contract。只有一个 Room database，没有 Home/Calendar/Map database；DataStore 只存 Appearance。

## 审查限制

本次保留 DB v2 不是为了 prototype 数据兼容，而是已有 typed 表/metadata 边界满足当前能力，不需要额外 canonical 表。未来专业领域需自己的 typed owner，不能写 LifeEntity JSON。Backup 保留的是 recovery-before-destruction 和本地恢复安全，不等同于 sync。真实手机 gesture/TalkBack/predictive-back 与长期 key 覆盖安装仍需 Owner 人工验证；本地 unit/build/APK inspection 结果由交付报告列出。
