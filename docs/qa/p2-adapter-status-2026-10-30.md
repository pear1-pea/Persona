# P2 组件评测接入：Mac 候选 — 2026-10-30

此前固定评测只有独立参考算法，无法检查实际 Room 查询、预算和 Prompt 组装。
本轮补齐组件 adapter、输入快照、设备运行脚本和不完整结果协议，完成 Mac 可执行验证。
未连接 Windows 或手机，未运行真实 Android 构建、Room processor 或模型评测，P2 尚未完成。

本记录保留 Mac 阶段状态；同日后续连接 Windows/手机的实测、导出修复与 v2 结果见
[Windows/设备验收](p2-adapter-windows-status-2026-10-30.md)。

## 候选范围

工作区 `/Users/pear/Developer/Persona`，分支 `codex/p2-product-flow`，base 为
`911981a22b81cd63b93fe10ffcd09cfd31b6f5aa`。P2 既有改动和本轮新增仍未提交、推送。
本轮没有修改生产业务代码、数据库 Schema、采样默认值或模型选择。
[上一批 Windows/真机结果](p2-memory-status-2026-10-29.md) 保留为历史证据，
其中 408 项 JVM/24 项设备用例不代表本轮新增 adapter 已经通过 Android 验收。

- `prepare_device.py` 从固定集合准备输入，保留原始 memories/events，去掉 expected，
  不包含主观案例与 rubric。对 main/test/androidTest 和 Gradle 配置生成源码摘要。
- `M5RoomAdapterInstrumentedTest` 只允许显式 opt-in 和独立验证包，使用独立内存 Room
  与命名隔离的合成会话 preferences，默认不访问已有会话、模型或网络。
- candidates 来自实际 MemoryDao；injected 来自实际 RoomMemoryRepository，并核对
  MemoryPrompt JSON 输出。这里的注入仅指组件输出，不证明最终派发或 LLM 遵循。
- 14 个静态案例可映射，12 个请求生命周期/自动来源入口案例整例 unsupported。
  这是输入能力映射数量，尚无设备端通过数量；不复制 oracle 过滤后伪造拒写。
- compare 要求所有案例仍在输出中，unsupported 须附原因且 actual=null，退出 1；
  not_run、漏案例、畸形观测继续拒绝。完整人格与记忆质量仍需独立模型和人工评审。

`run_device.py` 为后续 Windows 集中验收准备：先构建并核对前后源码，再校验 APK metadata，
安装两个隔离包、执行 adapter、绑定实际安装 APK 哈希、比较并清理本次安装。
已有任一验证包时拒绝覆盖；安装不明确时不取得删除权限，不操作原应用或杀死 ADB 服务。
源码/APK复核、adapter 执行和 compare 结果分别记录，清理失败总退出非零。
命令和范围详见 [DEVICE_ADAPTER.md](../../tools/persona-eval/DEVICE_ADAPTER.md)。

## Mac 验证结果

Python 完整集合包含原有 32 项及本轮 22 项工具/编排回归。真实输出：

```text
python3 -m unittest discover -s tools/persona-eval/tests -v
Ran 54 tests
OK
```

Kotlin 使用项目版本 2.0.21、JDK 17、target 11，直接编译 adapter 输入协议及其测试：

```text
JUnit version 4.13.2
........
Time: 0.16
OK (8 tests)
```

覆盖 bundle/revision/source snapshot 绑定、UTF-8 跨语言排序、路径边界、参考答案混入、
重复案例、owner 物理 ID、相对时间和未接入生命周期标记。源码清单变更未使旧 revision
失效的回归先红后绿；JVM 测试源码未进入快照的问题也先复现再修复。
设备脚本的模拟测试覆盖已安装包保护、源码漂移、原包名拒绝、安装所有权、APK 导出不匹配、
ADB 启动输出、stop 超时后的卸载、清理失败，以及安装成功后日志写入失败仍须登记归属并清理。
独立 Review 发现的该安装日志回归已先红后绿。模拟子进程测试不是设备证据。

另用真实 Room 2.6.1、SQLite 2.4.0、AndroidX Test JUnit/monitor 类库完成组件类型检查：

```text
CLASSPATH_TYPECHECK_EXIT=0
```

此检查使用 Android 存储 SessionTokenStore 和 DI callback 的明确编译替身；
没有运行 Room processor、APK 打包或 Android 进程，不能称为 Android 构建通过。

实际 prepare_device 生成的三种 bundle 都由 Kotlin 解析器读取并复核摘要：

```text
HOST_INPUT_MAPPING_ONLY split=dev ready=11 unsupported=7 device_execution=false
HOST_INPUT_MAPPING_ONLY split=heldout ready=3 unsupported=5 device_execution=false
HOST_INPUT_MAPPING_ONLY split=all ready=14 unsupported=12 device_execution=false
```

这里只读取确定性 fixture 的输入结构，没有运行 heldout 模型，也没有按保留集输出调参。
数据集 SHA-256 未变，schema 检查通过；26 项 reference 继续通过，
application_conformance 和 llm_quality 均为 not_run。

## 交接与待验收

日志、失败记录、输入桥接、依赖和源码 SHA-256 存于忽略目录
`build/verification/p2-adapter-mac/`。最终状态及证据清单见其中 `state.json`。
本轮不覆盖上一批 Windows 的源码清单或成功/失败归档。

下一次连接 Windows/设备后先跑 dev 组件验收，验证实际 Room/安装/导出路径；
即使 11 个静态 dev 案例全通过，也会因 7 个 unsupported 返回 incomplete。
随后逐步接入真实请求生命周期边界；没有对应产品入口的参考场景继续保留为能力缺口。
1.5B 的扩大开发集、配置冻结后的模型保留集和人工人格评审另行执行。

Sources: [Room 数据库测试](https://developer.android.com/training/data-storage/room/testing-db)、
[AndroidJUnitRunner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)。
