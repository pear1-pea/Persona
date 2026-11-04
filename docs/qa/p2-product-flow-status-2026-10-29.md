# P2-A product flow acceptance — 2026-10-29

创建/编辑后的列表可能一直显示旧数据，AI 资料缺少严格校验，旋转聊天页面会停止生成，
已完成回复还可能被迟到取消改写为 STOPPED。本轮修复产品流程与恢复边界，
属于 P2 首批交付，不代表显式记忆或整个 P2 已完成。

## P1 closure

[PR #5](https://github.com/pear1-pea/Persona/pull/5) 已合入 master，merge commit
`911981a22b81cd63b93fe10ffcd09cfd31b6f5aa`。
Review 补充修复 `d3825de`：已加载模型不重复按冷加载权重估算剩余内存；
执行前准入失败按授权回退，正在使用的会话仍拒绝被替换；Native allocation failure
保留 OutOfMemoryError，模糊加载失败不永久标记为模型损坏。

Windows 296 项单测和完整构建通过；双模型生命周期/Native sampler 真机复测
`OK (2 tests)`，95.386 s。三个新回归先在旧实现失败，再在修复后通过。
[PR CI](https://github.com/pear1-pea/Persona/actions/runs/40843197477) 与
[合入后主分支 CI](https://github.com/pear1-pea/Persona/actions/runs/40844780453) 均成功。
自动流水线中的 Windows 手动 job 按设计 skipped，不能与实际 Windows 本地构建混称。

## P2 source and changes

- 工作区：`/Users/pear/Developer/Persona`，分支 `codex/p2-product-flow`。
- Base 为上述 master merge commit；P2 改动尚未提交或推送，没有 P2 PR/远程 CI 结果。
- Windows 候选：`C:\Users\pear\persona-m1-10c9727`；使用单独验证包。
- 30 个 P2 源码、资源和测试文件在 Mac/Windows 逐项 SHA-256 一致。
- JBR 21.0.8、SDK 36、NDK 27.0.11718014；MNN pinned revision 保持不变。
- 真机仍为 BLK-AL80 / arm64-v8a / SDK 31，支持范围见 [P1 记录](p1-status-2026-10-28.md)。

列表在恢复显示时刷新，保留搜索/筛选，按身份和请求版本丢弃迟到结果。
Discovery 分类来自实际 traits；Feed 展示真实 backstory 并去除虚构互动数字；
Profile 展示自己拥有的总数/公开/私有数量。离线公开列表包含自己的公开 Persona
和系统数据，登录后的远端失败 fallback 仍仅允许公共 seed，不合并账号数据源。

创建流程严格检查 JSON 字段、类型、重复键、空值和长度；最多一次受控重试，
两次生成共用超时与固定身份。无效资料不保存，重复操作被拦截；草稿与界面操作状态
保留在 ViewModel/SavedStateHandle，恢复和错误显示受页面生命周期约束。

聊天对同一身份/Persona 的重复加载保持幂等，Activity 销毁不再直接停止保留中的
ViewModel。旋转时输入与本地限定选项可恢复，并保存跟随最新消息的选择；内存流式显示按约 100 ms
节流，Room 约 750 ms 检查点，终态立即提交。终态提交与状态登记不可被取消拆开，
身份检查仍保留；迟到取消不覆盖已完成回复。

增量重复检测限制最近 2048 字符，完成时再做一次全文检查。既有数据库 onOpen
恢复机制继续使用，本轮仅开放内部测试入口并验证真实磁盘重开；Schema 仍为 v7。

## Verification

```text
BUILD SUCCESSFUL in 38s
93 actionable tasks: 21 executed, 72 up-to-date
TESTS=356 FAILURES=0 ERRORS=0 SKIPPED=0
Time: 5.213
OK (6 tests)
```

命令：`gradlew.bat '-Ppersona.isolatedValidation=true' testDebugUnitTest assembleDebug assembleDebugAndroidTest`。
先运行的 Chat/Switching 定向测试复现 3 项失败；长句重复的定向 JVM 测试另复现
1 项失败。最终 Windows 全套单测覆盖修复结果。

六项真机用例包括：磁盘重开保留部分回复与其他终态、思考占位恢复的幂等性、
同一已打开数据库读取不打断活跃记录、创建草稿旋转后单次保存、聊天草稿/隐私选项
旋转恢复，以及新流式显示不被旧检查点覆盖。
显示用例通过独立 Room fixture 写入并显式刷新 Paging，不冒充真实模型输出或帧率测试。

另执行跨进程两阶段用例：prepare 写入部分回复和思考记录 → host force-stop
隔离应用 → verify 通过真实 ChatActivity/Hilt/数据库 onOpen 路径加载并检查恢复。
两阶段均 `OK (1 test)`；这是同一跨进程场景的两个步骤，不计为两个独立用户能力。
部分文本保留，状态为 STOPPED，没有自动续跑或再次发送请求。

测试初次发现的 JUnit 返回类型问题已修正；失败日志与最终结果均保留。
独立只读 Review 未发现本批阻断问题；Review 本身不算测试。

## Evidence and cleanup

本机忽略目录 `build/verification/p2-windows/` 保存最终 30 文件源码包、哈希清单及
`p2-evidence-2026-10-29.tar.gz`，内含 Windows 构建、JUnit XML、真机阶段与失败日志、
APK 哈希、原工程/应用状态及清理结果。归档 SHA-256：
`e1102cace935ae00a4ec297071efd7babee448463520aec0766eb3d91ee65846`。
P1 Review 补验另存于 `build/verification/p1-windows/p1-review-evidence-2026-10-29.tar.gz`，
SHA-256 `ebfd614a6bf58a0e56e8e8656f503dae1ac093a0a8dd40514151279c42e4b632`。

最终验证 App APK SHA-256：
`33105a777a367cd04963b1455aae0a2f219cd8d9defcd6126ef80ff92912d152`。
Test APK SHA-256：
`718473d01f0ded3873d2a4e69d0163fbf78a56eb1cb924524c843563575cc846`。

测试仅安装 `com.example.persona.validation` 和 `.validation.test`，取证后均已卸载，
手机只保留原应用，原应用更新时间仍为 `2026-09-20 21:58:52`。
Windows 原 Android Studio checkout 仍为 `803388f` 且干净；两个原模型 manifest 哈希未变。
没有登录真实账号、调用真实 DeepSeek 或修改原用户数据库。

P1 审查 worktree 干净且已推送。它作为本聊天的主 checkout 被工具保护，因此保留；
P2 的实际未提交改动在本报告开头指定的 Developer 工作区，后续从那里继续。

## Remaining P2 work

本批未实现显式长期记忆、自动提取、Embedding 或向量检索。
下一批先补 M5 的固定中文人格/记忆评测集，再设计 M6 的账号/Persona 作用域、来源、
出端授权、修订与删除，完成手工保存 → 查看编辑 → 跨会话使用 → 删除不再召回。
Schema 变更与迁移在该阶段单独验证。

本轮证明隔离离线资料和聊天恢复路径；真实登录账号的完整 UI 流程、主观人格质量、
长文本/大列表帧率和性能对照仍需扩充。没有宣称 UI 节流已经带来测得的性能提升，
也没有重复宣称本地模型持续高负载热稳定性达标。

Sources: [Android 状态保存与恢复](https://developer.android.com/topic/libraries/architecture/saving-states)、
[PagingDataAdapter API](https://developer.android.com/reference/kotlin/androidx/paging/PagingDataAdapter)。
