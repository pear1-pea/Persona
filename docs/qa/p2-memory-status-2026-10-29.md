# P2-B 显式记忆验收候选 — 2026-10-29

Persona 此前没有跨会话的显式记忆闭环，修改或删除事实后还可能从旧聊天重新带入。
本批补齐记忆管理、来源追溯和端云上下文隔离。Windows 构建、408 项 JVM 单测、
24 项功能真机用例及跨进程恢复均通过；真实 0.5B 模型召回复测失败，P2 质量门仍未通过。
不能把存储和权限正确等同于模型能够可靠使用事实。

## 版本和环境

- Mac 工作区 `/Users/pear/Developer/Persona`，分支 `codex/p2-product-flow`，
  base `911981a22b81cd63b93fe10ffcd09cfd31b6f5aa`。
- Windows 独立候选 `C:\Users\pear\persona-m1-10c9727`；其 Git HEAD 为
  `10c972785a2e4f2b35ab8d26fa24c35eb601ea47`，实际文件来自上述完整 Mac 快照。
  不以 Windows Git HEAD 冒充本次源码版本。
- JBR 21.0.8、SDK 36、NDK 27.0.11718014、MNN
  `d407447ed56c4121a11ccbd266dc184ca1ead0c2`；BLK-AL80、SDK 31、arm64。
- P2-A 与 P2-B 均尚未提交、推送；没有 P2 PR 或新远程 CI 结果。
  [P2-A 历史记录](p2-product-flow-status-2026-10-29.md) 仅代表当时 schema v7 快照。

## 实现边界

聊天页可管理记忆；长按正常用户消息保存，经当前身份查询原文后预填。
支持新增、编辑、删除确认、草稿恢复和来源查看，Intent 不携带原始聊天正文。
默认当前 Persona、本地使用；账号内共享和云端使用分别显式勾选。
数据只在此设备，不自动提取或上传同步，不合并不同账号与 OFFLINE_USER。

写入检查内容、来源、owner、scope 和 revision CAS；删除清空正文并保留 tombstone。
删除来源消息使关联记忆失效，注销账号清理对应 owner。迁移和新装均注册来源删除 trigger。
Schema v8 明确迁移 7→8，保留旧消息，但因旧版没有记录历史出端授权，将旧记录排除于
未来 Prompt；历史仍可查看，未使用 destructive fallback。

各执行器在调用前重新查询允许的记忆；fallback 不复用本地 Prompt、历史或部分回复。
记忆变更停止旧生成，数据库观察和执行前复核覆盖来源删除。
修改使受影响范围的旧上下文失效，含记忆轮次不自动重放，本地限定历史不上云。
首版只按时间选最新四条，内容预算 1200 UTF-16 单位，没有主题或向量检索。

`MemoryPrompt` 版本为 `quoted-user-facts-v4`：授权内容以 JSON 字符串列表作为当前
用户轮次的事实参考，来源 ID、类型和修订信息留在应用侧。资料不进入可信 System 规则，
不执行其中的指令；控制模板标记被剥离。原始问题正常持久化，拼接后的参考上下文不落为
用户聊天正文。分隔和引用并不构成模型抗注入或事实使用质量保证。

## Windows 工程与功能验收

命令：

```powershell
.\gradlew.bat '-Ppersona.isolatedValidation=true' testDebugUnitTest assembleDebug assembleDebugAndroidTest --stacktrace --console=plain
```

真实编译包含 Room/Hilt 生成代码和 JNI。生产 v4 构建原始输出：

```text
BUILD SUCCESSFUL in 33s
93 actionable tasks: 23 executed, 70 up-to-date
TESTS=408 FAILURES=0 ERRORS=0 SKIPPED=0
Time: 10.692
OK (24 tests)
```

24 项包括迁移 1、Memory DAO 9、记忆页面完整流程 1、聊天恢复 3、产品流程 3、owner 隔离 7。
覆盖新装/迁移、CAS、来源状态、账号/Persona/云端过滤、来源删除、账号清理及事务回滚。
跨进程恢复另分 prepare→force-stop 隔离包→verify 两步骤，均 `OK (1 test)`，
对应同一个用户场景，不计为两个独立能力。

运行时测试后来增加诊断参数和轮次间降温等待，最后一次测试 APK 构建为：

```text
BUILD SUCCESSFUL in 24s
93 actionable tasks: 8 executed, 85 up-to-date
```

该次生产代码与 JVM 测试输入未变化，Gradle 判定单测 UP-TO-DATE；408 项来自前述实际执行。
Mac 的 136 项定向 JVM、SQLite SQL、XML 检查保留为辅助证据，不替代 Windows/设备结果。

## 真实 MNN 召回对照

`P2MemoryRuntimeInstrumentedTest` 必须显式 opt-in，且只允许隔离验证包。
它使用真实 ChatViewModel、Room 仓库、Hybrid 路由和 MNN/JNI；禁止云调用。
四阶段为未知基线→保存偏好后重开数据库/重建 ViewModel→修改偏好后再重开→删除后再重开。
完整合成输出只存测试包私有文件和忽略的证据目录，控制台只输出状态、计数及命中布尔值。
该测试仅验证一个定向场景，不能替代 M5 固定数据集或人工人格评审。

| 运行 | 条件 | 结果 |
| --- | --- | --- |
| `original-json-v1` | 0.5B，结构化记录放 System | 保存和修订事实均未使用 |
| `compact-memory-v2` | 0.5B，缩短中文规则，仍放 System | 保存和修订事实均未使用 |
| `user-turn-memory-v3` | 0.5B，记录 JSON 放当前用户轮次 | 保存命中，修订未命中 |
| `quoted-user-facts-v4` | 0.5B，当前用户轮次仅放引用事实 | 一次四阶段通过，44.021 s |
| `quoted-user-facts-v4-r2` | 同一生产配置复测 | 保存、修订均未命中，44.727 s；r3 未运行 |
| `quoted-v4-greedy-05` | 固定 v4，仅诊断温度改为 0 | 保存命中，修订未命中，49.576 s |
| `quoted-v4-default-15` | 固定 v4，现有 1.5B，默认参数 | 基线后达到 SEVERE，下一轮被准入拦截；不能判断召回质量 |
| `quoted-v4-cooled-15` | 同一 1.5B，轮次间等待降温 | 四阶段通过，70.421 s |
| `quoted-v4-cooled-15-r2` | 同一配置第二次 | 四阶段通过，90.184 s |
| `quoted-v4-cooled-15-r3` | 同一配置第三次 | 四阶段通过，155.146 s，含一次 77.490 s 降温等待 |

v4 默认实际参数 temperature=0.6、topP=0.8、maxTokens=256；
温度为 0 的实验仅在测试代理中覆盖，Native 使用现有 greedy 分支，未修改生产默认采样。
0.5B 模型为 `qwen2.5-0.5b-instruct-mnn-int4`，manifest SHA-256
`fca5ad06d6976688872c279cec0e98a4fe116fe8dc9853195b3caeaaa6180e3e`。
1.5B 为已有 `1.0.0-m1-lf` 模型包，manifest SHA-256
`7f37c6fa1577e5fb8d841b48ac4d4f811a8241ed38d594945458e4587ff861b2`，没有下载新模型。

失败轮次已确认正确修订的事实抵达执行器输入，本地策略仍禁止云端调用。
这些结果说明当前 0.5B 配置尚未满足召回门槛；不能据此认定全部问题都属于模型能力，
更不能以“降低温度”作为已经验证的修复。不得通过重跑直到偶然成功来通过验收。
1.5B 的受热阻断实验不参与质量优劣比较。随后三次同配置四阶段均通过，未作诊断温度覆盖，
也没有云调用。这支持将现有 1.5B 作为下一轮质量评测候选，但三个相同场景样本不足以
证明通用召回、人格或抗注入质量。轮次间降温不代表持续负载稳定性达标。
本轮没有自动切换生产模型、放松热准入或修改全局采样。

## M5 固定集与剩余门槛

[评测工具](../../tools/persona-eval/README.md) 包含 40 条合成中文用例：
dev 28 / heldout 12；确定性契约 26 / 主观用例 14。

```text
Ran 32 tests
OK
reference --split all: pass = 26
application_conformance = not_run
llm_quality = not_run
```

上述 Python 检查在 Mac 和 Windows 均通过，只验证工具及参考契约。
新增 CI job 尚未远程触发。真实 MNN 四阶段 smoke 与固定集是不同的证据轨道。

Android adapter 仍待实现。契约对接前必须明确：

- 当前手写保存入口本身就是用户确认，没有自动候选的 confirmed/provenance API。
  未点击保存不能伪称生产拒写；来源消息状态/owner/scope 的拒写已有实际测试。
- fixture 时间及 ID 需映射到真实查询时钟、快照和稳定排序；不能从 expected 生成观测。
- 当前支持授权的本地失败转云，未实现云失败自动转本地。
  `heldout-07` 的参考执行器切换不能冒充已经支持的产品恢复策略。
- candidates、注入和拒写须分别在真实边界取证；取消、修改和 resend 走实际生命周期。

下一步以现有 1.5B 和 v4 上下文作为候选基线完成开发集对照，同时保留 0.5B 失败回归。
保留集仅在配置冻结后运行，14 条主观用例仍需人工成对评审。P2 继续进行，暂不进入自动记忆、
Embedding 或 Agent，也不把一次通过作为发布依据。

## 证据与安全范围

本地忽略目录 `build/verification/p2-memory-windows/` 保存源码 SHA-256、Windows 日志、
JUnit XML、模型工件信息、合成运行输出及交接状态。各次失败和热阻断日志均保留。
最终源码清单 `full-source-final-sha256.json` 绑定本轮源文件；
`source-build-verification.json` 记录逐文件比对、构建退出码、JUnit 统计和 APK SHA-256。
`p2-memory-evidence-2026-10-29.tar.gz` 归档这些记录、完整测试输出、合成运行工件及历史快照；
归档哈希和最后交接状态另保存在同目录 `resume-state.json`，不将本地证据上传为遥测。

本轮只安装 `com.example.persona.validation` 和 `.validation.test`，取证后均已卸载。
原应用更新时间仍为 `2026-09-20 21:58:52`。未操作原应用数据库、
真实账号或 DeepSeek。Windows 原工程保持 `803388f` 且干净，原模型源文件不修改。

Sources: [Room 迁移](https://developer.android.com/training/data-storage/room/migrating-db-versions)、
[Room 异步查询](https://developer.android.com/training/data-storage/room/async-queries)。
实际接口沿用项目 Room 2.6.1。
