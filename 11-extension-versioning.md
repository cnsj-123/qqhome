---
Title: Life OS Schema Evolution、扩展与版本兼容
Version: 0.1
Status: DRAFT
Authority: Canonical Architecture Detail
Last Updated: 2026-09-30
Owner: Product Owner
Scope: Life OS
Parent Document: 00-architecture-overview.md
Recommended Path: docs/life-os/04-architecture/11-extension-versioning.md
---

# 1. 核心目标

Life OS 必须允许十年后继续增加模块、字段和关系，而不破坏旧人生数据。

# 2. Canonical Data 禁止 destructive fallback

Migration 失败：

> 宁可停止打开，也不能清空重建 Canonical DB。

Derived Index 可以删掉重建；Truth 不行。

# 3. Schema History

仓库保留历史 schema / migration fixtures，而不是只留最新版。

长期未更新设备可能跨多个版本升级。

# 4. Semantic Migration

Migration 不只验证“列存在”。

还要验证旧业务含义正确进入新模型。

例如旧 `price` 拆成 `listed_price` / `actual_paid` 时，需要明确语义映射。

# 5. Expand → Migrate → Contract

破坏式变化采用：

```text
Expand
→ coexist old/new
→ migrate
→ validate
→ switch reads/writes
→ deprecate old
→ later contract
```

避免“一版 App 把整个世界瞬间重写”。

# 6. Lazy Migration

能用默认值解释旧记录时，不要为一个新字段扫描十年数据库。

例如 `NULL = default NORMAL`，真正修改对象时再 materialize。

# 7. Version Skew

Phone 新版、Tablet 旧版同时存在是正常状态。

旧客户端必须：

- preserve unknown fields
- preserve unknown extension
- patch known fields only
- not whole-object replace

# 8. Stable Property Identity

UI label 可以改，底层 Property ID 不变。

已退休的 property/type identity 不重新用于新语义。

# 9. 多层版本

领域层建议区别：

```text
Core Schema
Media Schema
Finance Module
Closet Module
Knowledge Module
Archive Format
Plugin Schema
```

一个模块变化不等于整个世界 major break。

# 10. Compatible vs Breaking

Compatible：

- 新 optional field
- 新 relation type
- 新 extension
- 新 event subtype，旧客户端可 opaque-preserve

Breaking：

- 一个旧概念拆成多个新概念
- old semantics 无法被旧客户端安全理解
- identity / relation meaning 改变

Breaking change 需要 Migration Plan + Recovery Plan + ADR。

# 11. Migration UX

大迁移必须显示：

- 已创建安全恢复点
- 当前阶段
- 是否可暂停
- 数据是否安全
- 验证结果

不要只转圈“正在升级数据库”。

# 12. Checkpoint / Resume

大迁移必须 chunked、checkpointed、resumable。

App 被杀后从 checkpoint 继续，不从头扫。

# 13. Module Extension

First-party Domain Extension 附着 Core Identity，不复制 Entity。

新模块未来可以安全增加自己的 typed extension。

# 14. Plugin Extension

Plugin 使用 namespace + versioned payload。

卸载：

> data preserved。

Plugin 自己负责 extension schema migration；失败时保留原 payload。

# 15. Unknown Preservation

旧客户端看不懂新数据：

> opaque preserve。

Unknown 不等于 invalid。

# 16. Core API Stability

Plugin 不依赖物理表名。

依赖稳定 capability APIs：

```text
Person.read
Event.query
Relation.propose
Extension.writeOwn
```

# 17. Archive Format

App DB Schema 可以较快演化。

Canonical Archive Format 应兼容优先、慢速演化。

```text
Internal DB
→ adapter
→ Stable Archive Format
```

# 18. Upgrade Fixtures

CI 后续应保留：

```text
v1 database
v5 database
v12 database
vCurrent-1 database
```

测试所有支持旧版本 → Current。

验证：

- IDs 不变
- relations 不丢
- media refs 不丢
- unknown extension 不丢
- export 完整
- upgrade 后 sync 正常

# 19. Version-skew Integration Test

专门测试：

```text
Phone Core v8
Tablet Core v7
Plugin v3
Server protocol v6
```

不同设备修改同对象不同字段后，新字段不能被旧客户端覆盖。

# 20. Invariants

- No Destructive Migration for Canonical Data
- Schema Identity Never Recycled
- Expand/Migrate/Contract
- Unknown Data Preserved
- Old Client Uses Patch Semantics
- Migration Has Recovery Point
- Migration Resumable
- Plugin Data Survives Uninstall
- Archive Evolves Slower Than App DB
