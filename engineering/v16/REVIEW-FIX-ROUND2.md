# V16 Review Fix 第二轮定点修复（2026-10-19）

本地增量基线：`7d9fe46d4f489d50f72cfcff3e3b1b5dce02b319`，即上一轮34文件补丁的交付HEAD。本轮不重做V16、不重新讨论D1–D5、不下载Android工具链，不push/merge/PR/发布/触发CI。

| 问题 | 文件与修复 | 验证状态 |
|---|---|---|
| P0 CI历史可读 | android.yml保留原本已有的fetch-depth: 0，新增R2 cat-file/show及浅克隆状态日志；audit-v16-tests和verify-room-schema各自也显式检查R2对象 | 本地R2可读、is-shallow=false、历史1–4校验PASS；Actions实际执行UNVERIFIED |
| P1 JUnit假覆盖 | verify-ci-tests.py按完整类名+JUnit方法名逐项匹配固定契约与新增/修改清单；重复、缺失、failure/error/skip及套件级错误均FAIL | 反向用例methodA×2不能覆盖methodB、错误类名、无声明参数后缀、缺参数调用均被拒绝 |
| P1 删除旁路 | audit-v16-tests.py、v16_test_contract.py、test-minimum-contract.json固定96文件/638方法（上一轮634+本轮4），校验规范化SHA-256并扫描Git删除/跟踪状态与方法缺失；CI不生成最低契约 | 真实合成Git仓库验证删除文件、删除方法、删除后保留未跟踪副本、同步缩减契约均FAIL；新增/修改仍进入动态清单 |
| P1 对账精度 | FinanceAccountProjection/Status：算法3，DATE映射为本地自然日全部可能毫秒，INSTANT为单点；整个区间明确范围外才排除，整个区间明确范围内才计入，否则book/difference均null；指纹含精度、区间与可信度 | FinanceReconciliationScopeV16Test新增4个真实Room方法，均UNVERIFIED；涵盖同日、anchor重叠、明确范围外、精度变化、实际保存和重核失效 |

最低契约与动态清单分开：契约的规范化SHA-256为 `732e114d7512f7b4bf53dc61f19002017357a25da64ac59effd3f0bb4e352ce5`。修改契约须显式修改此受审常量，不能被清单自动缩减。该契约以本地交付内容为来源，CI只要求R2历史可读，不要求未推送的7d9fe46对象；Owner通过git am产生不同提交元数据时也能工作。当前动态V16审计仍为32文件，方法从144增至148；全套CI还须执行最低契约要求的638个方法。

参数化规则：默认只匹配原始方法名，不去掉任意`[suffix]`。确需参数化的源方法须在契约该文件的`parameterized`中列出每个必需的完整JUnit调用名，例如`methodA: [methodA[0], methodA[1]]`；所有列出调用都必须各执行一次，不能只出现其中一个，也不能用同名重复记录代替另一个方法。当前Kotlin套件无Parameterized runner声明；若实际CI产生额外runner名称，按实际日志补显式对应，禁止宽泛截断后缀求绿。

日期精度的工程边界（不改旧anchor）：DATE沿用现有本地时区解释，使用下一自然日开始前1毫秒为上界，包含DST日长变化，不固定加24小时。若观察本身为DATE，则账面余额必须对当日所有可能观察时点都确定；同日流水与不确定观察先后时保持INCOMPLETE。UNKNOWN/LEGACY入账精度不能视为确知时点；原LEGACY_CASH流水时间公式保留，观察DATE仍按不确定范围处理。postedAt缺失始终不取occurredAt/recordedAt。算法1/2旧结论保留并提示重核；本轮无Entity/DDL/备份格式更改。

CI顺序已静态重审：完整checkout→JDK17/Gradle→R2日志→22例工程门禁→来源/最低契约审计→结构/SQLite/历史schema→KSP→严格官方schema→全套testDebugUnitTest→逐调用JUnit与真实2000条指标→assembleDebug→APK commit/哈希。KSP成功且Bootstrap门禁失败时仍收集全套测试，job保持红；assemble只在所有门禁成功后运行。

Artifacts继续含run number和SHA：unit-test-results（XML/HTML/2000条实测报告）、room-schema（官方JSON）、v16-ci-evidence（日志、执行校验、动态清单、最低契约及工程门禁报告）失败时仍上传可得材料；lifeos-ci-debug只在assemble成功上传APK/commit-sha/SHA256SUMS。Release产物单独命名且只在main读取正式签名secret。方法与具体名称见GITHUB-VALIDATION.md。

本地结果：Python工程门禁22/22 PASS；首次新辅助测试出现两行错位的AttributeError，修正归属后原有断言保留，全套通过。动态审计/最低契约、R2与历史1–4、结构及git diff --check通过。官方Room5仍未生成/提交，严格门禁实际FAIL，等待既定KSP Bootstrap。Android/KSP/Room/Compose、四个新方法、全套638方法、真实2000条运行、APK与Actions均UNVERIFIED。交付后等待Owner授权推送。
