# Life OS 路线图

## v0.1（本次）— 地基

已完成：

- [x] `com.xiaoming.closie` → `com.qq.closie` 包迁移（applicationId 保持不变）
- [x] Room 2.6.1 + KSP 接入，KSP 配置在 project-level
- [x] `LifeDatabase` v1：8 张表、枚举 converter、索引与唯一约束、schema 导出
- [x] 三个 Repository（Life / Media / Capture）+ `LifeContainer` 接入 Application
- [x] Life OS App Shell：首页 / 记录 / ＋ / 生活 / 我的
- [x] 生活 → 衣橱 复用既有 Closie，且保证不出现双底部栏
- [x] 采集弹窗与 Capture 状态机（模型 + 仓储）
- [x] Room / Repository 测试

明确不做：业务功能、数据搬迁、UI 打磨。

## v0.2 — Capture Pipeline

- [ ] 统一采集管道：`CapturePayload` → `CaptureItem` → 解析 → 候选字段 → 用户确认 → 类型化实体
- [ ] 从相册导入（当前弹窗中禁用）
- [ ] 媒体资源落盘与缩略图生成（`media_resources` 的 ORIGINAL / THUMBNAIL）
- [ ] 迁移既有 `QuickCaptureService` 到新的 Capture 仓储
- [ ] 记录页时间线：按日期分组、媒体缩略图、搜索

## v0.3 — 业务模块

- [ ] 饮食 / 健康 / 阅读 / 出行 中至少一个模块从 Coming soon 变为可用
- [ ] 标签体系接入 UI（`tags` + `entity_tag_cross_ref` 已有）
- [ ] 关系图可视化（`life_relations` 已有）

## v0.4 — 同步与迁移

- [x] 端到端备份包含 Life OS 数据（v0.3.0 已实现：backup format v2 以强类型 payload 携带
  `life/data.json` 与 `life/media/…`，在**单个事务内**写入运行中的数据库；v1 旧备份仍可恢复且
  不会清空 Life OS 数据）
- [x] **崩溃一致的恢复**（v0.3.0 最终版）：恢复提交边界横跨两个目录树（`closie/`、`media/`）与
  一个 Room 数据库，这三者无法共享一个事务，因此用"暂存目录交换"而非"原地改"来保证崩溃一致性——
  - 每个发布都是 `live → old → stage → live`，且标记在**每次重命名之前**就写好（带 parked-old-dir
    路径与 `existedBefore` 标志），所以任何一步之间的进程被杀都可恢复；
  - 衣橱与 Life 媒体各自独立交换（`CLOSET_SWAPPED` → `MEDIA_SWAPPED`），数据库事务最后提交
    （`DB_COMMITTING` → `DB_COMMITTED`），二者之间用一份预写快照做幂等回放；
  - 标记与快照通过 `android.util.AtomicFile` 原子落盘，损坏的标记 ≠ 没有标记，恢复选择重试而非
    静默清除；
  - 回滚规则（P0-2）：只有在**每一个**持久半步都真正回退后，才把标记写成 `ROLLED_BACK` 并清除；
    任何一步回退失败都保留标记与证据，留给下次启动继续，绝不"看起来干净"地丢掉用户数据。
  - 详见 `docs/REGRESSION_CHECKLIST.md` 的恢复原子性回归小节与 `app/src/test/.../backup/` 下的
    `RestoreCoordinatorTest` / `BackupManagerTest` / `RestoreRecoveryManagerTest`。
- [ ] 同步能力（当前完全没有；在此之前「我的 → 同步」保持禁用）
- [ ] 把 `ClothingItem` 从 JSON 迁入 Room（需要完整数据迁移测试）
- [x] `LifeDatabase` v2 的第一个显式 `Migration` 示例（`LifeMigrations.MIGRATION_1_2`）

### 明确推迟到 v0.4：接收 `ACTION_SEND` 的 `image/*`

当前 `AndroidManifest.xml` 只注册了 `text/plain`：

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
```

从别的 App「分享图片到 Life OS」因此不会出现在系统分享面板里。这是**有意推迟**，不是遗漏：

- 接收 `image/*` 需要一条与相册选取**完全不同**的输入路径。`PickVisualMedia` 返回一个由用户
  明确挑选、且授予了读取权限的 URI；而 `ACTION_SEND` 送来的是另一个进程直接塞进 Intent 的
  `EXTRA_STREAM`，没有 picker 的授权语义，需要自己处理 `content://` 读取、多图、以及 provider
  权限过期。把两者混在一处，最容易出的错是"有时能读有时读不到"，且只在特定 App 分享时复现。
- 图片一旦读入，后续流程（落盘 → 记录 → OCR → 资料库）与本版已实现的
  `ReferenceImporter.importGalleryImage` 完全一致，因此增量工作只在"取字节"这一步。
- v0.3.0 已经有两条可用的图片入口：**从相册**（Photo Picker，无权限）与**手动记录**。
  系统分享图片属于锦上添花，在 v0.3.0 的时间盒内优先级低于上面那些真实功能缺口。

所以本版把 `image/*` 留给 v0.4，并在改动时补上对应的 `intent-filter` + 读取路径 + 回归用例。

### 明确推迟到 v0.4：小红书 / 抖音的"商品卡片"识别

`ShareRouter` 现在只把**强电商域名**（淘宝 / 天猫 / 京东 / 拼多多 / 得物 / Amazon / Farfetch …）
路由到 Closie 商品导入。小红书与抖音是**内容平台**，不是商店：用户从它们保存的内容里，
教程、攻略、生活信息、园艺、旅行的数量远大于商品。按"平台 = 商品"判断的后果是把一篇园艺教程
变成一条等着填价格和尺码的"商品"，而专门为这类材料存在的资料库一条都收不到。

所以这两个平台默认进入**资料库**，这是代价较小的错误方向——被误存的商品仍然是一个用户可以
打开的链接，而被误存的教程则是一条没有意义的衣橱记录。

将来若要做，正确做法是识别**明确的商品分享格式**（商品卡片结构 / 平台商品域），而不是平台本身。
命中才路由到商品导入，并补上对应回归用例。

## 纪律（长期）

- schema 变更必须有显式 Migration，禁止 destructive fallback
- `life_entities` 永不装万能 JSON
- 禁用态永远用 `enabled = false`，不用空 lambda
- 任何声称"已完成"的能力必须在真机上被回归清单覆盖
