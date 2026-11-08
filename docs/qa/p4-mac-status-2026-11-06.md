# P4 Mac 实现与验证记录

此页保留初始研究/实施时的历史状态。最新集中验收见 [2026-11-08 回执](p4-status-2026-11-08.md)。

日期为 2026-11-06，分支为 `codex/p4-task-strategy`，基线为主分支 `4b4801e`。
P3 已有受控记忆，但缺少可导出的任务执行指标和有界格式失败升级机制。本轮补齐这些
Mac 可实现的工程能力，保留原模型与 MNN 3.6.1，不把工程通过等同内容质量通过。

## 已实现范围

- 按实际 MNN/云端执行尝试记录任务类型、触发类型、结果、首输出及总耗时和可用 token/native 指标。
- request 和 model 标识只保留 SHA-256，不记录账号、Prompt、Response 或自由错误文本。
- 本地文件限制 512KiB、256 条；读取或写入时清理超过七天的记录，不承诺 App 停止时后台定时删除。
- 文件严格解析、同目录原子替换，清理水位阻止旧任务和旧注解重新写回，时钟回拨不能延长保留。
- 有界异步队列隔离磁盘 IO，生成结束和取消不等待存储；繁忙、存储失败和进程死亡可以造成指标缺失。
- 设置新增“推理诊断”，可读取计数、用户主动 SAF 导出和清理；页面及文案不展示内部 hash。
- 默认关闭的本轮格式失败转云授权，独立于账号已有云端提取授权，不持久化到下一次提取。
- 云端升级仅处理记忆提取的严格 JSON、字段和来源契约拒绝；有效空结果和语义猜测不升级。
- 升级需两项授权、来源允许出端、非计费网络；未知/离线/计费网络均拒绝自动升级。
- 每次最多本地一次、云端一次，共用六十秒预算；执行错误、用户取消和云端再次拒绝均不继续重试。
- 来源、账号、任务状态、授权 revision 在准备、升级和落库前复核，候选继续逐条确认。
- Python 对照工具区分 attempt/request、执行完成/契约接受，保留未知指标并严格拒绝错误文件。
- 同负载声明与完整观测形状匹配时才生成描述性差值，不将统计差值宣称为因果或内容质量提升。
- CI 新增无模型的 Python 合同检查，原有 Android、backend 与其他 jobs 保持不变。

指标计时从执行器流开始，包含下游收集，不包含其前面的路由、模型准备和上下文规划。
这不是用户全链路 TTFT、完整请求总体成功率或云端账单。云端实际模型身份与 native
计数未知时保留 null；语义质量一律未评估。诊断是本设备匿名记录，不按账号隔离。

## 实际 Mac 检查

JDK 17、Kotlin 2.0.21 和已有依赖缓存编译实际选定业务源码。涉及 Android、Hilt、
Keystore、模型管理、Room DB/transaction 的位置使用已有明确 framework boundary。
Curation 仓库已做 Kotlin 类型编译，但没有据此声称真实 Room 事务或生成代码通过。

```text
Core extraction / tracker / buffer / real file journal  OK (75 tests)
Hybrid + Room repositories / mapper                    OK (81 tests)
Chat / prompt / session                               OK (36 tests)
Memory / curation ViewModels                          OK (63 tests)
Diagnostics ViewModel + actual file journal            OK (13 tests)
Python telemetry contract                             Ran 25 tests, OK
Synthetic summarize + compare examples                OK
Kotlin -> buffered journal -> Python report            OK
```

上述 Kotlin/JVM 测试为 268 项不同用例，属于选定范围，不能当作完整 Gradle 全量结果。
实文件测试覆盖损坏、超大、TTL、并发、取消、清理与安全错误；回归保留先失败后修正记录。
跨语言对接使用生产 runner、tracker、buffer、journal 与 CLI，执行器为合成 Flow，
没有真实 MNN 或云端请求。原始历史失败与新通过记录分别保存，没有覆盖失败证据。

本机实际尝试如下，Gradle 插件已从独立缓存解析，随后因缺少 Android SDK 阻塞。

```text
sh gradlew --offline testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
SDK location not found.
BUILD FAILED in 9s
```

因此 Android APK、Hilt/Room 生成代码、实际 Activity/SAF 和设备行为仍待 Windows 验收。
Kotlin grammar 与 XML/资源引用检查只证明语法及资源引用，不证明 Android 类型编译。

## 单候选模型

研究固定为 Qwen/Qwen3-0.6B、non-thinking、CPU INT8，保留两个 Qwen2.5 baseline。
官方模型 SHA 为 `c1899de289a04d12100db370d81485cdf75e47ca`，MNN 固定 SHA 为
`d407447ed56c4121a11ccbd266dc184ca1ead0c2`。研究已确认 exporter 有 Qwen3 结构支持，
App 仍有 family、tokenizer、模板和预算契约缺口，不能伪装成 Qwen2.5 绕过门禁。
本轮未下载权重、转换模型、改变默认模型或升级 MNN，不宣称新模型质量或性能改善。
详见 [候选研究](p4-model-candidate-2026-11-05.md)。

## Windows 集中验收入口

已准备八项 `data.local.P4ExtractionCascadeInstrumentedTest`，用真实 Room/Auth/Hybrid，
搭配合成模型文件、引擎和 SSE，不调用 JNI 或网络。仅接受隔离包，需显式开启。

```powershell
.\gradlew.bat -Ppersona.isolatedValidation=true testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb shell am instrument -w -e class com.example.persona.data.local.P4ExtractionCascadeInstrumentedTest -e runP4Cascade true com.example.persona.validation.test/androidx.test.runner.AndroidJUnitRunner
```

用例覆盖一次升级、默认关闭、独立授权、本地专用、网络门禁、来源/授权撤回、第二次拒绝
和合法空结果。测试源码通过语法检查，尚未 Android 编译或执行，八项不计入通过数。
Windows 还需验证诊断实际 SAF 导出/取消/重建/清理，以及原聊天、记忆与取消回归。
真实模型、云端调用与资源效果必须分别留证，不能用受控传输通过替代。

## 交付状态

Mac 实现及本机可执行检查完成，P4 整体与候选模型采用尚未完成。
源码、日志、快照与跨语言样例位于忽略目录 `build/verification/p4-mac/`，完整隐私原文不入 Git。
没有连接 Windows 或手机，没有修改原手机 App，也没有提交、推送或触发本轮远程 CI。
Embedding、GPU、Prefix Cache、系统模型和 Agent 继续按实际收益条件推进。
