# F32 / F48 当前实现约定（未运行 Android 验证）

FinanceMediaLink 是 Finance 专用 typed 关系，恰有一个 Event / Shopping Purchase / Proposal 外键。
它保存共享 MediaAsset ID、原件 SHA256、用户名称及版本，不创建假的 LifeEntity，不另建原图目录。
图片与 PDF 使用同一个 MediaAsset/Resource 机制；PDF 的 MediaType 为 DOCUMENT。
选同一摘要的原件复用媒体身份；一个原件可以被多个事项和购买记录引用。

MediaAsset ID 为有意的跨域软引用：旧备份恢复会整体替换共享媒体表，但保留备份后已退役的财务历史。
对 MediaAsset 建硬 FK 会阻止这一已有恢复策略，或迫使恢复伪造 MediaAsset。
新建引用时 Repository 验证真实 asset/resource；读时区分可用、媒体身份缺失、原件缺失/授权失效、内容已改变。
缺源不删除现金、事项或商品分配；重新选择相同摘要原件可恢复 URI 引用，媒体 ID 保持不变。
删除 Finance 引用只退役该关系，不删除共享资源或原文件；其他引用不受影响。

入口：财务工作页的事项、购买记录以及来源复核小票都有“凭据与附件”。
选图 OCR 位于“自动发现”，不需要开启通知监听/无障碍/悬浮窗。
OpenDocument 只访问用户所选 URI，支持 image/* 与 application/pdf；OCR 只识别图片。
单文件上限32 MiB，解码前检查尺寸并采样到最大2048边长，不写临时截图或 OCR 原文。
读取、解码和 ML Kit 完成后回收 Bitmap；取消识别不会创建候选、账务或媒体记录。
ML Kit Task 使用期间不会提前回收 Bitmap；跨 dispatcher 解码先交接所有权，取消返回也能清理。

本地有限语法只提取金额、完整时间和方向建议。多金额/多日期/无完整时间保持未知。
用户先核对人民币方向，再保存持久候选和不完整 ReviewSession；未识别时间留空，选图时间不冒充交易时间。
必须通过标准复核与显式确认才能写 Canonical。退款保留性质并关联原事项；还款核对转账两端。
仅当明确勾选“保留原图凭据引用”才保存媒体资源 URI；否则仅保留最少字段和识别问题。
不扫描相册、不默认上传图片、不启用真实云、不自动把 OCR 文本写入备注。

引用及 typed 字段进入主动加密备份；系统 URI 授权不作为备份事实恢复，不自动再次 OCR。
URI 原件属于外部来源，文件本身不复制进 Finance，也不保证换机仍可访问；恢复后显式检查/重新选原件。
完整备份中的已有应用托管媒体继续沿用原有文件归档机制。

已写但未运行：FinanceMediaV16Test / FinanceLocalOcrParserTest。
已执行：生产45新表迁移 SQL、旧27表逐行保留、FK检查与3故障点回滚；源码结构检查。
未执行：Kotlin编译/KSP、Robolectric、Compose、URI授权/撤销、离线ML Kit、设备取消/进程死亡。
