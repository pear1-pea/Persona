# P4 单候选模型兼容性研究与 QA — 2026-11-05

此页保留初始研究/实施时的历史状态。最新集中验收见 [2026-11-08 回执](p4-status-2026-11-08.md)。

现有本地模型在十二次固定提取中暴露严格 JSON/来源解析失败，以及提问、虚构被当成用户事实的问题。本研究只回答一个替代候选是否值得进入当前 MNN runtime 的独立验证，不判定新模型更好，不改变默认模型或记忆写入权限。

## 研究结论

- 唯一最终研究候选：**`Qwen/Qwen3-0.6B`，non-thinking 模式，MNN INT8 包**。官方仓库名没有 `-Instruct` 后缀；这是经过 post-training 的对话模型，不选 `Qwen3-0.6B-Base`。[1]
- 保留现有 **Qwen2.5-0.5B-Instruct / Qwen2.5-1.5B-Instruct** 两个 baseline。候选目标是验证中文指令与提取可靠性是否改善，参数规模、官方宣传或成功加载都不能证明改善。
- **可以保留 MNN 3.6.1，继续独立 runtime 验证；当前 App 导入与端到端验收尚不可直接继续。** 固定源码具备 Qwen3 导出、tokenizer 和 Jinja context 通道；项目仍有 family、prompt 与打包契约缺口。现成 M1/P3 测试同样经过这些门禁，不能通过换包名直接复用。
- 暂无必须升级 MNN 的源码依据；“不需升级”仍待真实导出/加载确认。如果发现固定版 converter、算子或模板实现缺陷确需升级，另轮处理 MNN revision 和回归，不与候选接入混改。
- 这是研究结果，不是采用批准。后续接入由隔离验证结果决定；提取出的每条候选仍须用户明确确认才能成为正式记忆，不要求用户新增人工评分。

## 已核对的本地事实

本轮所有仓库命令均显式使用工作目录 `/Users/pear/Developer/Persona`，未在旧 worktree 操作。研究起点仓库 HEAD 为 `4b4801ee2c12942def6bca83c0bc17ec60d47bb0`，初始 `git status --short` 无输出。

```text
$ git ls-tree HEAD third_party/MNN
160000 commit d407447ed56c4121a11ccbd266dc184ca1ead0c2 third_party/MNN
$ git submodule status third_party/MNN
-d407447ed56c4121a11ccbd266dc184ca1ead0c2 third_party/MNN
$ git ls-remote https://github.com/alibaba/MNN.git refs/tags/3.6.1 'refs/tags/3.6.1^{}'
ea44a3ebd5dd6348eea501047b17c43aa3ecccb6 refs/tags/3.6.1
d407447ed56c4121a11ccbd266dc184ca1ead0c2 refs/tags/3.6.1^{}
```

`third_party/MNN` 当前是空目录，前缀 `-` 表示未初始化；实际锁定 revision 是 gitlink 的 `d407447…`，没有可报告的本地 submodule checkout HEAD。在空目录执行 `git rev-parse HEAD` 会向上找到 Persona 仓库，不能作为 MNN revision 证据。

`app/src/main/cpp/CMakeLists.txt:25–45`：优先使用显式 `PERSONA_MNN_SOURCE_DIR`，其次已初始化 submodule，否则下载官方 `3.6.1` archive，校验 SHA-256 `93d60848879e68be554c247afe376657630a30652a7d8ec713e9e7d1bbf69260`。本轮没有运行 CMake 或检查已安装 APK 的二进制来源，因此不能把构建配置等同于设备实际 runtime revision。

历史质量事实来自 `docs/qa/p3-status-2026-11-05.md`，本轮未重跑：两个 baseline 各六次，temperature=0、topP=0.8、maxTokens=256；0.5B 六次被严格解析拒绝，1.5B 一次被拒绝、五次可解析，但提问/虚构仍产生错误候选。严格内容一致为 0/12，包含前缀、类型或选段差异，不是通用准确率为零。P3 的并发/恢复通过也不是内容质量通过。

## 模型身份与许可

| 项目 | 固定研究对象 |
| --- | --- |
| 官方模型 | `Qwen/Qwen3-0.6B` |
| 模型 revision | `c1899de289a04d12100db370d81485cdf75e47ca` |
| revision 证据 | 本轮官方 Hugging Face model metadata 返回的 `sha`，并读取该 SHA 下的 README 模型卡 |
| 许可 | 模型卡声明 `Apache-2.0`；后续制包保留对应 revision 的 LICENSE、适用 NOTICE 和转换来源记录 |
| 能力边界 | 官方卡标注 0.6B、28 层、Q/KV heads 16/8、上下文 32,768；这些规格不代表设备预算或质量已通过 |
| 唯一部署规格 | `quant_bit=8`、`lm_quant_bit=8`、`embed_bit=16`，CPU；尚无导出物或包哈希 |

选择理由是官方 post-training 模型具备 non-thinking 硬开关，固定 MNN 源码也有对应结构支持，足以进入受控验证。[1][2][3] 质量优先下先避免额外 INT4 量化变量；这不证明 INT8 的资源可接受。与已有不同量化 baseline 的比较只能说明部署包整体表现，不能单独归因于模型架构。未引入第二个新模型或多个量化候选。

## 固定版本官方导出路径

以下均从 MNN commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2` 源码读取，未用 `main` 代替版本依据：[2]

| 源码位置 | 本轮确认的机制 |
| --- | --- |
| `transformers/llm/export/utils/model_mapper.py:133–148` | `regist_qwen3()` 显式注册 `qwen3`，映射 `q_norm` / `k_norm`，不能把它当成纯 Qwen2 图 |
| `transformers/llm/export/utils/transformers.py:244–334` | attention 路径处理 Q/K norm 与 RoPE 的先后关系 |
| `transformers/llm/export/llmexport.py:53–106,661–711` | 读取本地模型和 tokenizer，导出语言图、tokenizer、`llm_config.json`、`config.json`，复制 chat template 到 `jinja.chat_template` |
| `transformers/llm/export/utils/tokenizer.py:183–226,535–570` | 有 `tokenizer.json` 时优先导出二进制 `tokenizer.mtok`，带 special/stop IDs；不得拿 HF JSON 直接冒充 MNN tokenizer |
| `transformers/llm/export/utils/mnn_converter.py:17–58` | 优先指定存在的 `MNNConvert`，否则退回 Python `MNN.tools.mnnconvert`，存在误用其他安装版本的风险 |

源码确认的导出调用示例如下，**本轮未执行**。输入须是上述模型 SHA 的完整本地 snapshot，权重、tokenizer、配置和 generation config 同 revision；本轮只读取小型元数据、模型卡和源码，没有获取模型权重。

```powershell
python "<MNN-d407447>/transformers/llm/export/llmexport.py" `
  --path "<Qwen3-0.6B-c1899de-snapshot>" `
  --dst_path "<isolated-output>/qwen3-0.6b-mnn-int8" `
  --export mnn --quant_bit 8 --lm_quant_bit 8 --embed_bit 16 `
  --mnnconvert "<MNN-d407447>/build/MNNConvert.exe"
```

这些选项在固定 `llmexport.py:835–881` 存在；示例中的尖括号路径需替换。首次工具/输出路径宜无空格，固定 converter 使用 shell 拼接调用。必须记录 converter 的实际固定源码来源与二进制哈希，确认指定文件确实存在，不能依赖未固定版本的 pip fallback。导出机 Python、torch、transformers、ONNX 等实际版本也须记录：模型卡要求 transformers 至少 4.51.0 才识别 Qwen3，但 exporter 的 requirements 未锁版本，这不是所有新版依赖组合都已验证。[1][2]

应保留 `llm.mnn`、`llm.mnn.weight`、`config.json`、`llm_config.json`、`tokenizer.mtok`、`export_args.json` 及实际引用的 embedding 等附件。当前 exporter 写入 `config.json.tokenizer_file=tokenizer.mtok`；不要沿用 baseline 的 `tokenizer.txt`。包内全文件记录 size/SHA-256，并将模型 SHA、MNN SHA、工具版本、导出参数、模板和 non-thinking 配置纳入可追溯记录。当前模型包 schema 不会自动表达全部来源信息。

## Tokenizer、prompt 与 thinking 控制

官方卡的 `enable_thinking=False` 是模板硬开关；`/no_think` 是 thinking 默认开启时的软指令，仍可能有空的 think block，不能当作硬开关。不要通过截掉 `<think>` 或仅提示“只输出 JSON”来声称关闭 thinking。[1]

固定 MNN 的可用通道是 **ChatMessages → 原始模型 Jinja 模板 → context**：[3]

- `llmconfig.hpp:59–102` 先加载 runtime config，再合并 `llm_config.json`。
- `llm.cpp:112–124,287–337` 加载 tokenizer，读取 `context_file`（默认包内 `context.json`），合并到 `jinja.context`，设置模板后创建 sampler。
- `tokenizer/tokenizer.cpp:981–1024` 将 context 作为 extra context 传入模板；`engine/CMakeLists.txt:45–46` 为 llm target 始终定义 `LLM_USE_JINJA`。仍需核对实际构建二进制，不能只检查 JSON 字段。

隔离候选包的 `context.json` 内容可固定为：

```json
{"enable_thinking": false}
```

该文件属于后续候选包数据，本轮未创建。也可在导出 `llm_config.json` 的 `jinja.context` 中携带同一布尔值；研究建议使用上述单一 `context.json` 入口，避免多个配置来源互相覆盖。必须检查最终生效模板/context、实际 prompt token IDs，以及包含 `/think` 用户输入时的结果。HF Python 参数不会自动传入 Android JNI。

raw-text 路径直接 `tokenizer_encode` 后 `response(inputIds, ...)`，不运行 MNN chat template；只有 ChatMessages 路径会在 runtime 中套模板。当前 Kotlin raw adapter 只补 `<|im_start|>assistant\n`，没有 Qwen3 non-thinking 模板语义，不能因共用 ChatML 标记就认为兼容。

历史只回灌最终回答，不能把思考内容混入后续对话或记忆来源。`</think>` 也不是回答结束符，不能把它加进普通 stop words；真正停止要核对导出的 EOS IDs、`<|im_end|>`、现有 `<eop>` 和 token cap，避免先停在空思考块、无限生成或流式泄漏标记。

## 现项目的确切兼容缺口

| 层与本地证据 | 已确认限制 / 后续验证边界 |
| --- | --- |
| `core/ai/ModelPromptConfig.kt:3–10` | 实际是 `ModelFamilies` object，不是 `ModelFamily` enum；唯一 family 为 `QWEN2_5`，只有 `QWEN_CHATML_TEXT` / `MNN_CHAT_MESSAGES` 两种 promptFormat |
| `LocalModelValidator.kt:126–133,294–301` | `QWEN3` 会返回 Unsupported；用旧 family 伪装不能证明候选被正确接入 |
| `prompt/PromptAdapterRegistry.kt:12–27`、`QwenChatMlTextAdapter.kt:10–12` | raw adapter 同时要求 `QWEN2_5` 和 raw ChatML；registry 没有 Qwen3 专用选择或 thinking 契约 |
| `prompt/MnnChatMessagesAdapter.kt:9–10,20–36` | 只按 promptFormat 接受消息，已有 native 模板通道，但它本身既不关闭 thinking，也不能绕过 validator |
| `prompt/ContextPlanner.kt:42–54`、`mnn/MnnLocalAiEngine.kt:316–330` | 引擎已传入真实 native token counter，不能说它仍用字符估算；但消息路径预算渲染仍是旧 ChatML 文本，没有计入 Qwen3 的完整模板差异，需核对最终 IDs 与输出预留 |
| `mnn_jni.cpp:683–753` | raw 与 messages 路径不同；每次请求 reset，messages 结束后 syncPromptCache。候选必须验证 hard switch、多轮、reset、停止和缓存同步，不能靠过滤输出补救 |
| `tools/model-packager/model_package.py:20–28,112–133,207–213` | 打包器同样只接受 `QWEN2_5`；tokenizer 自动检测不读 `tokenizer_file`，且优先选 `.txt` 再 `.mtok`。后续候选应显式选择 `.mtok` 并核对 runtime 实际打开的文件 |
| `LocalModelValidator.kt:149–191,203–208` | 检查图/权重/manifest tokenizer、files 哈希与 LF 文本；不校验 manifest tokenizer 与 runtime `tokenizer_file` 一致，也不要求 `llm_config.json`、context、embedding 引用完整。`Ready` 仍带来源未验证的 RISKY 提示，不能当 runtime 或质量合格 |
| `GenerationParams.kt:3–6`、`generation_config.h:27–43` | 当前只公开 temperature/topP/maxTokens；temperature=0 走 greedy，否则 sampler_type=topP。不能声称完整实现官方建议的 TopK/MinP 采样组合 |
| `features/P3ModelRuntimeInstrumentedTest.kt:58–80,117–129`（androidTest） | 固定六样本与两个 arm，经过真实 installer/validator；并非现成三模型 benchmark。M1 lifecycle 也经过相同门禁。首次 native 探针与后续 App 回归须区别记录 |

首次质量对照应保留 P3 的相同 prompt/parser、六条样本和 temperature=0、topP=0.8、maxTokens=256；这用于解释历史回归，不等于 Qwen3 官方推荐配置。官方 non-thinking 建议 temperature=0.7、topP=0.8、TopK=20、MinP=0；如另做该配置诊断，必须单列有效 sampler 和不支持项，不能把两种设置的最好结果合并。[1]

## 初次 Windows / 手机准入用例（全部未运行）

下表是研究得出的验收契约，所有项目当前状态均为 **未运行**；不代表已创建测试或有可直接运行的 Qwen3 App 包。独立 runtime 探针应直接使用固定 MNN API 和独立目录，不通过篡改 family 获得“通过”。App 端回归需要另轮最小适配，本轮不改代码。

| ID / 执行面 | 输入或动作 | 可机器判定的准入证据 |
| --- | --- | --- |
| W1 / Windows 制包 | 核验本地 snapshot SHA、同 revision tokenizer；固定 converter 导出唯一 INT8 包 | 工具版本/哈希、完整导出日志、文件清单与 SHA-256；无缺失 graph/weight/embedding/llm_config/context；失败即停，不另找未经固定的转换器 |
| W2 / Windows 模板 | 固定官方模板，context 为 false；单轮、多轮、用户含 `/think` | 比对 HF 模板与 MNN 实际渲染/编码结果，包括中文、换行、emoji、角色与 generation prefix；证明 false 生效，不是软指令或后处理过滤 |
| W3 / Windows→手机传输 | 原样传输候选，校验包哈希和 tokenizer | 两端 size/hash 一致，无 CRLF/AppleDouble 污染；runtime tokenizer 与 manifest 同一文件，错误包被拒绝 |
| D1 / 手机独立 native | arm64、CPU，记录 APK/so/MNN revision；加载、生成、卸载 | 有限时间内完成或明确失败，无 native crash；记录实际 tokenizer、模板/context、EOS 与 token cap；一次 ping 不作为质量通过 |
| D2 / 手机模板与流 | 中文、emoji、多轮及带 `/think` 的用户轮次 | 无思考内容、泄漏控制标记或重复套模板；EOS 正常结束、token cap 生效；连续请求不继承其他账号/会话 KV |
| D3 / 手机质量 | 两个既有 baseline 与唯一候选分别执行下节固定六样本 | 每个模型六条记录全量保留，严格 parser、类型、来源摘录、语义负例分别计数；保留拒绝、超时、空结果，不只挑成功输出 |
| D4 / 手机安全回归 | 取消/前台抢占、切回 baseline、进程恢复、账号/Persona 切换、来源删除 | 无迟到写入、跨账号/Persona 污染；未确认不入正式记忆，确认/修订/删除保持权限边界 |
| D5 / 手机资源观察 | 记录设备/ABI/SDK、可用内存、电量、热状态和有效生成参数 | 记录冷加载、TTFT、tokens/s、RSS/PSS/VmHWM、峰值采样方法、超时/OOM；未知预算明确未判定，不挪用 baseline 数据当候选数据 |

### 固定提取样本与判定

复用 `tools/persona-memory-benchmark/extraction_samples.jsonl` 中 P3 实际选中的六条；完整 JSON schema 与类型枚举以 `MemoryExtraction.VERSION` 对应 parser 为准，不放宽解析或清洗无效输出来提高成绩。

| sample ID | 原始合成输入 | 预期 |
| --- | --- | --- |
| `explicit-tea` | `记住，我喜欢喝茶。` | PREFERENCE：`我喜欢喝茶。` |
| `explicit-negation` | `请记住：我不喜欢咖啡。` | PREFERENCE：`我不喜欢咖啡。`，保留否定 |
| `explicit-goal` | `记住，我的目标是明年跑完马拉松` | GOAL：`我的目标是明年跑完马拉松`，不虚构日期或已完成事实 |
| `recall-question` | `你记住我喜欢什么吗？` | 合法空结果，不制造用户偏好 |
| `fiction` | `记住，假设我喜欢茶` | 合法空结果，不把假设当事实 |
| `conflicting-preference` | `记住我不喜欢茶` | PREFERENCE：`我不喜欢茶`，不翻转否定；涉及旧记忆冲突仍由用户确认处理 |

分别报告：原始 JSON/schema 合法率、类型/摘录严格一致率、来源子串合法性、提问/虚构误提取次数、否定保留、超时/截断/取消次数。解析通过不等于语义正确；来源子串检查也无法单独阻止“假设我喜欢茶”的错误候选。六条是首次准入样本，不是通用中文能力评测。

首次质量门槛：六条均合法结束且满足表内预期、负例零错误候选；任一失败则不得标为提取准入通过。即便六条全过，也只报告这六条的结果，不能宣称模型全面更好或取消确认。重复运行或扩充到现有条件、引用、混合语言、emoji 样本时保留独立 run ID 和全部输出，不以最优一次替代全记录。

后续证据只含合成样本；原始响应保存在隔离 QA 产物，不进入生产日志。不记录真实用户完整 prompt/response、Authorization 或密钥。判断与报告由机器契约执行，不新增 pear 人工评分任务。

## 事实、推断与未运行数据

| 分类 | 本轮状态 |
| --- | --- |
| 已验证事实 | Persona gitlink、未初始化状态、3.6.1 tag 解引用、CMake fallback；固定 MNN exporter/engine 源码；项目 validator/adapter/family/打包/采样限制；官方模型 SHA、许可和 non-thinking 接口 |
| 历史事实，未重跑 | P3 十二次 baseline 提取及已记录的失败类型；只能引用原报告口径 |
| 源码支持的推断 | Qwen3-0.6B 可以先尝试留在当前 MNN 3.6.1 验证，无已知结构性升级前提；native 模板通道可能支持 hard-off；不是 runtime 实测结论 |
| 未运行 | 权重获取、真实导出、包校验、Windows/MNN 模板一致性、Android 构建/安装、候选加载/生成、中文与提取质量、资源和性能、安全回归 |
| 尚无数据 | 候选包字节数/哈希、实机内存、TTFT、tokens/s、成功率；均不得填 0、沿用 baseline 数值或记为通过 |

本轮没有初始化/更新 submodule，没有修改代码、依赖或现有 QA，没有下载模型权重，也没有 commit。仅写入本研究文档；文档检查不能代替编译或模型测试。

**是否可继续当前 runtime 验证：可以，有条件。** 固定 MNN 3.6.1 不动，先在后续具备同 revision 导出物和隔离探针时检验 tokenizer/template/non-thinking/加载；当前未准备这些产物。不能现在宣称候选可直接导入生产 App。family、打包与预算契约的开放取决于模板和 runtime 验证；若实测才发现必须升级 MNN，再单独立项。任何研究或验证结果都不自动改变模型选择或记忆确认规则。

## Sources

仅以下三个官方固定版本入口作为外部事实依据；目录内的具体源码路径和行号已在上文标出。本轮读取的是小型模型元数据/模型卡与 MNN 源码，未采用第三方量化包、教程或排行榜结论。

[1]: https://huggingface.co/Qwen/Qwen3-0.6B/blob/c1899de289a04d12100db370d81485cdf75e47ca/README.md
[2]: https://github.com/alibaba/MNN/tree/d407447ed56c4121a11ccbd266dc184ca1ead0c2/transformers/llm/export
[3]: https://github.com/alibaba/MNN/tree/d407447ed56c4121a11ccbd266dc184ca1ead0c2/transformers/llm/engine

1. [Qwen 官方模型卡，固定模型 revision][1]：许可、模型身份、模板模式与官方参数建议。
2. [MNN 3.6.1 固定 commit 的 export 源码][2]：Qwen3 映射、Q/K norm、CLI、tokenizer 与 converter 路径。
3. [MNN 3.6.1 固定 commit 的 engine 源码][3]：Jinja 编译、context 加载、tokenizer 和模板执行。


## 2026-11-08 实验补充：Windows 可运行，候选未采用

以上“未运行”等字段是 2026-11-05 的研究快照。本次在独立 Windows 目录完成固定源 snapshot 校验、
固定 MNN converter 构建、CPU torch 及依赖准备；原项目 submodule、运行时和默认模型未变。
导出物参数为 weight/lm INT8、独立 BF16 embedding，11 文件共 949,972,727 bytes，有完整逐文件收据。
模板由固定 exporter 写入 `llm_config.json.jinja.chat_template`，`context.json` 设置 `enable_thinking=false`。

| 准入项 | 本轮实际结果 |
| --- | --- |
| W1：来源、工具与导出物 | 固定 HF blob/LFS 身份和文件实算一致；固定 converter 编译通过，产物配置/完整性与后续主机加载已核验；原始导出总进程退出码未留存 |
| W2：模板与 Token IDs | 单轮、多轮中文/换行/emoji、含 `/think` 三组 HF/MNN 全部相等 |
| W3：Windows CPU runtime | 主机探针退出 0；加载和三次生成通过，输出未泄漏思考/控制标记，上限 24 tokens |
| D1～D4：候选手机与产品 | 未运行；现有 App family、预算和包契约仍未开放 |
| 固定六条候选质量 | 未运行，不沿用主机 smoke 或历史 baseline 分数 |

**暂不采用。** 此实验验证了当前固定 MNN 主机路径的可运行性，不能证明候选在 Android 更快、更省内存或回答更好。
后续仍需按本页门槛独立验证 Android 模板/预算、资源、取消及六条质量，不能伪装旧 family 绕过生产准入。
本轮不新增人工评分任务。完整执行和清理记录见 [集中回执](p4-status-2026-11-08.md)。
