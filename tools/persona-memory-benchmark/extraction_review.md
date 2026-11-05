# P3 提取与摘要核心 review

本页保留 Mac 核心检查时点。后续真实设备结果与模型缺陷见
[P3 集中验收](../../docs/qa/p3-status-2026-11-05.md)，下方 unrun 只指当时状态。

P3 需要独立验证候选提取、严格模型输出校验和可追溯摘录，摘要文本必须满足现有
memory 的 400 字符上限。本次交付覆盖两个核心、对应 JUnit 测试及固定合成样本。

## Review 签名

- Reviewer 为 Codex，日期为 2026-11-05，分支为 `codex/p3-memory`。
- 核心源码及全部新增文件已经读取 review。独立验证过程的源码变化列表为 `[]`。
- 读取主代理的摘要调用点，确认旧到新输入顺序与来源关联字段一致。
- 本次 review 范围内未发现待处理缺陷。Android 集成与真实 MNN 验收状态为 `unrun`。

## 接入契约

`MemoryExtraction.Suggestion(type: MemoryType, content: String, topicKey: String)` 保持既定签名。
`explicit/selected/decode/prompt` 均已通过实际编译与 `javap` 验证。

`decode` 接受根对象 `{"suggestions":[{"type":"FACT","content":"来源连续子串"}]}`。
每项必须恰好包含 `type/content` 两个 String 字段，type 必须匹配现有 enum。
合法空数组返回空 List，无效输出抛出不含原文的 `IllegalArgumentException`。
任一项无效则整体拒绝，精确重复项在校验后去重。没有修复循环。

摘要正式入口为 `MemorySummary.summarize(messages: List<Message>): Result`。
`build(messages)` 为等价入口。

```kotlin
data class Source(val messageId: String, val contentHash: String, val content: String)
data class Result(val text: String, val sources: List<Source>, val sourceHash: String)
```

摘要输入按旧到新排列，只扫描末尾 128 条。最近完整原文优先，最多 8 条来源，
文本连同换行分隔符最多 400 UTF-16 chars。仅 `NORMAL` 用户消息且
`excludeFromHistory=false` 有资格。超过 400 chars、空白、非法 Unicode 和空 ID
均跳过。同 ID 的较新版本优先，包括排除状态。混合 Persona 输入返回空结果。
遇到下一条合资格消息超出总预算时停止，保留已选的最近连续合资格片段。

每条来源完整进入摘要，`contentHash` 对完整原文 UTF-8 字节计算 SHA-256 小写 hex。
`sourceHash` 覆盖摘要版本、Persona、实际引用的有序消息 ID 和内容哈希。
空结果的来源列表为空，接入方据此跳过写入。

规则只覆盖有限中文表达。`selected` 保留完整手选原文，无法分类时默认 FACT。
`topicKey` 为粗粒度冲突提示。来源子串校验仍需设备内容质量评审验证否定和语境。

## 实际验证

使用现有 JDK 17、Kotlin 2.0.21、JUnit 4.13.2、Gson 2.8.5，JVM target 为 11。
所有被测类均由本次 output 中冻结的源码重新编译。

```text
Initial red
Tests run: 22,  Failures: 18

Boundary regression red
Tests run: 27,  Failures: 3

Final JUnit
JUnit version 4.13.2
...........................
Time: 0.138

OK (27 tests)

Synthetic extraction contracts 24/24
Device MNN unrun
Exit 0
```

最终运行命令如下，重复运行时需要新的 output 路径。

```sh
python3 tools/persona-memory-benchmark/extraction_check.py \
  --output /tmp/persona-p3-extraction-final-20261105 \
  --deps /Users/pear/.codex/verification/persona-p2-chat/deps \
  --java-home /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

最终 output 为 `/private/tmp/persona-p3-extraction-final-20261105/`，包含
`compile.log`、`junit.log`、`api.log`、`benchmark.json`、`manifest.json` 和冻结源码。
初始失败与边界失败分别保存在相邻的 `persona-p3-extraction-red-20261105` 和
`persona-p3-extraction-regression-red-20261105` 目录。

## SHA-256 收据

```text
MemoryExtraction.kt
59516c54394dbaa3734b34b212f8cc53c78d36a6d774b30532ab8a70527340c5
MemorySummary.kt
7bf677cfdec5942cea4d39f2680cdbacdc50b7d696aff63b2c25f0ab6ab74ef6
extraction_samples.jsonl
9345975b9f5763e718cc2ae063c958ea2a10decdb2697e33ea3cbd577c7abcdd
api.log
a1511c89b9e96b8c9de102dc80a8cb9c9a37d46a7d0853a3229bd3e35d0b66a4
manifest.json
facb95d1e254fc99e7f5c9c5445540493efe4fabfb2d51b11515d0efcafa1380
```

## 固定样本与设备交接

`extraction_samples.jsonl` 固定 24 个合成样本，分别记录规则预期、手选候选类型和
模型提取参考答案。Mac 检查规则、手选契约，并将模型参考答案序列化后验证 parser。
输出中的 `schema_reference_matches` 记录参考 JSON 回放结果。
单次 Mac 耗时只用于本地诊断。模型质量、用户确认成本和设备耗时待真实设备测量。

`extraction_mnn_unrun.json` 明确保留 `status=unrun` 与空 observations。
设备对照使用同一固定 source 和 `MemoryExtraction.prompt(source)`，冻结数据集哈希、
提取版本、模型包哈希、设备及生成配置。每条真实输出经 `decode(raw, source)` 校验，
保留失败和空结果，并对照 model 参考答案评审否定、条件与虚构。当前真实推理记录为零。

## Sources

- Gson streaming token、String 类型检查与严格读取依据。[Gson 2.8.5 JsonReader](https://javadoc.io/static/com.google.code.gson/gson/2.8.5/com/google/gson/stream/JsonReader.html)
- SHA-256 来源哈希使用 JDK MessageDigest。[Java 11 MessageDigest](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/security/MessageDigest.html)
