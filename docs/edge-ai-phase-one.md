# 第一阶段：MNN 本地模型接入

## 目标

在不重做聊天业务的前提下，新增端侧 AI 抽象层，接入 MNN-LLM 最小推理链路，让 Qwen2.5-0.5B Instruct 4bit 能在真机本地生成回复。云端 DeepSeek 保留为兜底路径。

## 职责梳理

| 文件 | 第一阶段职责 | 处理决定 |
| --- | --- | --- |
| `ChatViewModel` | 只负责发送消息、创建 `GenerationSession`、保存用户/AI 消息、收 token、停止当前生成，并暴露生成中状态。 | 保留并改造生成控制。 |
| `HybridAiRepository` | 只负责本地/云端路由：本地 ready 走 LOCAL，`@cloud` 或本地不可用走 CLOUD，本地失败后兜底云端。 | 保留并收窄职责。 |
| `EdgeAiEngine` | 旧 MediaPipe/Gemma 路径。 | 第一阶段不再作为主链路依赖。 |
| `ModelDownloadManager` | 旧单文件 `gemma-2b-it-cpu-int4.bin` 下载逻辑。 | 第一阶段废弃，后续下载系统单独做。 |
| `ModelManagementActivity` | 本地模型管理页，扫描标准 models 目录、校验 manifest、选择/删除模型。 | M2 已接管模型管理。 |
| `CloudChatRepository` | DeepSeek SSE 云端聊天路径。 | 保留为兜底和 `@cloud` 强制路径。 |
| `SettingsManager` | 主题等应用设置。 | 保留，不混入模型运行状态。 |

验收口径：聊天 UI 只管发消息和收 token；`HybridAiRepository` 只管 LOCAL/CLOUD 路由；旧 `EdgeAiEngine` 不再是主线强依赖。

## 当前实现

- 端侧领域对象位于 `app/src/main/java/com/example/persona/core/ai/`：`ChatMessage`、`GenerationParams`、`GenerationSession`、`InstalledModel`、`EngineState`、`RecommendedModel`、`Backend`。
- `LocalAiEngine` 是聊天层唯一感知的本地模型接口，暴露 `state`、`initialize`、`streamResponse`、`stopGeneration`、`release`。
- MNN 实现位于 `core/ai/mnn/`：`MnnLocalAiEngine` 负责 Kotlin Flow 和状态流，`NativeMnnSession` 封装 JNI create/load/generate/stop/destroy。
- JNI 入口位于 `app/src/main/cpp/mnn_jni.cpp`，CMake 优先使用 `third_party/MNN`，目标输出 `libpersona_mnn.so`。
- MNN 生成链路按官方 Android demo 的 stepping 模式执行：`response(..., 0)` 做 prefill，再循环 `generate(1)`，同时处理 Android 运行时每步可能出现的中间 `<eop>`。
- Prompt 渲染已从 JNI 下沉风险区移到 Kotlin `PromptAdapter` 层：Qwen 默认走 `QWEN_CHATML_TEXT` RawText 路径，JNI 只负责把 RawText 或 ChatMessages 搬到 MNN Runtime。
- `LocalModelManager` 扫描 App 专属 `models` 目录，避免业务代码到处传裸 `modelDir` 字符串。
- 官方 `Llm::response` / `generate(1)` 通过 `ostream` 写出每个生成步的增量解码片段；Kotlin 层只截断 `<eop>`，不做累计前缀去重，避免连续相同字符被误删。
- 生成开始后会记录 MNN 当前 KV/history 位置；正常完成才 `syncPromptCache`，用户停止时调用 `eraseHistory` 回滚 native 内部状态，避免下一轮聊天吃到未落库的残留上下文。

## 模型放置

第一阶段只支持手动放置模型，不做下载、断点续传、升级和模型市场。

推荐目录：

```text
/storage/emulated/0/Android/data/com.example.persona/files/models/qwen2.5-0.5b-instruct-mnn/
```

开发阶段不再兼容旧内部目录或嵌套目录。模型目录内至少需要直接包含：

```text
manifest.json
config.json
llm.mnn
llm_embeddings.mnn / weight 相关文件
tokenizer.model
```

`manifest.json` 必须包含 M2.5 字段：`family`、`promptFormat` 和 `contextWindow`。

## 推荐模型

- 验证流程：
  - Hugging Face：`taobao-mnn/Qwen2.5-0.5B-Instruct-MNN`
  - ModelScope：`MNN/Qwen2.5-0.5B-Instruct-MNN`
- 体验效果：
  - Hugging Face：`taobao-mnn/Qwen2.5-1.5B-Instruct-MNN`
  - ModelScope：`MNN/Qwen2.5-1.5B-Instruct-MNN`

第一阶段使用 MNN 预转换模型包，不使用 GGUF，也不再使用旧 MediaPipe/Gemma `.bin` 文件。

## 真机验证记录

这些值必须来自真机运行和 logcat，不从桌面构建结果猜。

| 项目 | 结果 |
| --- | --- |
| MNN 版本 | 3.6.1 |
| 模型 | Qwen2.5-0.5B-Instruct-MNN 4bit |
| 模型目录结构 | `config.json`、`llm.mnn`、tokenizer/weight 相关文件在同一模型目录 |
| 手机型号 | 待真机填写 |
| Android 版本 | 待真机填写 |
| 总内存 | 待真机填写 |
| 模型加载耗时 | 待 `MnnLocalAiEngine` logcat 填写 |
| 首 token 延迟 | 待 `MnnLocalAiEngine` logcat 填写 |
| 输出速率 | `MnnLocalAiEngine` 会记录 `outputCodePointsPerSecond`，作为首轮近似指标；严格 tokens/s 仍以官方 demo 的 tokenizer benchmark 为准 |
| 是否崩溃 | 待真机填写 |
| 是否明显发热 | 待真机填写 |

## 当前验收方式

1. Android Studio 同步并 `assembleDebug`。
2. 在真机安装运行，完成 Authing 登录。
3. 打开 **Profile > Settings > 本地模型管理**，确认检测到 Qwen 0.5B MNN 模型。
4. 进入聊天页，等待状态从 `LOCAL LOADING` 变为 `LOCAL READY`。
5. 发送一句中文短消息，确认模式显示 `LOCAL`，回复从本地流式生成。
6. 本地生成中点击输入框右侧按钮，确认当前回复停止，并且不会继续追加旧 token。
   如果首 token 还没有到达，当前占位消息会变成“已停止生成”，不会永久停留在“正在思考...”。
7. 连续快速发送两条消息，确认新 `GenerationSession` 不会混入上一条回复的 token。
8. 在 `local.properties` 填写 `DEEPSEEK_API_KEY` 后发送 `@cloud 你好`，确认可以强制走 DeepSeek 云端。
9. 本地加载或生成失败时，确认聊天不会崩溃，并能切回云端兜底。

## 暂不处理

- 远程 manifest
- 下载/校验/解压/断点续传
- 模型升级
- 多后端 Factory 或 AiRuntime
- 复杂 ChatMode 策略
- 模型导入/导出和模型市场

## 当前待验收项

- 本地模型测试页曾出现连续重复输出，已修复为显式 ChatML 提示词渲染；M2.5 已将 Prompt 适配层独立出来，仍需在真机验证单轮、连续多轮以及停止后立即重发。
- 云端兜底代码已切换为 DeepSeek SSE 接口。未填写 `DEEPSEEK_API_KEY` 时，`@cloud` 会显示明确的配置提示；填写有效 Key 后再验收 `@cloud` 和本地失败兜底。
