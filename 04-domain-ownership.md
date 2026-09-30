---
Title: Life OS Domain Ownership 与跨模块共享规则
Version: 0.1
Status: DRAFT
Authority: Canonical Architecture Detail
Last Updated: 2026-09-30
Owner: Product Owner
Scope: Life OS
Parent Document: 00-architecture-overview.md
Recommended Path: docs/life-os/04-architecture/04-domain-ownership.md
---

# 1. 目的

本文定义“某类事实到底由谁负责”，避免多个模块分别创建同一事实。

# 2. Domain Ownership 原则

每一种 Canonical Fact Type 必须有唯一 Owner Domain。

Owner 负责：

- 语义定义
- 写入规则
- invariant
- merge / deletion policy
- conflict policy
- schema evolution

其他 Domain 可以：

- Query
- Reference
- React
- Display
- Propose

不能复制同义事实。

# 3. 初步 Ownership Registry

```text
Finance
- Account
- Transaction
- Transfer / Refund / Reimbursement financial semantics

Commerce / Shopping
- Product
- Purchase
- Return / Rebate / Resale commerce semantics
- personal price memory / buy decision state

Items
- Item
- ownership lifecycle
- generic inventory state

Closet
- WearEvent
- WashEvent
- TryOnEvent
- Closet item extension
- Outfit / OOTD domain semantics

Membership
- MembershipAccount
- Recharge / Renew / Consume ledger

Food
- Meal / EatingEvent
- personal food experience

Hobbies
- HobbySession
- ClassSession
- ConcertEvent
- Artwork

Garden
- Plant / Cultivar
- CareEvent
- Bloom / Repot / Death lifecycle

Health
- SleepEvent
- MedicationIntake
- MedicalVisit
- Health Observation

Reading
- ReadingSession
- Me↔Book reading state

Travel
- Trip
- TravelLeg
- HotelStay where travel semantics own the event

Place
- Place Entity
- GenericVisitEvent only when no more specific domain event exists

Knowledge
- Knowledge Artifact / personal note semantics
- claim/source relations

Plans
- Goal / Project / Task / PlannedEvent / RecurringPlan

Life Marks
- GenericMarkEvent only when no professional Domain fact exists
```

# 4. 跨模块示例：油画课

Owner：

```text
hobbies.class_session
```

Membership 只根据它消费次数。

Calendar 只投影日期。

Life Marks 只查询最近一次。

Home 只展示或 resurfacing。

# 5. 跨模块示例：购物

```text
Finance      owns Transaction
Commerce     owns Purchase
Items        owns Item
Closet       owns Wear / Wash
```

这四个对象通过关系连接，不压缩成一条“购物记录”。

# 6. 跨模块示例：演唱会

```text
ConcertEvent
 ├── owned by Hobbies
 ├── part_of Trip
 ├── at Place
 ├── paid_by Transaction
 ├── participants Person
 ├── media MediaAsset
 └── referenced_by Plog
```

Travel、Calendar、Home 不再创建自己的 Concert copy。

# 7. Life Marks Rule

有专业 Event：

> 使用专业 Event。

没有专业 Domain：

> 创建 GenericMarkEvent。

# 8. Calendar Rule

Calendar 不 Own canonical events，只投影 Event / Intent / External Calendar Reference。

# 9. Home Rule

Home 是 Projection Surface，不创建专属 life facts。

# 10. Place Rule

Place 页面汇聚发生在此地点的专业 Event。GenericVisit 仅用于无专业语义的简单到访。

# 11. Person Rule

Person 页面汇聚 shared events / media / place / relations，不维护第二套 interaction log。

# 12. Domain Contract

每个 Domain 后续应维护：

```text
Owns:
References:
Accepts Proposals From:
Emits:
Consumes:
Deletion Policy:
Merge Policy:
Conflict Policy:
```

# 13. 新 Canonical Type 的准入

创建新 Type 前检查：

1. 是否已有语义相同的 Canonical Type？
2. 能否作为 Extension？
3. 能否通过 Relation 表达？
4. 是否只是 View / DTO / UI state？
5. 是否只是 Derived？
6. 是否需要长期独立 identity？
7. 谁是唯一 Owner？

# 14. 禁止模式

- `calendar_event_copy`
- `home_event_copy`
- `place_visit_copy` 与专业 Event 重复
- Membership 自己复制 ClassSession
- Life Marks 复制 Hobbies Event
- UI Screen 持久化自己的 canonical DTO
- Plugin 定义与 Core 重复的 canonical truth

# 15. Ownership 冲突处理

两个 Domain 都声明 Ownership 时：

> 暂停实现，先做 Architecture Review / ADR。

不能靠“谁先写代码谁拥有”。

# 16. Invariants

- One Canonical Type, One Owner
- Cross-domain Reference, Not Duplication
- Feature Does Not Own Truth Merely Because It Displays It
- Projection Surfaces Are Not Domains
- Generic Event Is Fallback, Not Competitor to Professional Event
