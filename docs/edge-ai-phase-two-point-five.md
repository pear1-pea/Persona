# M2.5：模型适配层 / Prompt Adapter

## 目标

M2.5 不继续扩展模型管理，也不做下载系统。目标只有一个：把“业务消息如何变成 Runtime Prompt”单独分层，避免 Qwen 0.5B / 1.5B 或未来 Llama、Gemma 的 prompt 差异污染聊天业务、Repository 和 JNI。

## 分层

```text
ChatViewModel
  ↓ 业务消息
HybridAiRepository
  ↓ 本地/云端路由
LocalAiEngine
  ↓ 本地生成接口
PromptAdapter
  ↓ NativePromptPayload
NativeMnnSession / JNI
  ↓ RawText 或 ChatMessages
MNN Runtime
```

## 当前实现

- `InstalledModel` 增加轻量适配字段：`family`、`promptFormat`、`contextWindow`。
- `LocalModelManifest` 只支持新版 camelCase 字段：`promptFormat`、`contextWindow`。
- 旧 manifest 不再兼容；缺少 `family/promptFormat/contextWindow` 会被判定为不可用，避免开发阶段保留隐式分支。
- `PromptAdapterRegistry` 根据模型元数据选择适配器。
- `QwenChatMlTextAdapter` 在 Kotlin 层拼 Qwen ChatML，并输出 `NativePromptPayload.RawText`。
- `MnnChatMessagesAdapter` 输出纯 role/content 的 `NativePromptPayload.ChatMessages`，交给 MNN 内部模板处理。
- JNI 不再硬编码 Qwen ChatML，只暴露 `nativeGenerateRawText` 和 `nativeGenerateChatMessages` 两条入口。

## Manifest 示例

```json
{
  "id": "qwen2.5-1.5b-instruct-mnn-int4",
  "name": "Qwen2.5 1.5B Instruct 4bit",
  "version": "1.0.0",
  "backend": "MNN",
  "family": "QWEN2_5",
  "promptFormat": "QWEN_CHATML_TEXT",
  "entry": "llm.mnn",
  "tokenizer": "tokenizer.model",
  "contextWindow": 4096,
  "minRamGb": 8,
  "minSdk": 26
}
```

## 完成标准

- 0.5B / 1.5B 可以通过 manifest 指定 prompt 路径。
- `ChatViewModel` 不感知 Qwen、ChatML 或 MNN 模板。
- `HybridAiRepository` 只负责本地/云端路由和兜底。
- JNI 不再拼业务 prompt。
- RawText 路径绕过 MNN chat template，避免双重模板包装。
- ChatMessages 路径保留，用于未来有稳定模板的模型。

## 暂不处理

- Tool Calling。
- tokenizer 级精准上下文裁剪。
- 下载、解压、SHA256、断点续传。
- 插件式 Adapter 动态注册。
