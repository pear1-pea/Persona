# 记忆组件与请求生命周期评测接入

此前只有 Python 参考契约，不能证明应用的 SQL、预算或 Prompt 行为。
`room-memory-boundary-v2` 在 Android instrumentation 中调用实际 Room、AuthManager、
RoomMemoryRepository 和 MemoryPrompt，导出引用列表交给 compare。Windows 构建与设备
组件检查已完成，模型未运行，应用完整链路仍不完整。

当前代码版本为 `room-and-lifecycle-boundary-v4`。以下 14/12 表格保留默认静态组件
范围；显式生命周期模式的设备结果为 20/6，见末尾命令与
[生命周期验收](../../docs/qa/p2-lifecycle-status-2026-10-30.md)。

## 可观测范围

| Split | 可映射静态案例 | unsupported | 总数 |
| --- | ---: | ---: | ---: |
| dev | 11 | 7 | 18 |
| heldout | 3 | 5 | 8 |
| all | 14 | 12 | 26 |

可映射包括 owner/Persona/云端过滤、共享、时效、排序和预算。
组件输入直接种入独立内存 Room；它不证明写入入口、ChatViewModel、取消、fallback、
最终执行器或 LLM 质量。请求事件整例 unsupported；未确认/非用户来源的自动候选也
没有对应生产入口，不能把“不调用保存”报告成“生产拒写”。

2026-10-30 设备实测 dev 11 项通过、7 项 unsupported；all 14 项通过、12 项 unsupported，
没有不匹配。两轮重叠不相加，完整结果见
[Windows 验收](../../docs/qa/p2-adapter-windows-status-2026-10-30.md)。
14 条主观模型案例不进入此 adapter。

首次 v1 导出因 Gson 省略 actual=null 被比较工具拒绝；v2 显式 serializeNulls，
已有协议回归和真实设备证据，不接受缺失字段作为 null 的宽松替代。

## Mac 离线准备

从仓库根目录执行；输出必须是不存在的新文件，父目录先建好：

```sh
python3 tools/persona-eval/prepare_device.py --split dev \
  --output build/verification/m5-room-dev-input.json
python3 -m unittest discover -s tools/persona-eval/tests -v
```

bundle 保留原始未过滤 memories/events，剥离 expected，不包含主观案例或 rubric。
源码快照覆盖 app/src/main、app/src/test、app/src/androidTest 及五个 Gradle 配置文件，
包含未跟踪源码，拒绝软链。每个相对 POSIX 路径按 UTF-8 字节排序，向 SHA-256 依次输入
`路径 UTF-8 + NUL + 文件 SHA-256 原始 32 字节`，生成 implementation_revision。
bundle 完整字节另计算 SHA-256；两者由 Android 端复核。源码摘要本身不证明 APK 来源。

fixture 固定时间按相对 as_of 的差值平移到设备当前时间，不修改系统时间；
到期右边界仍按已过期处理。物理 ID 保持 fixture 的 UTF-16 排序并隔离 owner。
所有账号信息都是独立测试 preferences 中的合成会话，不访问原应用会话。

## Windows 集中验收

本步骤届时需要 Windows Android 环境及已授权设备；不需要模型文件或 DeepSeek。
在同步完整候选后运行，serial 以届时 `adb devices` 为准：

```powershell
python tools/persona-eval/run_device.py --serial 4ACVB24C10039864 --run-id m5-dev-20261030-01
```

脚本默认 dev。只有明确扩大确定性契约检查时才传 `--split all`；这不会运行 heldout 模型调优。
脚本创建新的 `build/verification/m5-room-<run-id>/`，已有目录拒绝覆盖，依次执行：

1. 检查设备授权及两个隔离包均未预先安装；有任一个就拒绝，不覆盖其他测试会话。
2. 固化输入，运行隔离 Debug 构建、JVM 单测和测试 APK 构建，保存完整输出。
3. 检查构建前后源码一致、APK metadata 包名准确，计算两个 APK 哈希。
4. 仅安装 `com.example.persona.validation` 与 `.validation.test`，在私有目录部署 bundle。
5. 显式 opt-in 执行 `M5RoomAdapterInstrumentedTest`，导出 JSON，核对输入、源码标签和安装 APK 哈希。
6. compare 后记录结果，只清理本次明确安装成功的包和自己的临时文件，不停止原应用或 ADB 服务。

JUnit `OK (1 test)` 仅表示 adapter 导出完成；不代表所有契约通过。
当前能力下，即使静态案例全通过，仍会因 unsupported 返回 1，summary 标为 incomplete。
构建/输入/安装/导出或清理失败返回 2；日志与 summary 分别记录各阶段，不重跑至偶然通过。
安装结果不明确时不擅自卸载未知包，需根据 install 日志人工确认状态。

目录保留 bundle、build-command/build.log、安装和 instrumentation 日志、
observations.json、comparison.json、summary.json。设备导出记录实际安装 APK 的 SHA-256，
host receipt 绑定构建前后快照和工具摘要；完整 Android/Room processor 验收仍以真实运行日志为准。
控制台只输出摘要，不打印合成问题正文、完整 Prompt/回复或凭据。

## 显式请求生命周期与热点测试

```powershell
python tools/persona-eval/run_device.py --serial 4ACVB24C10039864 --run-id m5-life-all-20261030-01 --split all --with-lifecycle --controlled-network
```

实际重跑需换新的 run-id。`--with-lifecycle` 使用真实 Room、AuthManager、ChatViewModel、
HybridRouter、Hybrid 和云端上下文规划，只替换引擎与 CloudChatApi；不需要真实模型包。
等待点在实际用户消息保存之后，取消必须使原 request job 退出，resend 必须正常落库。

流量热点可以继续用于 SSH。生产计费网络禁止自动回退，测试可以显式用
`--controlled-network` 注入网络策略输入，同时强制执行计费拒绝对照。
报告分别记录 physicalNetworkState 与 policyNetworkState，云传输始终在内存中。
没有此参数时使用真实网络，计费网络下回退案例会 unsupported，不会绕过产品策略。

v4 实测 dev 16/2、all 20/6（pass/unsupported），零不匹配；原来的 14 个静态案例仍是组件观测。
五项自动来源入口与 cloud→local 未实现，comparison 仍为 incomplete、退出 1。
失败的 instrumentation 也会先尝试保存私有 observations，再清理本次验证包。

Sources: [Room 数据库测试](https://developer.android.com/training/data-storage/room/testing-db)、
[AndroidJUnitRunner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)。
使用仓库已有 Room 2.6.1 API；不采用新版文档的 Room KMP/SQLite driver 接口。
