# Life OS Android 安装与构建基线

- 正式安装身份固定为 `com.qqhome.lifeos`，显示名保持 `Life OS`。
- Kotlin namespace 暂时保持 `com.xiaoming.closie`；不迁移旧 App 私有数据。
- 长期覆盖安装使用同一把 Owner 保管的签名 key，并使用更大的 versionCode。

## 本地构建

普通开发检查不需要 release 签名材料：

```bash
./gradlew test
./gradlew :app:assembleDebug
```

默认 debug key 只用于开发。`lifeos-ci-debug-apk` 的安装身份是
`com.qqhome.lifeos.debug`，versionName 带 `-debug` 后缀；它可以与正式
`com.qqhome.lifeos` 并存，不占用正式安装身份，也不进入长期覆盖更新链。

安装版使用 `./gradlew :app:assembleRelease`。通过外部环境提供以下四项，值不能为空：

| 环境变量 | 用途 |
| --- | --- |
| `LIFEOS_KEYSTORE_PATH` | 仓库外 keystore 的路径，推荐绝对路径 |
| `LIFEOS_KEYSTORE_PASSWORD` | keystore 密码 |
| `LIFEOS_KEY_ALIAS` | 长期签名 key 的 alias |
| `LIFEOS_KEY_PASSWORD` | key 密码 |

四项完整时才配置 release signing。缺失时 Gradle 不使用 debug key 或生成新 key
补位；直接 assembleRelease 得到的 unsigned APK 不属于可安装更新产物。
签名路径不存在、密码错误或 key 无效时构建失败，不切换到另一把 key。
签名材料应由 Owner 在仓库外妥善保管、备份；仓库不生成正式 key、不保存密码。

## GitHub Actions 接口

Owner 后续自行配置四个 Secrets；本提交没有创建或修改 Secrets：

| GitHub Secret | 构建环境变量 |
| --- | --- |
| `LIFEOS_SIGNING_KEYSTORE_BASE64` | 临时还原后设置 `LIFEOS_KEYSTORE_PATH` |
| `LIFEOS_SIGNING_STORE_PASSWORD` | `LIFEOS_KEYSTORE_PASSWORD` |
| `LIFEOS_SIGNING_KEY_ALIAS` | `LIFEOS_KEY_ALIAS` |
| `LIFEOS_SIGNING_KEY_PASSWORD` | `LIFEOS_KEY_PASSWORD` |

普通 CI 总是执行 test + assembleDebug。三个正式签名步骤都仅允许
`github.ref == 'refs/heads/main'`；PR 和手动选择 feature branch 的 workflow_dispatch
只执行普通 CI/debug 路径，不接收 release 签名材料。
main push 或在 main 上手动 workflow_dispatch 只有四项 Secrets 完整时才构建及上传
`lifeos-update-release-apk`。keystore 只写入 RUNNER_TEMP，构建结束或失败时删除。
上传路径只匹配 signed `app-release.apk`，缺失则失败，不上传 unsigned/debug 替代品。

## 版本入口

- `LIFEOS_VERSION_CODE`：未设置时 fallback 为 `1`；已设置必须为合法正 Int，
  空白、零、负值、非法或溢出时 Gradle 明确失败。
- `LIFEOS_VERSION_NAME`：非空值；未提供或空白时 fallback 为 `0.1.0`。
- CI signed build 使用同一 workflow 的 `github.run_number` 作为 versionCode，
  versionName 为 `0.1.<run_number>`。每个新 workflow run 递增；rerun 保持原版本号。
- 需要新版安装产物时启动新 run，不通过重跑旧 run 发布新版本。保持 workflow 的
  版本序列；将来更换 workflow 或版本策略时必须延续已安装的 versionCode 上限。
- 本地安装版也应显式设置高于已安装版本的 code；本地开发 fallback 不代表发布版本。

相同 applicationId、相同签名证书和递增 versionCode 是更新链的构建条件。
本地临时测试 key 只验证 wiring，不是 Owner 的长期 key；真实覆盖安装仍由 Owner 真机验收。
