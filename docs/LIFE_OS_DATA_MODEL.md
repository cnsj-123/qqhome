# Life OS 数据模型（LifeDatabase v1）

Room `LifeDatabase`，version = 1，`exportSchema = true`（导出至 `app/schemas/`）。
**禁止** `fallbackToDestructiveMigration()`：后续任何 schema 变更必须提供显式 `Migration`。

## 实体总览（8 张表）

| 表 | 作用 | 关键列 |
|---|---|---|
| `life_entities` | 所有领域对象的身份锚点 | `id`, `entityType`, `revision`, `deletedAt` |
| `life_relations` | 有向关系边 | `fromEntityId`, `toEntityId`, `relationType` |
| `tags` | 标签（规范化名唯一） | `id`, `name`, `normalizedName` |
| `entity_tag_cross_ref` | 实体 ↔ 标签多对多 | `entityId`, `tagId` |
| `media_assets` | 一次媒体事件（一个身份） | `id`, `mediaType`, `takenAt` |
| `media_resources` | 同一资产的具体文件变体 | `mediaAssetId`, `role`, `sha256`, … |
| `media_links` | 资产 → 业务实体的引用 | `mediaAssetId`, `ownerEntityId`, `role`, `sortOrder` |
| `capture_items` | 采集收件箱 | `id`, `source`, `status`, `rawText`, `primaryMediaAssetId` |

## 核心设计决策

**1. `life_entities` 只装身份，不装业务字段。**
业务字段由各自的 typed entity 持有并通过 `id` 引用本行。禁止塞万能 JSON 列——
一旦某列变成"任意 JSON"，schema 就失去了约束力，索引、迁移、校验全部失效。

**2. `revision` 有明确语义。**
| 操作 | revision |
|---|---|
| create | `1` |
| 有意义更新 | `+1` |
| 软删除 | `+1` |
| 恢复 | `+1` |

它永远不为 0。未来做同步时，revision 是冲突检测的基准。

**3. 软删除而非物理删除。**
`deletedAt` 为 null 即存活。恢复只是把 `deletedAt` 置回 null（并 `revision + 1`）。

**4. 一个媒体身份，多个业务引用。**
一张照片可以同时挂在旅行、日记、植物上，靠 `media_links` 引用，不复制文件。
`media_resources` 保存同一资产的不同变体（原图 / 缩略图 / Live Photo 配对视频），
用 `sha256` 做去重查找。

**5. `capture_items` 不级联。**
`primaryMediaAssetId` **故意不建物理外键**。采集是历史记录，级联删除会静默抹掉用户
"我曾经记录过这件事"这一事实；引用有效性由 Repository 层保证。该列可空——纯文本采集
本来就没有媒体。

## 类型安全

枚举列使用 Room `TypeConverter` 持久化为 String，**不用裸 String**：

- `MediaType` → `MediaTypeConverter`
- `MediaResourceRole` → `MediaResourceRoleConverter`
- `CaptureSource` → `CaptureSourceConverter`
- `CaptureStatus` → `CaptureStatusConverter`

四个 converter 统一声明在 `@TypeConverters`（Database 作用域），因此 DAO 的**查询参数**
也能直接用枚举，而不只是实体字段。

未知取值的 converter 回落到默认值（如 `MediaType.IMAGE`）而不是抛异常：
数据库里出现旧枚举名不应该让整个页面崩掉。

## 索引与唯一约束

- `life_entities`: `entityType`, `updatedAt`, `deletedAt`, `(entityType, deletedAt)` ——
  后两者是"按类型列表且排除已删除"这个最高频查询的覆盖索引。
- `life_relations`: `(fromEntityId, toEntityId, relationType)` **唯一**，
  重复边在数据库层被拒绝（Repository 不需要先查后插）。
- `tags`: `normalizedName` **唯一** —— 大小写/空格差异不会产生两个同义标签。
- `entity_tag_cross_ref`: `(entityId, tagId)` 主键，重复挂载用 `IGNORE`。
- `media_resources`: `mediaAssetId`, `sha256`。
- `media_links`: `mediaAssetId`, `ownerEntityId`。
- `capture_items`: `source`, `status`, `createdAt`, `(status, createdAt)`。

## 迁移纪律

v1 是首个版本，无迁移历史。v2 起：

1. 在 `app/schemas/` 下留一份新版本的 JSON；
2. 写显式 `Migration(1, 2)`；
3. 用 `MigrationTestHelper` 验证；
4. 绝不打开 `fallbackToDestructiveMigration()`。
