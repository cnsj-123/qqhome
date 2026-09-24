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

- [ ] 端到端备份包含 Life OS 数据库（当前备份只覆盖 Closie 的 JSON）
- [ ] 同步能力（当前完全没有；在此之前「我的 → 同步」保持禁用）
- [ ] 把 `ClothingItem` 从 JSON 迁入 Room（需要完整数据迁移测试）
- [ ] `LifeDatabase` v2 的第一个显式 `Migration` 示例

## 纪律（长期）

- schema 变更必须有显式 Migration，禁止 destructive fallback
- `life_entities` 永不装万能 JSON
- 禁用态永远用 `enabled = false`，不用空 lambda
- 任何声称"已完成"的能力必须在真机上被回归清单覆盖
