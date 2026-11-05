# M5 中文人格与显式记忆固定评测集

对应长期方案 §8.2–8.3。全部为合成数据，无真实聊天、密钥或个人身份资料。
Python 3.9+，仅标准库，Mac 可直接运行；不需要 Android SDK、模型或网络。

## 数据和结论边界

数据集为 `m5-zh-explicit-v2`，参考策略与本次 M6 的字段和预算对齐。
`personaId=null` 表示用户主动启用本账号全部 Persona 共享，云端授权独立。
检索按 `updatedAt DESC, id ASC` 取最多 4 条，内容总预算 1200 UTF-16 单位；
`query_keys` 仅为评审标签，不作为首版 SQL 筛选条件。相关性由生成质量评审检查。

| 集合 | 确定性契约 | LLM 主观评测 | 合计 |
| --- | ---: | ---: | ---: |
| `datasets/dev.jsonl` | 18 | 10 | 28 |
| `datasets/heldout.jsonl` | 8 | 4 | 12 |
| 合计 | 26 | 14 | 40 |

`validate` 检查数据结构、版本、覆盖标签、ID、来源、事件和切分；
`reference` 检查独立参考契约与手写期望是否一致。
两者都**没有调用生产代码或 LLM**，通过不代表应用权限正确或模型质量合格。
`results/reference-contract-v2.json` 仅保存这类参考结果。

`compare` 检查外部应用 instrumentation 导出的观察值，不执行应用，也无法证明
提供者真实运行过 instrumentation。必须关联实现 revision、adapter 版本和测试证据。
静态基线验证了 14 个 Room/仓库/Prompt 案例。当前 `room-and-lifecycle-boundary-v4`
可显式增加真实 ChatViewModel/Hybrid 请求生命周期，使用受控引擎和云传输。
带生命周期及受控网络对照的设备结果为 20 pass / 6 unsupported，零不匹配；
其中 14 项为静态组件、6 项为请求案例，不能全称为完整端到端或模型质量通过。
详见 [DEVICE_ADAPTER.md](DEVICE_ADAPTER.md) 和
[生命周期验收](../../docs/qa/p2-lifecycle-status-2026-10-30.md)。完整应用 conformance 仍不完整。

14 条主观用例只能通过真实模型输出和人工评审得出质量结论。
自动关键词命中、参考答案、假模型输出和自动裁判都不能代替它，也不能决定隐私放行。

## Mac 命令

从仓库根目录运行：

```sh
python3 tools/persona-eval/evaluate.py validate
python3 -m unittest discover -s tools/persona-eval/tests -v
python3 tools/persona-eval/evaluate.py reference --split all
```

前两类检查可以读取 heldout 的结构并验证固定 oracle；这不是模型调优。
模型/Prompt 开发只使用 dev；heldout 仅在里程碑冻结配置后运行，按 family 整体分组。
两个 split 没有相同 family 或相同对话；它们仍共享风险类别，不能据此宣称统计独立。
一旦针对 heldout 的失败调参，将其视为已暴露并发布新的保留集版本。

生成待填写的应用观测模板，填入真实边界观测后比较：

```sh
python3 tools/persona-eval/evaluate.py template --split dev \
  --output tools/persona-eval/results/application-dev.json
python3 tools/persona-eval/evaluate.py compare --split dev \
  --observations tools/persona-eval/results/application-dev.json
```

模板的 `not_run` 不会通过比较；缺少/重复/越 split 的案例、版本或摘要不符均拒绝。
`unsupported` 必须附非空原因且 `actual=null`，保留为不完整结果；不能填写空集合冒充观测。
证据列表按集合比较，既检查越权多取，也检查合法证据漏取；重复 ref 被视为坏数据。
退出码：0 = 所选检查通过，1 = 契约不匹配或有 unsupported，2 = 输入或文件错误。
`--output` 使用独占创建，已有文件不覆盖；换一个运行名保存。

## 配置和真实模型运行

`config/baseline.v2.json` 分别版本化 dataset、参考策略、检索、Prompt、参数和模型。
文件名对应 dataset v2；当前 config 为 `m5-config-v3`，上下文组装对应生产
`MemoryPrompt` 的 `quoted-user-facts-v4`。`results/reference-contract-v2.json`
保留原 config v2 的历史参考结果，不作为当前应用或真实模型运行记录。
其中模型是 `not_run`，参数仅为计划值，尚未确认后端实际支持/转发。
真实运行须复制完整配置快照并记录模型工件 SHA-256 或服务端模型 revision、
runtime、量化、实际生效参数、设备/执行器和上下文组装版本。
任何组件变化都增加其版本；数据/期望/切分变化还需增加 dataset 版本。

运行记录从 `templates/llm-run.v2.json` 开始，执行步骤见
[HUMAN_REVIEW.md](HUMAN_REVIEW.md)。没有真实输出时保持 `not_run`。
参考/契约命令不调用模型。真实端侧批量入口和人工盲评包见
[REAL_MODEL.md](REAL_MODEL.md)：通过隔离 Android instrumentation 调用实际 MNN，
不读取 API key、不向云发送数据。云端主观案例保留 unsupported，不能用本地替代。

真实 MNN 的单个四阶段 smoke 及失败对照见
[P2 记忆验收记录](../../docs/qa/p2-memory-status-2026-10-29.md)。它没有执行这 40 条固定案例，
不能将该 smoke 当作固定集质量验收；固定集真实运行以独立回执及人工评审记录为准。
组件 adapter 的准备和设备运行入口见
[DEVICE_ADAPTER.md](DEVICE_ADAPTER.md)，契约限制见 [CONTRACT.md](CONTRACT.md)，
不要用参考算法预先过滤 fixture 后再把结果报告为应用拒写。
`results/` 的运行文件默认忽略；只跟踪参考契约基线。

## 参考资料

人工成对比较与固定样本设计参考
[Evaluation best practices](https://developers.openai.com/api/docs/guides/evaluation-best-practices)。
本地文件、项目隐私边界和人工复核要求优先，不接入外部评测服务。
