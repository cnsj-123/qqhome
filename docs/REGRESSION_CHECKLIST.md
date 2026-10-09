# Unified baseline — manual regression checklist

本清单是 Owner 真机验证项，不表示已经执行。自动化 unit/build/APK identity 检查结果见 patch 交付报告。

## 安装

- [ ] release `com.qqhome.lifeos` 使用同一长期签名 key、递增 versionCode，可覆盖前一 release，私有数据保留。
- [ ] debug `com.qqhome.lifeos.debug` 可与 release 并存，不阻挡 release 更新。
- [ ] 显示名称 Life OS，冷启动到 V11 Home，没有全局 Bottom Navigation。

## Shell / Appearance

- [ ] Home 左滑和显式按钮打开部分宽度抽屉；露出 Home 可点击关闭、右滑关闭、Back 优先关闭。
- [ ] 不抢系统左边缘返回；predictive back、landscape、字体放大、TalkBack 核心入口正常。
- [ ] Home History / Future / 照片空状态诚实，没有 demo canonical 数据。
- [ ] Appearance 尚未加载时显示轻量纸张 loading state；该阶段不提前取得业务存储。
- [ ] 有 eligible media fixture 时照片堆可拖拽/回弹/翻页，点击 viewer 是浮层，Back/close 返回 Home。
- [ ] Calendar / Map / Companion 可进入和返回，Chat 不伪造连接/回复。
- [ ] Appearance preview 不持久化；Restore + Cancel 保留之前主题；Save 才写入；重启仍保留；Custom 切 preset 后不丢失。

## 真实模块 / intake

- [ ] Drawer → Closet 可进入真实列表、detail/editor、OOTD、Outfit Studio，局部返回回到 Life OS。
- [ ] Drawer → 收件箱/资料库/计划/已保存阅读资料/备份恢复进入真实实现。
- [ ] Capture 手动编辑空白保存/取消无新增行；文本/链接采集保留原文。
- [ ] Capture 收件箱/详情没有单条永久删除入口；DISMISSED 没有被冒充回收站。
- [ ] 普通网页分享 → Capture → unclassified Reference editor；网络失败仍能打开 Capture detail。
- [ ] 商品分享 → Closet add/import；纯文本分享 → Capture detail；Edit / Open Add extras 保留。
- [ ] 旋转/重建不会重放已消费分享；新分享不会被旧异步完成覆盖。
- [ ] Quick Capture MediaProjection / floating-ball / preview / 保存后返回正式 App 不退回废弃 root。
- [ ] 没有相册默认复制原图的公开 canonical import；最终原相册引用功能尚未开放。
- [ ] Reference 自动提取文本与用户 note 分开，原始 Capture 不因整理被删除。

## 恢复安全

- [ ] Backup v2 包含当前 wardrobe + typed Room 数据及 managed 资源，恢复前保留 recovery snapshot。
- [ ] 中断恢复后启动先完成 recovery barrier；失败关闭业务访问且给出恢复提示。
- [ ] 旧 wardrobe-only backup 不盲目清空 Life OS 数据；恢复期间拒绝业务写入。
