# P3 Mac 实现与检查记录

本页为 Mac 阶段历史记录。后续 Windows、手机和模型质量结果见
[P3 集中验收](p3-status-2026-11-05.md)。

日期为 2026-11-05，工作分支为 `codex/p3-memory`，基于 P2 的 `965f69d`。
P2 依靠手动保存与最近记忆选择。P3 补齐候选确认、相关性选择和来源失效处理，
将模型提取的效果与工程检查分别记录。

## 交付范围

- 默认关闭候选功能，用户可启用明确“记住”表达与手选消息入口。
- 候选支持来源查看、编辑、逐条确认、忽略、共享范围、独立云端使用授权和到期日期。
- 显示可能相关的已有记忆，用户明确选择新建或按 ID/revision 更新。
- Room v9 增加候选、账号偏好和摘要多来源表，包含 v8→v9 迁移。
- 候选具有稳定身份和来源内容哈希，确认与正式保存处于同一事务。
- 原消息变化、删除、账号变化和提取授权变化使相关任务失效。
- 在途任务不能恢复刚被忽略的候选，主动重新选择来源可以明确重开。
- 云端提取需单独授权及主动点击，本地专用原消息会被拦截。
- 现有 Hybrid 路径执行模型提取，低优先级准入先于路由准备，聊天可取消提取并等待清理。
- 检索按实际问题做中文关键词排序，维持四条与 1,200 字符注入预算，发送前复核。
- 摘要采用最多 400 字符的原文摘录，保留全部消息引用与哈希，来源变化使摘要失效。

摘要保持当前 Persona 内本地使用。到期日期采用所填日期的 UTC 零点，界面已说明。
本地模型候选记录模型 ID/version 与请求参数。云端记录代理路径，服务端实际模型身份
尚未由当前响应协议提供，不能据客户端配置推定实际云端版本。

## Mac 检查范围

使用 JDK 17、Kotlin 2.0.21 与已有 JUnit/AndroidX 缓存，实际编译选定业务源码并运行测试。
仓库协调、Auth 与 ViewModel 测试保留明确的 framework boundary。Room DAO 注解与类型
经过本机编译，Room 生成代码、Android Activity 类型和 JNI 行为留待 Windows 验证。

执行命令与逐项结果保存在本地忽略目录 `build/verification/p3-mac/`。
最终汇总以 `summary.json` 为准，保留 `release/checks.json` 的原始结果与聊天测试签名
修正后的独立重跑日志。各已编译与执行的源码哈希已与最终工作区核对。

```text
Chat/context                         OK (36 tests)
Room repositories/mapper             OK (31 tests)
Hybrid                               OK (47 tests)
Switching                            OK (24 tests)
Feed/Discovery/Profile/validator     OK (39 tests)
Creation                             OK (24 tests)
Auth                                 OK (15 tests)
Priority gate/keyword ranker          OK (11 tests)
Memory/curation ViewModels           OK (59 tests)
Extraction/extractive summary        OK (27 tests)
Host SQLite                          Ran 16 tests, OK
Evaluation tools                     Ran 94 tests, OK
Model packager                       Ran 15 tests, OK
Backend                              typecheck passed; tests 22, pass 22
Kotlin grammar                       PASS (42 files; no Android type checking)
```

上述 Kotlin/JVM 测试合计 313 项，属于明确范围的 standalone 检查。
早期缺少 auth 测试的 OkHttp classpath，以及移除临时参数后遗留的测试调用签名，
均已修正并重跑，原失败日志保留。

## 固定样本对照

| 检索策略 | 完全匹配预期的案例数 |
| --- | --- |
| 最近记录基线 | 3/9 |
| 实际 Kotlin 关键词排序 | 9/9 |
| host SQLite FTS4 原始查询 | 4/9 |
| host FTS4 使用 Kotlin 分词 | 9/9 |

报告保留每例预热 200 次、采样 1,000 次的 host 耗时分布。样本仅有 1～5 个可见候选，
这些数据限定于固定 host 样本。真实设备的内存、冷启动与规模性能另行测量。
规则、手选和严格模型 JSON 解析的 24 条合成契约已执行，真实 MNN 观测模板保持 unrun。
关键词基线在本轮样本上满足预期，Embedding、后台持续整理及大规模索引保持条件式待办。

## 审查发现与回归

确认保存改为复用已有锁内写入逻辑，避免重复获取账号写锁。审查又修复提取准入晚于
路由准备、在途结果重开刚被忽略的候选，以及删除无关资料导致候选失效的问题。
新增 Hybrid 与实际迁移 SQL 回归覆盖对应路径，失败和早期运行日志保留。
候选界面状态恢复、账号切换、取消、冲突选择和到期编辑均有 JVM 回归。

## Windows 与设备交接

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest assembleDebugAndroidTest
.\gradlew.bat -Ppersona.isolatedValidation=true assembleDebug assembleDebugAndroidTest
```

使用隔离包 `com.example.persona.validation`，先确认该包没有需要保留的数据。
P3 设备类为 `data.local.MemoryCurationInstrumentedTest` 与
`data.local.MemoryCurationFlowInstrumentedTest`，另外重跑现有
`MemoryMigrationInstrumentedTest`、`MemoryDaoInstrumentedTest` 和 P2 聊天生命周期回归。
Flow 用例验证真实 Room、确认锁、幂等、来源失效、独立授权及摘要多来源。

还需补齐候选界面操作与重启、真实 MNN 提取、云端撤回、聊天抢占、热状态与内存测试。
设备环境准备后集中执行。当前 Mac 离线 Android 构建遇到缺少 AGP 8.13.2 缓存，
Activity 的类型检查与视觉验收、完整 Gradle 单测及 P3 远程 CI 均为待验收。

## 关联

- [P3 待办](../p3-todo.md)
- [检索、提取与存储工具](../../tools/persona-memory-benchmark/README.md)
