# Android identity / signing / versioning

正式 APK 是 `com.qqhome.lifeos`；debug APK 是 `com.qqhome.lifeos.debug`，versionName 后缀 `-debug`，可并存且不会占用正式 update chain。源码 namespace 为 `com.qq.closie`，可见名称 Life OS。

版本入口在 `app/build.gradle.kts`：`versionName = 0.3.0`，`versionCode = 300000 + CI_BUILD_NUMBER`。本地未设置时 build number 为 0；明确提供非法、负数或超出 Android versionCode 上限的值会失败。GitHub Actions 使用 `github.run_number`。同一 workflow 的下一次运行号更大；重新创建 workflow/reset run counter 或更换发布渠道需先确认 versionCode 仍高于已安装版本。重跑同一 run 不代表一个新版本。

只有四项环境变量全部非空，Gradle release 才选择长期 signing configuration：

- `LIFEOS_KEYSTORE_PATH`
- `LIFEOS_KEYSTORE_PASSWORD`
- `LIFEOS_KEY_ALIAS`
- `LIFEOS_KEY_PASSWORD`

Owner 在 GitHub 自行维护四个 Secrets：

- `LIFEOS_SIGNING_KEYSTORE_BASE64`
- `LIFEOS_SIGNING_STORE_PASSWORD`
- `LIFEOS_SIGNING_KEY_ALIAS`
- `LIFEOS_SIGNING_KEY_PASSWORD`

CI 的 signing availability、signed build、release upload 都要求 `github.ref == 'refs/heads/main'`。手动运行 feature branch 仍只走普通 CI/debug。keystore 仅还原到 `$RUNNER_TEMP`，之后清理。Secrets 不完整时仅跳过 signed release；不生成随机 key，不使用 debug key 签 release，不上传 unsigned APK 冒充 update APK。

普通 CI 执行 `testDebugUnitTest`（含 Room migration / restore safety）和 `assembleDebug`，上传 unit test report 与 `lifeos-ci-debug-apk`。完整签名配置的 main 额外执行 `assembleRelease` 并上传 `lifeos-update-release-apk`。

本地可以在 `/tmp` 使用一次性测试 keystore 验证 wiring，测试 key 不能作为 Owner 长期 key。真正覆盖升级要求 package identity 一致、签名证书一致、versionCode 上升；仓库的静态验证不能替代 Owner 手机上实际安装验证。不要提交 keystore、base64 key、密码、token 或 APK。
