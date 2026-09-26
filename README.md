# qqhome · Life OS v0.1（开发中）

一个本地优先的私人生活记录 Android App。所有数据只保存在手机本地，不会上传到服务器。

包名：`com.qq.closie`（namespace）；`applicationId` 仍为 `com.xiaoming.closie`，
以保证老用户覆盖安装时数据不丢。

## App Shell

启动后进入 **Life OS**：首页 / 记录 / ＋ / 生活 / 我的。

- **首页**：日期、Today、最近记录，留白优先，点空状态即可开始采集
- **记录**：采集收件箱时间线
- **＋**：快速采集 / 从相册（Coming soon）/ 粘贴文本 / 链接 / 手动记录
- **生活**：衣橱（进入下面已有的 Closie）、饮食、健康、阅读、出行（Coming soon）
- **我的**：设置、备份与恢复、媒体库（Coming soon）、同步（尚未实现）、版本

Life OS 的持久化基于 Room（`LifeDatabase` v1，8 张表）。
详见 [`docs/LIFE_OS_ARCHITECTURE.md`](docs/LIFE_OS_ARCHITECTURE.md) 与
[`docs/LIFE_OS_DATA_MODEL.md`](docs/LIFE_OS_DATA_MODEL.md)。

## 衣橱（Closie）

从「生活 → 衣橱」进入。当前已支持：

- **衣橱管理**：Owned（拥有）/ Returned（试过·退货）两套衣橱，支持多图片（平铺 / 本人 / 模特 / 商品图）
- **商品链接导入**：粘贴商品链接自动解析标题、价格、原价、品牌、店铺、平台与主图（Open Graph + JSON-LD）
- **搜索 / 筛选 / 排序**：多字段搜索、动态类别筛选、多种排序、只看未穿过
- **单品资料**：名称、分类、子类别、品牌、店铺、平台、商品链接、价格、原价、购买日期、尺码、安全类别、评分（0–5 星）、评价、面料成分、具体尺寸（含单位）
- **使用记录**：穿着 / 洗涤记录，历史列表、补录历史日期、误操作可删除，自动计算单次穿着成本
- **OOTD 日历**：按日期记录每日穿搭（含照片），保存后自动联动穿着记录
- **搭配室（Outfit Studio）**：画布拖拽、缩放、层级调整，支持试穿照片
- **本地备份与恢复**：导出 / 恢复完整备份（ZIP，含图片），以及导出衣橱 CSV

## 数据说明

- 数据仍是 **local-only**：只存在设备本地（`filesDir/closie/`）。
- **Web / server 同步尚未实现**，暂不支持云同步。

## 手机上构建 APK

把项目上传到 GitHub 的 `main` 分支后，打开仓库 **Actions → Android CI**，下载 `qqhome-debug-apk`，解压并安装其中的 APK。首次安装时请允许浏览器或文件管理器安装未知来源应用。

## 本地构建

```bash
./gradlew :app:assembleDebug      # 打 debug APK
./gradlew :app:testDebugUnitTest  # 跑 Room / Repository 测试
```

需要 JDK 17 与 Android SDK（compileSdk 35）。Room schema 会导出到 `app/schemas/`，请一并提交。
