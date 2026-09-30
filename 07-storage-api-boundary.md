---
Title: Life OS Storage Model 与 API Boundary
Version: 0.1
Status: DRAFT
Authority: Canonical Architecture Detail
Last Updated: 2026-09-30
Owner: Product Owner
Scope: Life OS
Parent Document: 00-architecture-overview.md
Recommended Path: docs/life-os/04-architecture/07-storage-api-boundary.md
---

# 1. 物理存储方向

初期建议：

```text
lifeos.db
media/
creative-vault/
```

`lifeos.db` 保存 Canonical World、Proposal、Recovery metadata、Sync metadata、First-party extension 和部分 lightweight Derived。

Creative Vault 单独数据库、媒体空间和密钥边界。

# 2. Derived 暂不必物理分库

架构上先逻辑分离。以后 embedding / index 体积或 rebuild lifecycle 需要时可拆 `derived.db`。

# 3. Identity Spine + Typed Tables

不推荐万能：

```text
objects(id, type, json)
```

建议薄 Identity Spine：

```text
world_object
- object_id
- object_kind
- domain_key
- created_at
- recorded_at
- revision
- schema_version
- deleted_at
```

再由强类型表保存业务字段：

```text
person
place
item
event
intent
...
```

# 4. Event Base + Domain Extension

基础 Event 保存：

- event_id
- event_type
- domain_key
- start / end
- parent / part_of
- recorded_at
- revision

专业字段进入：

```text
concert_extension
meal_extension
medication_intake_extension
...
```

# 5. Relation：Hybrid

需要 generic relation table 支持跨 Domain typed relation，但不是所有 FK 都塞成图边。

高频、强 invariant 业务关系：

> 普通 FK / junction。

跨 Domain、语义化关系：

> generic Relation。

# 6. First-party Extension

第一方 Domain 使用强类型表，方便：

- validation
- migration
- index
- compile-time model
- query optimization

# 7. Plugin Extension

第三方只能写 versioned opaque envelope：

```text
extension_record
- namespace
- owner_object_id
- extension_type
- schema_version
- payload
```

Plugin 不能 ALTER Core Schema。

# 8. Evidence / Extraction 分表

Evidence 保存来源 identity。

Extraction 保存 extractor/model version、OCR text、structured payload、confidence 等 Derived 结果。

# 9. Proposal Storage

Proposal 应是结构化持久对象，而不是 AI 文本：

- proposal_id
- type
- domain
- status
- source actor
- reason
- created_at
- snoozed_until
- expires_at
- targets
- operations
- evidence

# 10. ChangeSet Storage

建议概念：

```text
change_set
- id
- actor
- authority
- command_type
- reason
- device_id
- causation_id
- correlation_id
- created_at

change_entry
- change_set_id
- object_id
- operation
- before_revision
- after_revision
- recovery payload / reference
```

具体 recovery payload 形式后续决定。

# 11. Media Storage

媒体 metadata 在 DB，binary 在受管理文件存储。

```text
media_asset
media_locator
```

URI / path / hash 都不是 Canonical Media ID。

# 12. API Layer

```text
SQLite / Files
    ↓
DAO
    ↓
Repository
    ↓
Domain Service
    ↓
Command / Query API
    ↓
Capability API
    ↓
UI / AI / Plugin / Import
```

# 13. DAO Boundary

只有 Storage / Domain implementation 能看 DAO。

Screen、Companion、Plugin、MCP、Importer 不直接碰 DAO。

# 14. Query API

UI 使用专业 Query，例如：

```text
ClosetQueries.itemDetail(id)
TravelQueries.tripView(id)
HomeQueries.todayOverview()
```

而不是自己 join 多个 DAO。

# 15. Command API

写入使用领域命令：

```text
ClosetCommands.recordWear(...)
GardenCommands.recordCare(...)
HealthCommands.recordMedicationIntake(...)
CommerceCommands.confirmPurchase(...)
```

# 16. AI API 更窄

AI 只得到 capability-scoped API，不得到 UI 的全部内部能力。

# 17. Sync Payload 不等于 DB Row

Sync 传输 Canonical State / Change / extension envelope / revision 等语义对象，不复制 SQLite 文件。

# 18. Settings Scope

配置需标记：

```text
ACCOUNT
DEVICE
MODULE
VAULT
SESSION
```

避免手机设置覆盖平板。

# 19. 技术上暂不锁定

- UUIDv7 vs ULID
- JSON vs CBOR vs Protobuf for extension/wire
- ChangeSet snapshot vs patch
- Derived 物理分库
- Sync merge algorithm

当前只固定 API 和语义边界。

# 20. Invariants

- Physical Same DB ≠ Same Permission Domain
- DAO Is Internal
- Core Facts Strongly Typed
- Plugin Data Namespaced
- Unknown Extension Preserved
- Sync Is Semantic Replication, Not File Copy
- Media ID Independent From Locator
