# V16 严格 CI 与官方 Schema Bootstrap（2026-10-19）

本轮增量基线为 `9a0acf0da84544750bd37f1e15e5220c4a899a95`，R2 仍为 `245e1475cd1d47a4205ab49f75d5e06d22de701c`。以交接包 manifest 中最终 HEAD 和补丁 SHA-256 为准。本机按 Owner 要求未下载工具链，未 push、PR、merge、发布或触发 CI。

官方 Room `1.json`–`4.json` 已跟踪且 Git 内容与 R2 相同；官方 `5.json` 尚未生成或提交。`engineering/v16/schema-v5.json` 只是设计契约，不能替代官方导出。当前严格门禁真实状态是 FAIL（缺少已提交的官方5），不是 Android 编译失败或通过的证据。

Owner 授权后，在干净的上述 V16 HEAD 上用 `git am` 应用本轮 format-patch，再推送匹配 `feature/life-os-v16-*` 的功能分支，例如现有 `feature/life-os-v16-g0-g7-local`，自动触发 Android CI。main push、pull_request 和 Actions 页 Android CI 的 Run workflow 入口保留；手动运行时选择同一功能分支。每次先核对 Actions 的完整 commit SHA，禁止混用旧运行的报告或 APK。

首次 Bootstrap 及后续门禁：

1. CI 完整 checkout，使用 JDK 17、工程 Gradle wrapper 和 compileSdk 35。源码检查、9例工程门禁测试、测试清单、真实 SQLite 辅助检查及历史1–4校验先执行；这些结果不替代 Kotlin/Room。
2. 执行 `./gradlew :app:kspDebugKotlin --stacktrace`，日志在 `ksp.log`。若此处失败，先按实际编译/KSP根因修复；不能手写官方5来绕开。
3. 严格脚本要求5同时被跟踪、存在于 HEAD、KSP生成字节与HEAD一致，且整个schema目录无diff；1–4同时检查R2。第一次缺少已提交5时必须红灯。
4. KSP成功后，即使Bootstrap门禁失败也继续 `./gradlew :app:testDebugUnitTest --stacktrace` 收集完整测试结果，job仍为失败。真实JUnit不得失败或跳过；`verify-ci-tests.py` 检查V16清单中的类/方法数量及本次SHA的实际2000条指标，缺报告、缺指标或错SHA均FAIL。CSV目录声明为测试输入，CI禁用该Test任务的旧结果缓存和up-to-date跳过。
5. 下载本次 `room-schema-run<run>-<SHA>` 中KSP导出的官方5，核对运行SHA、KSP日志及 `database.version=5`、实体集合、identityHash；原样放入 `app/schemas/com.qq.closie.life.data.database.LifeDatabase/5.json` 并本地提交。由Owner决定下一次推送；不能用设计JSON重写文件。此动作产生新的代码SHA。
6. 下一轮KSP再次生成并严格逐字节比较，任何变化仍FAIL。只有所有必要门禁与完整测试通过才运行 `./gradlew :app:assembleDebug --stacktrace`。Sol负责定位实际首个失败根因并提供新的本地增量修复，Owner决定推送。

| Artifact 名称（`<run>`为run number，`<SHA>`为完整提交SHA） | 内容与检查方法 |
|---|---|
| `room-schema-run<run>-<SHA>` | 官方1–5 JSON；首次KSP成功才会有5，先核对本次运行再原样归档。没有5时查ksp.log |
| `unit-test-results-run<run>-<SHA>` | JUnit XML、HTML和 `reports/v16/testDebugUnitTest/synthetic-history-2000.json`；检查failures/errors/skipped、144个V16源码方法对应执行及真实指标 |
| `v16-ci-evidence-run<run>-<SHA>` | KSP/全套测试/assemble等日志、严格schema报告、执行报告校验、结构/SQLite/测试清单；失败时也上传可取得的材料 |
| `lifeos-ci-debug-run<run>-<SHA>` | 仅assemble成功后上传；debug APK（applicationId `com.qqhome.lifeos.debug`）、`commit-sha.txt` 和 `SHA256SUMS.txt`。校验SHA文件与本次运行/下载APK哈希 |
| `lifeos-update-release-run<run>-<SHA>` | 保留的main正式签名流程产物；feature不读取release signing secrets，不将此产物作为功能分支CI必要项 |

报告/schema/logs使用 `always()` 上传。没有APK表示本次尚未完成构建，不能取旧APK补齐。2000条报告包含实际分阶段/总耗时和JVM堆采样，属于CI算法与数据回归，不能冒充真机帧率或峰值内存验收。

新增/修改测试逐文件自审见 `TEST-AUDIT.md`，问题/文件矩阵见 `REVIEW-FIX.md`。Room数据库5、备份格式5、manifest schema1、AEAD envelope2和核对指纹算法2是不同版本空间。本轮没有更改实体DDL或typed备份结构。

所有Android/KSP/Room/Compose、2000条真实运行、Actions实际执行和APK均UNVERIFIED；本地只有工程门禁9例、历史schema、结构与SQLite辅助证据。完整CI通过后才对唯一SHA进行最终独立Reviewer审查；无阻断后按 `OWNER-ONE-ROUND.md` 集中一次设备验收。七宿主、vivo/IME/Back/锁屏/撤权和真机性能保持DEVICE UNVERIFIED，不安排逐阶段Owner手验。
