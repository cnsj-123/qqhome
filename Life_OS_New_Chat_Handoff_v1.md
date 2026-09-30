# Life OS 新对话交接

请把以下内容视为当前项目事实，不要从零重新设计。

## 项目

GitHub：
`cnsj-123/qqhome`

Android：
- namespace: `com.qq.closie`
- applicationId: `com.qq.closie`

applicationId 已冻结，禁止修改。

## 当前分支

`life-os-v0.3`

当前真实 HEAD：

`d7c3c859d900f749625b72be0ce9fb62fec0e1d6`

## 当前 CI

GitHub Actions Run #22：

**SUCCESS**

已通过：
- unit tests
- assembleDebug

APK artifact：
`LifeOS-apk-build22`

## 当前阶段

**v0.3 真机验收。**

现在不要继续新增 Finance / Items / Travel / Garden。

先真机验证：
- Life OS shell
- Closet
- OOTD
- Reference
- Plan
- Reading
- Share
- Gallery
- Backup
- Restore
- restart

## 产品核心

Life OS 是长期个人生活 OS。

> Record once, use everywhere.

原则：
- local-first
- typed domain models
- stable IDs
- one media identity, many refs
- backup / restore / migration
- restrained UI
- no duplicate source of truth

## 架构

Composable
→ state holder
→ Repository
→ DAO / Storage

禁止 Composable → DAO。

Closie 保持成熟独立模块。

## Wardrobe

`WardrobeRepository`
→ `LocalWardrobeRepository`

canonical 多 surface state：

`StateFlow<WardrobeSnapshot>`

包含：
- items
- wearEvents
- washEvents
- ootds
- outfits

需要多 surface 的 UI 一次 collect snapshot。

## Restore ownership

唯一 owner：
- RestoreIntent
- RestoreCoordinator
- RestoreRecoveryManager
- RestoreStartupGate
- RestoreFs

不要新建平行 Restore engine/service。

## Restore v2

PREPARING
→ STAGED
→ CLOSET_SWAPPED
→ MEDIA_SWAPPED
→ DB_COMMITTING
→ DB_COMMITTED
→ HEALTH_CHECKING
→ COMMITTED

关键 invariant：
- COMMITTED 不 rollback
- marker clear LAST
- evidence validation before mutation
- DB-first compensation
- startup synchronous barrier

## Gate

状态：
- READY
- RESTORING
- BLOCKED

单一 StateFlow State：
- status
- error
- stickyUntilRestart
- activeBusinessOps

beginRestore：
READY + non-sticky + activeBusinessOps==0 才成功。

## Flow

正确：

status-only
→ flatMapLatest durable subscription
→ OUTER emission lease
→ downstream

不要把 emission lease 放在 flatMapLatest inner Flow。

## Navigation

保持：

Life OS
→ `LifeDestination.Closet`
→ `ClosieNavHost`

不要 flatten Closie destinations。

ClosieNavHost 继续处理：
- internal navigation
- EmbeddedInLifeOs return
- ProductImport/Edit/Add
- Wardrobe snapshot provider

## Backup

完整 v0.3：
- wardrobe snapshot
- drafts
- Closet images
- typed Life payload
- Life media

export business lease 只保证不和 restore 交错，不是普通业务写 mutex。

## Git / AI

代码 AI GitHub 只读。

禁止：
- push
- branch write
- PR write
- merge
- comment
- release/tag write

用户在 VPS 手动 push。

AI 必须真实报告：
- base SHA
- new SHA
- `git diff --check`
- `testDebugUnitTest`
- `assembleDebug`
- patch path

不得：
- @Ignore / @Disabled 隐藏失败
- 为测试绿破坏 production

## 当前签名

CI 仍缺：
- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEYSTORE_ALIAS
- ANDROID_KEY_PASSWORD

当前 APK 使用 debug fallback signing。

可以真机功能测试，但不同签名旧 APK 可能不能覆盖安装。

## 新窗口现在应该做什么

如果用户发：
- 真机截图
- 闪退
- 日志
- 页面异常

优先基于真机证据排查。

不要重新讨论已经通过 CI 的 Restore 主协议，除非真机证据明确指向它。

如果开始新功能：

Module Spec
→ Architecture Review
→ Task 拆分
→ 实现
→ Review
→ CI
→ 真机

## 回复风格

用户希望：
- 中文
- 直接结论
- 解释简洁
- 指令可复制
- 代码和架构都严格审
- 一次尽量多审，不要一个错误一轮
