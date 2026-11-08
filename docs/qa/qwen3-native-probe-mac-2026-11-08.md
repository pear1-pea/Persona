# Qwen3 Android native 探针：Mac 准备回执

日期：2026-11-08。基线 `master@1c1367a`，工作分支 `codex/qwen3-native-probe`。
问题：Windows 已验证候选主机模板，但 Android JNI 缺少只读模板 Token IDs 检查口，尚不能证明手机实际输入与 HF 参考一致。
本轮完成 Mac 可做的探针、固定输入、包合同和后续执行工具；Android 构建、真实推理、资源与质量结果仍待 Windows/手机验收。

## 已完成范围

- `NativeMnnSession.inspectChatPromptForVerification` 仅 Debug 可调用；校验角色、消息数与 UTF-16 上限，持生命周期读锁。
- JNI 在 session mutex 内执行真实 `apply_chat_template → tokenizer_encode`，最多返回 32768 IDs；不调用 generation/reset/sampler，不新增内容日志。
- 独立 QA 探针对照实际 IDs、HF 文本计数和 4096-token 实验预算；覆盖三条 smoke、固定六条提取与 callback 停止/reset 恢复。
- 提取判定使用生产 `MemoryExtraction.decode`，区分工程、固定样本质量、未完成执行；每条原始合成输出仅保留在私有产物。
- 资源检查包括电量、热状态、省电与系统低内存标志；准备记录加载、TTFT、native 时序、可用 RAM、native PSS、进程 RSS/VmHWM。
- 包工具固定来源和 receipt，验证完整性、文件路径与配置；Windows host driver 另有 portable 预检、APK 实际包名校验、provision、超时取证和 owned 清理。
- App 的 family registry、模型安装准入、默认模型、路由和记忆授权未修改。探针是独立 native 研究路径。

## 实际 Mac 结果

| 检查 | 结果与范围 |
| --- | --- |
| Python 合同 | 63 项通过；包、参考生成与 host driver 前置边界，外部 aapt2 使用测试替身 |
| Kotlin 合同与 Debug gate | 35 项不同用例，Debug/Release 两个 BuildConfig 边界配置各通过一次 |
| HF → Kotlin 实际对接 | 九条真实 tokenizer 参考被实际 Kotlin 协议接受 |
| HF 源 snapshot | 十文件全量 size/SHA-256 与固定锁一致；Git blob/LFS 身份一致 |
| C++ 语法 | 完整 JNI translation unit 对固定 MNN headers、JDK17 JNI 执行 clang++ `-fsyntax-only` 通过 |
| Kotlin grammar | 15 文件通过；不是 Android 类型编译 |
| Gradle Android | 默认 Java 环境未配置；显式 JDK17 后离线 AGP 8.13.2 插件未解析，构建失败；本机也未配置 Android SDK |
| Android/JNI/候选质量 | 未运行，候选未采用 |

```text
python3 -B -m unittest discover -s tools/model-candidate/tests -v
Ran 63 tests / OK

python3 tools/model-candidate/check_mac.py <explicit JDK/deps/bundle/output>
debug junit: OK (35 tests)
Actual Kotlin protocol accepted nine HF reference cases; native execution not run
release junit: OK (35 tests)

clang++ -std=c++17 -fsyntax-only <pinned headers> app/src/main/cpp/mnn_jni.cpp
exit=0
```

Kotlin 检查使用明确的 `BuildConfig.DEBUG` stub，其余选定源码为实际实现。
C++ 检查使用 Apple Clang/JDK JNI，不是 NDK 编译或链接。aapt2、ADB、Gradle 与手机执行的 host driver 尚未实跑。
新 Python 合同检查已加入 CI workflow，但本轮未提交或触发远程 CI。

## 固定参考与预算

生成器编译实际 `MemoryExtraction.prompt`、严格解析器和 `ContextPlanner.renderForPlanning`，没有另抄提取 prompt。
参考环境为独立 Python 3.14 venv、Transformers 4.51.3、tokenizers 0.21.4、Jinja2 3.1.6；只加载本地 tokenizer，无模型执行框架。
`enable_thinking=False`、generation prefix 和无额外 special tokens 的两种编码路径相互校验。

| 输入 | 实际 HF 输入 Tokens | 当前规划文本少算 |
| --- | --- | --- |
| smoke-single | 21 | 4 |
| smoke-multi | 37 | 4 |
| smoke-think-input | 26 | 4 |
| explicit-tea | 214 | 4 |
| explicit-negation | 216 | 4 |
| explicit-goal | 218 | 4 |
| recall-question | 215 | 4 |
| fiction | 214 | 4 |
| conflicting-preference | 213 | 4 |

九条输入均满足真实参考长度 + 输出预留（24/256）+ 56 <=4096。四 Token 差异在当前固定样本预留内，
并未据此开放生产 Qwen3 或宣称长上下文规划正确。4096 是保守实验预算，不是模型最大上下文。

## 交接物与后续验收

私有输出根目录：`build/verification/qwen3-mac-20261108/`。
最终参考目录 `ready-handoff/` 包含 `bundle.json`、原样 `receipt.json`、计数摘要、编译源码与依赖哈希。

- Android 源快照（不是 commit）：`sha256:0b11cfe5140137bb68b41db55945200fd93ee23cb84f0ea8a1535f1e1042a060`。
- Bundle SHA-256：`574ed419f36985561d5e1d661b84e8e31e8a30df8b2fed30cb7ee09af0638a72`。
- 固定包 receipt SHA-256：`1775292d3ecaa87a0d776cc4e773a8452294b9b3a4fdd4e3b1f82ff413e39b32`。

本机没有对应 949,972,727-byte 完整 MNN 包，因此参考报告明确标记
`receipt_only_model_files_not_checked`；63 项 Python 测试中的完整包夹具为合成文件。
实际候选包仍保留在上一轮 Windows 独立目录，后续执行前必须重新核对完整包和传输哈希。

后续使用[工具入口](../../tools/model-candidate/README.md)，先同步精确源快照，在 Windows 完整构建与运行单测，
再执行隔离 native 探针，检查实际模板 IDs、生成、取消恢复、固定六条质量和资源。
source、bundle、receipt 或 APK 不匹配会拦截。原包 ID `com.example.persona` 不参与安装；只操作成功安装的两个 QA 包。
JNI SO 哈希可记录，实际 MNN CMake source/override 仍须在 Windows 单独核验，不能把声明的 runtime revision 当成二进制证明。

实验工程成功与质量门槛分别报告。六条工程未完成时为 `not_evaluated_incomplete`；六条完成且全部严格匹配、
负例无误提取、未触及 token cap，才是 `passed_fixed_six`。该小集不代表通用回答质量，也不自动批准 App 接入或模型切换。
无需增加人工评分。结果不合格时完整保留，不自动改 prompt、换量化或切换候选。

本轮未连接 Windows 或手机，未提交、推送或合入；生产模型选择未改变。
最初 18 项协议检查、review 发现缺口后的 red/green，以及失败 reference preview 目录均保留，没有覆盖失败证据。
后续 Android 构建与真机验收以新回执记录，不从 Mac 检查推断通过。

## Sources

- [固定模型卡与 non-thinking 接口](https://huggingface.co/Qwen/Qwen3-0.6B/blob/c1899de289a04d12100db370d81485cdf75e47ca/README.md)
- [固定 MNN LLM API](https://github.com/alibaba/MNN/blob/d407447ed56c4121a11ccbd266dc184ca1ead0c2/transformers/llm/engine/include/llm/llm.hpp)
- [Transformers 4.51.3 模板实现](https://github.com/huggingface/transformers/blob/v4.51.3/src/transformers/utils/chat_template_utils.py)
- [AAPT2 manifest 包名查询](https://developer.android.com/tools/aapt2)
