# Life OS Architecture v0.1 文档索引

> Status: DRAFT  
> Last Updated: 2026-09-30

本目录是 Life OS 当前统一架构基线。所有文档均为中文 Markdown。

| 文件 | 内容 |
|---|---|
| `00-统一架构总览.md` | 总体架构、核心分层、Control Plane、Intelligence Plane |
| `01-世界模型与核心数据语义.md` | Entity / Event / Intent / Relation / Evidence / Artifact / Composition |
| `02-数据分层与事实生命周期.md` | Truth / Proposal / Draft / Evidence / Extraction / Derived / Recovery |
| `03-命令流与查询流.md` | Command、Query、ChangeSet、Transactional Outbox、Idempotency |
| `04-领域归属与跨模块共享.md` | Domain Ownership Registry 与跨模块共享 |
| `05-控制平面.md` | Capability、Action Gate、Attention Gate、Work Scheduler、System Health |
| `06-AI与Ombre边界.md` | Companion、Ombre Brain、Context Gateway、Tool/Skill/Agent 边界 |
| `07-存储模型与API边界.md` | SQLite/Room、typed tables、Extension、API Boundary |
| `08-同步备份与恢复.md` | Local-first、Sync、Conflict、Backup、Restore Epoch |
| `09-媒体架构.md` | MediaAsset、Original/Metadata/Derived、动态照片、缓存 |
| `10-安全与隐私.md` | Threat Model、Encryption、Vault、Plugin/AI 权限与隐私 |
| `11-扩展与版本演进.md` | Schema Evolution、Migration、Version Skew、Plugin Extension |
| `12-架构不变量与工程红线.md` | Architecture v0.1 硬不变量与 Code Review 红线 |
| `13-统一术语表.md` | 统一术语映射（Canonical Truth / Proposal / Draft / Inbox / Evidence / Derived…）与必须突出的不等式 |
| `14-领域归属注册表.md` | 完整 Domain Ownership Registry（Business Domains / Surfaces / Infrastructure 三类）与 Domain Contract 模板 |

## 推荐阅读顺序

`00 → 01 → 02 → 13 → 14 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10 → 11 → 12`

原因：先理解世界、层次和术语，再读具体执行架构。

## 核心架构句

> **一个真实的个人世界，多种专业视图；AI 是有权限边界的伙伴，而不是数据库的主人。**

## 状态说明

这些文件是 Architecture v0.1 的 DRAFT 基线，用于后续：
- PRD 收束；
- 数据模型设计；
- UI/UX 设计；
- Codex Task；
- Architecture Review；
- Pull Request Review。

在 Product Owner 明确确认前，不应把 `DRAFT` 自动改成 `CONFIRMED`。
