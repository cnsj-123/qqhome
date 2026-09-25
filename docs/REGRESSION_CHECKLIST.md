# 回归清单（v0.1 起 · v0.3.0 更新）

> A–G、I 节的**真机**项目**尚未在真机执行**（构建环境缺 Android SDK，见最终报告"构建真实状态"）。
> H 节为自动化测试，拿到可构建环境后跑 `./gradlew :app:testDebugUnitTest` 即可验证。
> 拿到可构建环境后，请按此清单逐条走一遍再发布。

## A. 安装与启动

- [ ] 从旧版本（applicationId `com.xiaoming.closie`）覆盖安装，衣橱数据**仍在**
      ——这一条验证 applicationId 未变带来的数据连续性
- [ ] 冷启动进入 Life OS 首页，不白屏、不崩
- [ ] 首页显示正确日期与星期

## B. 底部导航

- [ ] 首页 / 记录 / ＋ / 生活 / 我的 五个位置可点，选中态正确
- [ ] `＋` **不进入选中态**，只弹采集弹窗
- [ ] 生活 → 衣橱：进入 Closie 衣橱
- [ ] **进入衣橱后只有一个底部导航栏**（Closie 的），Life OS 底部栏消失
- [ ] 从衣橱返回 → 回到「生活」页，Life OS 底部栏回来
- [ ] 我的 → 设置 / 备份与恢复：进入 Closie 设置页，同样只有一个底部栏
- [ ] 从设置返回 → 回到「我的」页

## C. 采集弹窗

- [ ] `＋` 打开弹窗，五个入口可见
- [ ] 快速采集 → 弹出 MediaProjection 授权页
- [ ] 从相册 → 打开系统照片选择器（无权限弹窗），选一张图后**落到该图的资料库详情页**
- [ ] 从相册 → 该图在「记录」页有一条记录，在「资料库」有一条条目，缩略图可见
- [ ] 粘贴文本 → 剪贴板有内容时写入一条记录，空剪贴板时不应写入空记录
- [ ] 链接 → 剪贴板是 http(s) 链接时写入并带 `sourceUrl`；链接埋在中文字符串里也能提取
- [ ] 手动记录 → 打开空白编辑器，**不**立即写入任何行；直接返回后「记录」页条目数不变
- [ ] 手动记录 → 填标题/备注后保存 → 才在「记录」页出现一条
- [ ] 同一张截图的「存进资料库」连点两次 → 资料库只多出一条（幂等）
- [ ] 记录写入后在「记录」页与首页「最近记录」可见
- [ ] 记录详情：图片记录显示真实缩略图（不是「（图片）」占位块）
- [ ] 记录详情 → 标签为纯中文的备注用思源黑体渲染（不是手写体/系统字体）
- [ ] 资料库列表：有图的条目显示真实缩略图；无图条目不留空白方块
- [ ] 资料库右上角 `+` 可打开同一个采集弹窗
- [ ] 阅读页不受资料库的搜索/筛选影响（在资料库搜索后切到阅读，书本仍在）
- [ ] 计划页：日期已过且未完成的条目出现在「此前」分组，且**不显示红色/角标**
- [ ] 桌面图标在系统「主题图标」下显示为完整实心剪影（不是只有高光的残影）

## D. 既有 Closie 功能（防回归）

- [ ] 衣橱列表、详情、编辑、新增正常
- [ ] 商品链接导入（粘贴链接 → 解析标题/价格/图片）正常
- [ ] 搜索 / 筛选 / 排序正常
- [ ] OOTD 日历保存与穿着记录联动正常
- [ ] 搭配室（Outfit Studio）画布拖拽 / 缩放 / 层级正常
- [ ] 备份导出 ZIP 与恢复正常；导出 CSV 正常
- [ ] 备份 v2 导入后：衣橱 + 记录 + 资料库 + 计划 + 图片全部回来
- [ ] 恢复一份 v1 旧备份：衣橱回来，Life OS 数据**不被清空**

## E. 分享入口（防回归）

- [ ] 外部「分享到本应用」分享**淘宝/天猫/京东商品链接** → 进入衣橱新增编辑页（商品导入仍然生效）
- [ ] 外部「分享到本应用」分享**普通网页链接** → 进入资料库，标题为该网页标题
- [ ] 外部「分享到本应用」分享**纯文本** → 进入记录
- [ ] 分享文本中链接被埋在中文字符串里（`【淘宝】… https://m.tb.cn/xx 打开淘宝`）→ 仍能识别为链接
- [ ] 快速采集浮球「保存并继续编辑」→ 进入编辑页

## F. 数据层（自动化测试，`./gradlew test`）

- [ ] `LifeRepositoryTest` 全绿（含 revision 递增、软删除/恢复、关系去重、标签规范化唯一）
- [ ] `MediaRepositoryTest` 全绿（含一资产多资源、sha256 查找、无效引用拒绝）
- [ ] `CaptureRepositoryTest` 全绿（含状态流转、失败与 errorMessage、nullable 媒体）

## G. 构建

- [ ] `./gradlew :app:assembleDebug` 成功
- [ ] `./gradlew :app:testDebugUnitTest` 成功
- [ ] `app/schemas/<pkg>/1.json` 已生成并提交
- [ ] `grep -RIn "com\.xiaoming" app/src` 除 `applicationId` 外无残留

## H. 恢复原子性（自动化测试，v0.3.0）

> 这一节不再依赖真机：崩溃点由 `RestoreHooks` 注入，覆盖的是「两次落盘之间的窗口」——
> 真机上窗口只有几十微秒，靠手点永远复现不出来。生产代码传 `NoOpRestoreHooks`，
> 所以被测的就是线上跑的那条路径本身。

- [ ] `RestoreCoordinatorTest` 全绿，重点：
      - [ ] `dbCommitThenFilesystemFailure_leavesAllThreeSurfacesOnTheOldVersion`
            ——库已提交、目录还没换完时崩，库 + 衣橱 + 媒体**同时回到旧版本**
      - [ ] `healthCheckFailureAfterSwap_revertsClosetAndDatabaseTogether`
            ——换完目录才发现媒体不全，衣橱与数据库一起回滚
      - [ ] `missingMediaFile_failsBeforeAnyDurableMutation`
            ——v2 备份缺媒体文件：在任何持久化改动**之前**失败，现场一个临时目录都不留
      - [ ] `startupRecovery_whenMarkerLagsBehindTheSwap_stillReverts`
            ——标记落后文件系统一步时，仍按「旧目录是否存在」回滚，不删用户数据
      - [ ] `startupRecovery_fromDbCommitting_revertsBothHalvesAndIsIdempotent`
            ——DB_COMMITTING 崩：两个半都回滚，且重复调用结果一致
      - [ ] `startupRecovery_fromDbCommitting_withoutDatabase_repairsClosetAndKeepsMarker`
            ——没有数据库时只修文件系统，**标记必须留下**给下一次启动
      - [ ] `recoveryWithoutDatabase_revertsClosetButKeepsTheMarkerForALaterStart`
      - [ ] `secondStartWithDatabase_finishesTheRestoreAndClearsTheMarker`
      - [ ] `thirdCallAfterCompletedRecovery_isAGenuineNoOp`
      - [ ] `filesystemPassThenDatabasePassInOneStartup_neverLosesTheCloset`
      - [ ] `markerPathSquatter_doesNotDefeatTheMarkerWrite`
      - [ ] `markerWriteFailure_abortsBeforeAnyUndecidableStateIsReached`
            ——标记写不进去就不许进入无法判定的状态
      - [ ] `filesystemRecoveryGuard_runsThePassAtMostOnce`
      - [ ] `filesystemRecoveryGuard_doesNotLatchWhenThePassThrows`
            ——**失败不允许把「本进程已恢复」的闩锁上**（这条是 P0-3 的回归锁）
      - [ ] `filesystemRecoveryGuard_doesNotPreventTheDatabasePassFromRunning`
      - [ ] `requiresDatabaseRecovery_isExactlyTheThreeStatesThatFollowTheTransaction`
      - [ ] `requiresClosetRevert_coversTheSwapOnwardsAndNothingBefore`
- [ ] `RestoreRecoveryManagerTest` 全绿（应用启动入口的两段式恢复）
- [ ] `BackupManagerTest` 全绿（导出/导入往返、v1 兼容）

## I. 恢复原子性（真机手工，拿到可构建环境后补）

- [ ] 恢复一份 v2 备份，中途 `adb shell kill <pid>` → 重启后衣橱、资料库、媒体**要么全新要么全旧**
- [ ] 恢复过程中断电（长按电源）→ 同上，且 `filesDir` 下**不残留**
      `.closie_restore_*` / `.life_media_restore_*` / `.life_restore_dbsnap_*.json`
- [ ] 恢复一份 v1 备份：衣橱回来，`life_os.db` 与 `filesDir/media` **时间戳与内容都不变**
- [ ] 恢复失败后：弹错误，重启应用不再重复回滚，且设置页可以直接再试一次
