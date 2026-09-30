---
Title: Life OS Architecture Invariants 与工程红线
Version: 0.1
Status: DRAFT
Authority: Canonical Architecture Detail
Last Updated: 2026-09-30
Owner: Product Owner
Scope: Life OS
Parent Document: 00-architecture-overview.md
Recommended Path: docs/life-os/04-architecture/12-architecture-invariants.md
---

# 1. 目的

本文件集中列出 Architecture v0.1 的硬不变量。后续 PRD、Task、代码和 Review 都应以此为红线。

如果确需改变，应通过 ADR 明确记录原因、影响、迁移和恢复方案。

# 2. Canonical World

## I-01 Canonical Identity

同一现实事实只有一个 canonical identity。

## I-02 Single Domain Ownership

每类 Canonical Fact 只有一个语义 Owner。

## I-03 Stable Identity

名称、Provider、UI 文案变化不能改变 Canonical ID。

## I-04 Type ≠ Role

角色通过 Relation 表达，不复制 Entity。

## I-05 Intent ≠ Event

计划和实际发生永远分开。

## I-06 Unknown ≠ Observed None

无记录不等于明确未发生。

# 3. Data Layers

## I-07 Fact ≠ Evidence

Evidence、Extraction、Proposal、Truth 分层。

## I-08 Draft ≠ Proposal

用户未完成内容和系统候选分开。

## I-09 Derived Is Disposable

FTS、OCR、Embedding、Thumbnail、Summary 可重建。

## I-10 Derived Cannot Authorize Truth

搜索命中、Embedding 相似、AI 推断不能直接修改 Canonical。

## I-11 Proposal Revalidation

Proposal Commit 前重新检查当前世界与 Revision。

# 4. Writes / Recovery

## I-12 No AI Commit Capability

AI / Plugin 默认只能 read / propose。

## I-13 Atomic Canonical Commit

同一逻辑操作的 Canonical Changes 原子提交。

## I-14 ChangeSet Always

每次正式 Truth 修改都有 ChangeSet。

## I-15 Transactional Outbox

Truth Commit 与 Sync / Derived / Attention signal 同一 DB transaction 持久化。

## I-16 Idempotent Effects

所有异步 effect 可安全重放。

## I-17 No Blind Cascade Delete

数据库 cascade 不能代替 Domain 删除语义。

## I-18 Recovery Before Destruction

能 Undo 的操作优先 Undo；永久破坏才强确认。

# 5. Sync / Backup

## I-19 Sync ≠ Backup

同步负责传播，Backup 负责灾难恢复。

## I-20 Semantic Conflict Preservation

无法安全判断的冲突不静默覆盖。

## I-21 Unknown Preservation

旧客户端不理解的数据必须保留。

## I-22 Patch, Don't Replace

旧客户端只能修改理解的字段，不能 whole-object replace 抹掉未知字段。

## I-23 Restore Creates New Epoch

灾难恢复后必须建立新的同步恢复基线。

## I-24 Backup Must Be Consistent and Verified

不能 naive copy live DB 作为可靠恢复点。

# 6. AI / Security

## I-25 Minimum Necessary Context

AI 每次只读取当前任务必要范围。

## I-26 Content ≠ Instruction

网页、PDF、OCR、外部内容不能提升权限。

## I-27 No Arbitrary SQL for AI / Plugin

所有外部主体走业务 API。

## I-28 Capability Over Trust

权限由 capability 决定，不由“这个 Agent 很可信”决定。

## I-29 Vault Non-observability

普通 Life OS / AI 不能观察 Creative Vault 的 object ID、metadata、relation 或 existence。

## I-30 Plugin Non-criticality

Core Fact 的有效性不能依赖第三方 Plugin 存在。

# 7. Attention / Work

## I-31 Action ≠ Attention

允许发生不代表允许打扰用户。

## I-32 Module Cannot Push Directly

业务模块只能产生 Attention Candidate。

## I-33 Background Work Is Resource-budgeted

Worker 不自行无限抢占 CPU / memory / network。

## I-34 Offline Is a State

本地写入成功后，离线不算业务失败。

# 8. Media

## I-35 Media Identity ≠ Locator

URI、path、hash 都不能直接成为 Media canonical identity。

## I-36 One Original, Many References

模块共享 MediaAsset，不复制 original。

## I-37 Source Missing ≠ History Deleted

原相册引用失效时保留 Life OS identity、relations 和恢复路径。

## I-38 Original ≠ Derived

Original 不因清 cache 被删除。

# 9. UI / Composition

## I-39 Projection Surface Does Not Own Truth

Home、Calendar、Search、Map 不拥有第二份 canonical world。

## I-40 Live vs Snapshot Reference

动态视图可以跟随 Truth；手工 Composition 不得被后台事实更新偷偷重排。

## I-41 UI Refactor Should Rarely Migrate Truth

界面重构原则上不要求搬迁 Canonical Fact。

# 10. Long-term Evolution

## I-42 No Destructive Migration

Canonical migration 失败不得清空重建。

## I-43 Schema Identity Never Recycled

退休的字段 / type identity 不用于完全不同的新语义。

## I-44 Plugin Data Survives Uninstall

插件卸载不等于用户扩展数据删除。

## I-45 Archive Is Compatibility-first

Canonical Archive Format 比内部 App Schema 更保守。

# 11. Product Trust

## I-46 Safety Before Progress

出错时首先回答：

> 数据有没有丢？

然后再解释技术状态和修复。

## I-47 User Is Not System Administrator

系统维护、索引重建、备份验证、缓存管理默认由系统承担，不把维护压力转嫁给用户。

## I-48 No Lock-in by Design

用户应能导出人类可读档案与机器完整档案。

# 12. Review Checklist

任何新增功能 / PR 都应至少检查：

1. 是否复制了已有 Canonical Fact？
2. Owner Domain 是谁？
3. AI / Plugin 是否获得了过宽能力？
4. 是否绕过 Proposal？
5. 是否有 ChangeSet？
6. 异步 effect 是否幂等？
7. Derived failure 会不会破坏 Truth？
8. 旧客户端会不会丢未知字段？
9. 删除是否会误 cascade？
10. Vault 是否被 metadata / relation 泄漏？
11. Sync conflict 是否被静默覆盖？
12. 是否把 UI DTO 持久化成第二份 Truth？
13. 是否让用户承担不必要的系统维护？
