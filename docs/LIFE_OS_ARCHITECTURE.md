# Life OS 架构（v0.1）

Life OS 是在既有 Closie 衣橱之上新增的一层「生活记录」骨架。v0.1 的目标是**把地基打实**：
包迁移、数据库、仓储层、App Shell 全部落地并可被后续功能直接复用；不新增业务功能。

## 1. 分层

```
MainActivity
   └── LifeShellNavHost            (com.qq.closie.life.ui.shell)
         ├── LifeHomeScreen        首页：日期 / Today / 最近记录 / 空状态
         ├── TimelineScreen        记录：时间线骨架
         ├── LifeModulesScreen     生活：模块目录（衣橱可进入，其余 Coming soon）
         ├── ProfileScreen         我的：设置 / 备份 / 媒体库 / 同步 / 版本
         ├── ClosieNavHost         衣橱（复用既有 Closie，自带底部栏）
         └── CaptureBottomSheet    ＋ 采集入口（5 种方式）
                    │
ClosieApplication ──┴── LifeContainer ── Room(LifeDatabase)
                          ├── LifeRepository
                          ├── MediaRepository
                          └── CaptureRepository
```

**依赖方向单向向下**：UI → Repository → DAO → Room。UI 永远不直接碰 DAO。

## 2. 包迁移

| 项 | 值 |
|---|---|
| namespace / 源码包 | `com.qq.closie` |
| applicationId | `com.xiaoming.closie`（**故意保持不变**） |
| rootProject.name | `qqhome` |

`applicationId` 是 Android 上应用数据的身份。改它会让系统把新装的应用当成另一个 App，
用户在旧包下积累的衣橱 JSON 与私有图片全部够不着——等同于静默清空用户数据。
因此只改编译期的 namespace，不改运行期的 applicationId。

## 3. 依赖注入

没有引入 Hilt / Koin。项目原本零 DI 框架，为三个仓储引入一套注解处理器会新增一层代码生成
和第二个"装配真源"。`LifeContainer` 是 Application 持有的 `by lazy` 单例，与既有
`LocalWardrobeRepository` 的持有方式一致。

```kotlin
// ClosieApplication.kt
val lifeContainer by lazy { LifeContainer.getInstance(this) }
```

## 4. 数据库

Room 2.6.1 + KSP `1.9.25-1.0.20`（与项目 Kotlin 1.9.25 对齐，未升级 Kotlin/AGP/Compose）。
`LifeDatabase` version = 1，8 张表，**禁止** `fallbackToDestructiveMigration()`，
schema 导出到 `app/schemas/`。详见 [LIFE_OS_DATA_MODEL.md](./LIFE_OS_DATA_MODEL.md)。

## 5. 与 Closie 的导航关系（关键约束）

只有一个 Shell 拥有底部栏，任何时刻都不会出现两个底部导航栏：

- Life OS 四个 Tab（首页 / 记录 / 生活 / 我的）→ Life OS 底部栏显示；
- 进入 `life_closet` / `life_closet_settings` → Life OS 底部栏**隐藏**，
  由 `ClosieNavHost` 自带底部栏接管。

`ClosieNavHost` 为此新增了两个参数：`startDestination`（从指定页进入）
与 `onExit`（作为起始页时返回键交还给 Life Shell，避免出现空白 NavHost）。

分享 / 深链接 intent 仍然可用：Life Shell 检测到 `externalCommand` 时先导航到衣橱，
再由 `ClosieNavHost` 执行真正的 Edit / Add 跳转。

## 6. 采集（Capture）

v0.1 只落**模型 + 仓储 + 入口 UI**，未迁移既有 `QuickCaptureService`。
状态机：`NEW → PROCESSING → NEEDS_REVIEW → CONFIRMED`，旁路 `FAILED` / `DISMISSED`。
`＋` 弹窗中"快速采集"启动现有 `QuickCaptureActivity`；"粘贴文本 / 链接"读剪贴板写入
`capture_items`；"手动记录"建一条空记录；"从相册"标记为 Coming soon 并禁用。

## 7. 未做的事（诚实清单）

- Capture Pipeline 解析器、媒体导入、缩略图生成：v0.2
- 同步：应用当前**完全没有** web/server 同步，「我的 → 同步」显示为禁用项
- 衣橱业务数据（ClothingItem）**未**迁入 Room，仍用原 JSON 存储
