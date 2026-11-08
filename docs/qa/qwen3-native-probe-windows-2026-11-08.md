# Qwen3 Android native 实验验收

日期：2026-11-08。基线 `master@1c1367a`，分支 `codex/qwen3-native-probe`。
问题：候选此前只有主机 smoke，缺少 Android 模板、实际生成、取消恢复和记忆提取证据。
本轮已完成独立真机实验，并修复发现的取消状态恢复缺陷；工程检查通过，固定六条提取质量不通过，候选暂不采用。

## 范围与结论

- 唯一候选：Qwen3-0.6B，固定 revision `c1899de289a04d12100db370d81485cdf75e47ca`；INT8 权重、BF16 embedding、CPU 两线程、non-thinking。
- 使用既有 Windows 独立 QA checkout；仅安装 `com.example.persona.validation` 和其 test 包。
- 直接调用真实 `NativeMnnSession`，绕过产品准入、路由、账号与记忆持久化。
- 三条 smoke 和六条提取均通过真实模板 IDs、输入计数、预算与非空生成检查；无思考或协议控制标记泄漏。
- callback 停止后 reset 和再次生成通过，最终 `engineering_status=passed`。
- 六条提取均被生产严格解析器拒绝，严格匹配 **0/6**，`quality_status=not_passed_fixed_six`。
- 未修改提取 prompt、解析器或评分标准；未换候选、量化、默认模型或生产 family registry。
- 不需要新增人工评审。该小集足以否决本次提取准入，不能推导通用聊天质量或相对旧模型的收益。

## 实际检查输出

Windows 使用 JDK 与 Gradle wrapper 的参数列表执行：

```text
org.gradle.wrapper.GradleWrapperMain -Ppersona.isolatedValidation=true
  testDebugUnitTest assembleDebug assembleDebugAndroidTest
  --no-daemon --console=plain

BUILD SUCCESSFUL in 19s
93 actionable tasks: 5 executed, 88 up-to-date

JUnit XML: tests=602 failures=0 errors=0 skipped=0

Qwen3NativeProbeInstrumentedTest
Time: 48.324
OK (1 test)

QWEN3_DRIVER_EXIT=0
engineering_status=passed
quality_status=not_passed_fixed_six
cleanup_errors=[]
```

602 项 JVM 在修复前的完整构建执行并全绿；最终仅 JNI 改动，Gradle 正确复用 JVM 结果并重编 JNI、打包。
首次完整构建为 1m20s，Android instrumentation 源码实际编译通过。
已有 Kapt 与 P3 instrumentation 泛型警告保留，没有把警告计为失败或另行扩大修改范围。

Mac 对最终源码另外执行：

```text
python3 -B -m unittest discover -s tools/model-candidate/tests -q
Ran 65 tests
OK

check_mac.py: debug compile exit=0; debug junit exit=0
OK (35 tests)
Actual Kotlin protocol accepted nine HF reference cases; native execution not run
release compile exit=0; release junit exit=0
OK (35 tests)
```

35 是不同 Kotlin 用例数，两种 BuildConfig 边界分别运行，不计为70个不同用例。
65 项 Python 是 Mac 合同检查；Windows 上实际运行的是完整 host driver 和真实 AAPT2/ADB/Gradle，不宣称 Windows 全部 Python 合同测试通过。

## 失败记录与修复

1. `q3-20261108-01`：Windows `lstat` 与 `fstat` 的已弃用 `ctime` 值不一致，预检拒绝。
   Windows identity stamp 改用 birthtime，保留 device/inode、size、mtime、完整 SHA 与前后检查；增加回归测试。
2. `q3-20261108-02/03`：ADB listener 不接受显式 hostname，以及冷启动连接超时。
   listener 使用 `tcp:5037`，客户端仍连 loopback；启动握手最多15秒重试，检查退出状态，增加启动回归测试。
3. `q3-20261108-04`：九条生成通过，但 callback 取消后 reset 恢复零输出，工程失败。
   固定 MNN 的 reset 不清除 `USER_CANCEL`；ChatMessages response 在 generate_init 前先检查状态。
   JNI 仅在持 session mutex 的新请求入口恢复旧取消状态，继续生成的 stepping 分支不清除取消。
   不恢复 TIMEOUT、INTERNAL_ERROR 或 NOT_LOADED。未通过改变采样器、重新加载模型或延长输出掩盖问题。
4. `q3-20261108-05`：重新生成绑定最终源码的 HF bundle，重新构建、安装与运行，工程通过。
   callback 一次、停止返回延迟0ms；reset 后再次生成4个输出 Tokens、4次 callback，TTFT337ms。
   原失败证据完整保留。

## 固定六条质量

| 场景 | 严格解析 | 匹配 |
| --- | --- | --- |
| 喜欢茶 | 拒绝 | 否 |
| 不喜欢咖啡 | 拒绝 | 否 |
| 马拉松目标 | 拒绝 | 否 |
| 回忆问句 | 拒绝 | 否 |
| 假设内容 | 拒绝 | 否 |
| 不喜欢茶 | 拒绝 | 否 |

合成输出出现 Markdown fence、错误 Schema/类型、遗漏或不完整结构；没有触及256-token输出上限。
解析失败的两个语义负例是 **unknown**，不能把统计值 `semantic_negative_false_extractions=0` 解读成负例安全通过。
不剥离 Markdown 后重算成绩；即便改变解析，已观察到的事实/偏好和假设语义问题仍需独立解决。

## 单次资源观测

- 设备 BLK-AL80，arm64，Android API31；电量48–49%，各采样点 thermal=NONE，无系统低内存和省电状态。
- 模型加载5035ms；首次 smoke TTFT5299ms，后两条709/488ms；六条提取 TTFT3485–3842ms。
- 提取输入213–218 Tokens；native prefill3477–3833ms，输入计数与HF参考一致。
- 进程采样 RSS 约955–978MiB，VmHWM 约1293MiB；这是进程观测，不是模型独立峰值。
- 非重复、多设备或五分钟基准；不据此宣称性能升级、长时间稳定性或相对旧模型加速。

## 绑定与来源边界

- 最终源快照：`sha256:cd6d6fbf203bc1b4a150491fc00ad44c7f5d36c634cdf19b609f77c9247f134c`。
- Bundle SHA256：`e8c5ad9132558d92ce7ca4782fabd8aaa115830a0cb0c8929e2acde290a5d9c2`。
- 完整11文件模型包949,972,727bytes，原样 receipt SHA256：`1775292d3ecaa87a0d776cc4e773a8452294b9b3a4fdd4e3b1f82ff413e39b32`。
- App APK SHA256：`8529ae02a6748c22c4ead1b2b14735b7a2634874ba4395103d1ac2c1ec25c802`；安装后哈希一致。
- 打包 JNI SHA256：`5e2f051effeb305b7c781e78a1c813c6cfb4e2b86b3c50780df061be943d028b`，与 stripped build 输出一致。
- MNN 实际源码目录与固定官方 commit 内容等价，差异仅 Windows 换行；CMake graph 未使用主机研究目录或 fallback。
- 本次复用既有 `libMNN.a`，历史 object 输入未重新证明。保留 `runtime_source_verified=false / declared_target_only`，不把源码核验提升为完整二进制供应链证明；模型分发签名也未验收。

私有证据根：`build/verification/qwen3-windows-20261108/`。
最终结果在其 `build/verification/qwen3-device-20261108-05/`，包括 summary、observations、构建/instrumentation、APK、传输及清理日志。
完整合成原文只在 ignored 产物，不提交 Prompt/Response；最终参考在 `build/verification/qwen3-mac-20261108/ready-cancel-fix/`。

## 清理与后续

QA两个包、私有模型副本与本轮 ADB 临时文件已清理；原 `com.example.persona` 保留。
原 Windows checkout HEAD、工作区与 local.properties/backend/.env 哈希前后一致；本轮自有 ADB 与 SSH 连接已停止。
源码来源采集曾误序列化 PowerShell 元数据，已确认并停止该自有采集进程，排除其未完成输出，采用独立完成的精简证据。

以上为本轮本地实验收尾记录；记录时代码未提交/推送。后续提交、远程 CI 和合入状态以 Git 与 PR Checks 为准。
Qwen3 暂不开放生产准入、默认模型或记忆提取。保留现有模型与明确确认的记忆工作流；未来有明确质量收益方案时再另开实验，不自动继续调参。

Sources:
- [固定 MNN reset 与 response 实现](https://github.com/alibaba/MNN/blob/d407447ed56c4121a11ccbd266dc184ca1ead0c2/transformers/llm/engine/src/llm.cpp)
- [Python Windows stat 时间语义](https://docs.python.org/3.14/library/os.html#os.stat_result.st_ctime_ns)
- [Mac 准备与历史边界](qwen3-native-probe-mac-2026-11-08.md)
