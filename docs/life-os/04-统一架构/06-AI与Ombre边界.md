---
Title: Life OS 与 Ombre Brain / Companion AI 边界
Version: 0.1
Status: DRAFT
Authority: Canonical Architecture Detail
Last Updated: 2026-09-30
Owner: Product Owner
Scope: Life OS
Parent Document: 00-统一架构总览.md
Recommended Path: docs/life-os/04-统一架构/06-AI与Ombre边界.md
---

# 1. 系统职责

```text
Life OS:
Canonical personal facts

Ombre Brain:
Long-term AI memory / relationship memory / recall

Context Gateway:
Per-request context assembly

Conversation Archive:
Full chat history

Companion Runtime:
User-facing conversational experience
```

这五套数据必须分开：

```text
Life OS Canonical World
Ombre Memory
Conversation Archive
Model Context
Creative Vault
```

它们**彼此不是同一个数据库**，不能合并成一个“AI Database”。

# 2. Context Assembly

每轮模型 Context：

```text
Recent Conversation
+ Relevant Ombre Memory
+ Authorized Life OS Facts
+ Current Task Context
```

不是完整聊天，也不是整个 Life OS。

# 3. Full Conversation Archive ≠ Model Context

完整聊天可以长期保存和搜索。

Gateway 只发送当前必要部分。

原则：

> **保存得多，不等于每次加载得多。**

# 3.1 Chat Shared Card

聊天里分享的 Life OS 对象需要区分「历史快照」与「当前对象」。

建议语义：

```text
SharedRecordAttachment
├── display_snapshot
└── canonical_source_ref
```

原因：

- 聊天历史应保持“当时发送了什么”的**历史真实性**；
- 但用户仍可以跳转到**当前** Canonical Object。

因此：

- `display_snapshot` 不会因未来 Truth 修改而偷偷改写 → **Chat Historical Snapshot**。
- `canonical_source_ref` 可以跳转当前对象。

# 4. AI Read

AI 通过 capability-scoped read API 访问 Life OS。

禁止：

```text
LifeOS.query(sql)
SELECT * FROM ...
```

AI Read API 应面向业务：

```text
searchClosetItems(...)
getRecentHobbySessions(...)
getTripSummary(...)
```

# 5. AI Write

AI 只有：

```text
proposal.create
```

没有：

```text
truth.commit
```

用户确认后由系统以 `USER_CONFIRMED_PROPOSAL` authority 执行 Domain Command。

# 6. Authorized Quiet Reads

非敏感且已授权的读取可以静默完成。

敏感数据（Health、Finance 等）可配置 Ask / Allow / Deny。

读取权限和写入权限分离。

# 7. Tool / Skill / Agent / Worker

- Tool：具体动作；
- Skill：流程；
- Agent：多步执行主体；
- Worker：OCR / Embedding / Media 等后台执行器。

Skill/Agent 不因为工作流“需要”某数据就获得额外权限。

# 8. Deferred Tool Discovery

模型不应每轮注入所有工具 Schema。

建议：

```text
Capability Router
→ Domain Discovery
→ Concrete Tool
```

减少 Context、降低攻击面和错误调用。

# 9. Conversation-first, Agent-second

Companion 首先是聊天伙伴。

只有当任务真的需要多步执行时才进入 Agent / background task。

不要把主聊天变成开发者 Agent Console。

# 10. Long-running Task

长任务应独立拥有：

```text
queued
running
paused
waiting_network
waiting_permission
waiting_user
partial
completed
recoverable_failure
terminal_failure
cancelled
```

主聊天只显示必要状态和最终结果，避免刷屏。

# 10.1 Agent Runtime Capability Re-check

Agent 长任务创建时的 permission snapshot 只用于审计，**不是**永久授权。

执行中必须在敏感时点复查当前 capability：

```text
sensitive read
external provider call
external side effect
final canonical commit
```

权限被撤销时：

```text
running → waiting_permission / cancelled
```

不得继续使用旧 snapshot 越权运行。

# 11. Prompt Injection Boundary

外部网页、PDF、邮件、OCR 文本均视为 Content，不是系统指令。

核心：

> **Content ≠ Instruction。**

外部内容不得提升权限、修改 System Policy、要求读取额外私人数据或执行高风险动作。

# 12. Minimum Necessary Context

AI 只拿当前任务需要的最小数据范围。

例如分析旅行花费：

可以读该 Trip 关联 Transaction，不代表可以读取全部 Finance history。

# 13. Ombre → Life OS

Ombre Memory 中的推断不能自动写入 Truth。

如需正式化：

```text
Ombre inference
→ Proposal
→ User confirmation
→ Domain Command
```

# 14. Life OS → Ombre

Life OS Fact 可在授权后作为 Context Source，但不能让 Ombre 变成第二份 Canonical Fact DB。

如果 Ombre 和 Life OS 冲突：

> Life OS Canonical State 优先。

# 15. Creative Vault

Vault：

- 不进入普通 AI Search；
- 不进入 Context Gateway；
- 不产生 embeddings 给 AI；
- 不暴露 metadata / relation / existence；
- 不提供 Tool capability。

Vault 内部可以**单向**引用普通世界对象（例如 Knowledge / Reading），但普通世界不保存反向关系、不建立反向索引（见 `01-世界模型与核心数据语义.md` §51）。

# 16. Multi-model Routing

OCR、Vision、Embedding、Research、Companion 可以使用不同模型或 provider。

默认对用户隐藏实现复杂度；高级设置可配置 provider，但不能改变数据权限边界。

# 17. Quiet Intelligence

AI 可以在允许范围内后台组织、索引、形成候选。

但：

- 不偷偷写 Truth；
- 不无意义 Push；
- 不自动创建 Goals；
- 不强迫用户整理系统；
- 不把所有 observation 都变成通知。

# 18. Invariants

- Life OS Is Canonical, Ombre Is Contextual
- Model Never Gets Entire Database by Default
- No Arbitrary SQL
- AI Commit Capability Absent
- External Content Cannot Elevate Privilege
- Vault Absent From AI Capability Registry
- Conversation Archive Separate From Context Window
- Long Task Separate From Main Conversation
- Conversation Archive ≠ Model Context ≠ Ombre Memory
- Chat Historical Snapshot
- Runtime Capability Re-check
