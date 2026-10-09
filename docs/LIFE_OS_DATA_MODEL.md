# Current persistence capability boundary

单一 `LifeDatabase` v2（Room 2.6.1 / KSP），显式 migration 1→2；`app/schemas/1.json` / `2.json` 的实际 namespace 目录保持原样。schema fixtures 仅作为 debug merged assets，供 Robolectric MigrationTestHelper 使用；release APK 不打包这些文件。CI 使用 `testDebugUnitTest` 覆盖 migration。本轮不改 schema/version，不增加 prototype 兼容迁移，不使用 destructive fallback。

| 表 | 当前职责 |
|---|---|
| life_entities | 普通世界 typed owner 的 identity/revision/soft-delete metadata；没有业务 JSON |
| life_relations | 显式关系登记；不自动纳入 secure Vault |
| tags | 标签登记 |
| entity_tag_cross_ref | metadata ↔ 标签 |
| media_assets | 独立媒体 identity，不以 URI/path/hash 充当 identity |
| media_resources | 同一 identity 的资源定位/变体；最终原相册引用与 reconciliation 未开放 |
| media_links | 同一媒体的多个业务引用 |
| capture_items | intake 原始证据及处理状态，不拥有专业业务 truth |
| reference_items | unclassified source archive；raw extraction 与用户 note 分离，不等于全部 Knowledge |
| plan_items | 当前 typed 最小 Intent；soft planned date，不是 canonical Event 或完整 Plans 总模型 |

`reference_items.ocrText` 保存 raw extraction/source text；`summary` 只由用户编辑，自动 metadata 不写入。原始 share 始终保留在 Capture。系统 summary 后续有独立语义，不能复用用户 note 字段。

`plan_items.dueAt` 是保留的列名，当前只表示软计划日。未来多层 Goal/Project/Task、hard deadline、waiting/paused、recurrence 由 Plans typed owner 演进，不塞 metadata payload；本轮未假装实现这些能力。

相册默认复制原图的公开路径已撤下。实验 managed-copy helper 仍用于独立 backend 测试/恢复文件路径常量，没有 public gallery picker/container accessor。衣橱 JSON/ImageStore 是当前 legacy module 存储，不是未来跨领域 Media/Items/Shopping canonical owner。

Appearance preferences 在 DataStore；不进 Room。Closet JSON 的 repository、备份恢复与路径语义保留。本轮没有第二个 canonical database，也没有任何 Finance/Shopping/Items/Membership table。
