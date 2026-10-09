# Life OS Android · 0.3.0

本地优先的私人生活档案。统一入口是 Life OS Home：照片堆、历史、未来、生活抽屉、日历、地图与伙伴入口。Home / Calendar / Map 当前为原生表现层，空用户不会导入示例人生记录。伙伴连接尚未接入。

Drawer 可进入真实衣橱、Capture 收件箱、资料库、计划、已保存阅读资料及备份恢复。其余专业领域仍是明确标记的模块入口。衣橱保留局部兼容导航；它不是顶层产品壳。

- 源码 namespace：`com.qq.closie`
- 正式 release 安装身份：`com.qqhome.lifeos`
- CI/debug 安装身份：`com.qqhome.lifeos.debug`，标准 debug key
- 显示名称：Life OS
- Room v2 保存当前 typed 能力；衣橱仍由既有 JSON repository 拥有。
- Appearance 使用 DataStore，临时预览与保存分离。
- 相册 canonical intake 暂未开放：不得默认复制第二份原图。现有截图证据/衣橱图片不作为最终全局 Media 契约。

参阅 [工程基线审查](docs/LIFE_OS_ARCHITECTURE.md)、[当前存储范围](docs/LIFE_OS_DATA_MODEL.md)、[构建与安装](docs/ANDROID_BUILDS.md)。已确认产品/统一架构文档位于独立 `life-os-docs` 分支，本分支工程说明不替代它。

```bash
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:assembleDebug --stacktrace
```

需要 JDK 17、Android SDK 35。debug artifact `lifeos-ci-debug-apk` 仅供 CI/开发；Owner 长期安装应使用 main 上稳定签名的 `lifeos-update-release-apk`。旧 prototype package 不做跨包数据迁移。
