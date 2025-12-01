# Persona - 构建你的 AI 化身，定义下一代社交网络

**Craft Your Digital Self.**

一个基于 Android 端云协同架构的 AI 原生社交应用。

![alt text](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)

![alt text](https://img.shields.io/badge/Architecture-Clean%20%2B%20MVVM-blue.svg)

![alt text](https://img.shields.io/badge/AI-Hybrid%20(Edge%20%2B%20Cloud)-green.svg)

## 📖 项目简介

**Persona** 是一个探索未来社交形态的 Android 实验性项目。在这个平台上，用户不再仅仅分享生活，而是能够创造、培养并扮演由 AI 驱动的“数字人格”。

本项目旨在解决移动端 AI 应用的核心痛点：**隐私与能力的平衡**。通过构建一套**智能路由系统**，实现了 Google Gemma (端侧) 与 火山引擎 (云端) 的无缝协作，配合 **Room + Paging 3** 实现海量数据的本地持久化，打造了零延迟、高智商且具备记忆的社交体验。

## ✨ 核心功能

### 1. 🤖 端云协同混合聊天 (Hybrid AI Chat)

- **智能路由 (AI Router)** ：内置端侧“判官”模型，实时分析用户输入复杂度。

  - 🟢 **绿灯 (Edge Mode)** ：日常闲聊、隐私对话由本地 Gemma 2B 毫秒级响应，零流量，零延迟。
  - 🔵 **蓝灯 (Cloud Mode)** ：复杂逻辑、代码生成或 @cloud 指令自动无缝切换至云端大模型。
- **流式响应 (Streaming)** ：全链路支持 SSE 流式输出，实现丝滑的“打字机”效果。
- **富文本渲染**：支持 Markdown、代码高亮、表格渲染。

### 2. 🧬 Persona 创作实验室

- **AI 辅助生成**：基于用户提供的几个关键词（如“赛博朋克”、“侦探”），调用云端 AI 自动生成完整的人设、背景故事和性格标签。
- **JSON 结构化**：通过 Prompt Engineering 强制 AI 输出结构化数据，自动解析入库。

### 3. 🌏 沉浸式社交广场

- **AI 朋友圈**：模拟 AI 发布的动态瞬间，配合 Picsum 高清随机配图。
- **双重过滤发现页**：支持“分类标签 + 关键词”的组合筛选算法。

## 🛠️ 技术栈

- **语言**: Kotlin
- **架构模式**: MVVM 
- **依赖注入**: Dagger Hilt
- **异步处理**: Coroutines + Flow
- **端侧 AI**: Google MediaPipe Tasks GenAI (Gemma 2B CPU Int4)
- **云端 AI**: Retrofit + OkHttp (火山引擎 API)
- **本地存储**: Room Database + Paging 3
- **图片加载**: Coil
- **UI 组件**: XML (ViewBinding), Material Design Components, Markwon

## 🚀 快速开始 (Setup)

由于本项目包含大模型文件和敏感 API Key，请按照以下步骤配置环境：

### 1. 克隆项目

### 2. 配置 API Key

在项目根目录下创建 local.properties 文件，添加你的火山引擎 (或兼容 OpenAI 格式) API Key：

### 3. 部署端侧模型 (关键步骤)

由于 Gemma 2B 模型文件较大 (\~1.3GB)，未包含在 Git 仓库中。

- 下载 **Gemma 2B IT CPU Int4** 模型 (.bin 格式) [下载链接](https://www.google.com/url?sa=E&q=https%3A%2F%2Fwww.kaggle.com%2Fmodels%2Fgoogle%2Fgemma%2FtensorFlow2%2Fgemma-2b-it-cpu-int4)。
- 重命名文件为 gemma-2b-it-cpu-int4.bin。
- 连接 Android 设备/模拟器。
- 使用 Android Studio 的 **Device File Explorer**，将文件上传至：
  /data/data/com.example.persona/files/gemma-2b-it-cpu-int4.bin

### 4. 运行

Sync Gradle 并运行 App。

注意：模拟器建议分配 4GB 以上 RAM，否则加载端侧模型可能导致 OOM。

## 🔮 未来规划 (Future Roadmap)

本项目目前处于 MVP 阶段，未来的迭代将致力于打破端侧算力的物理边界，构建真正的 AI Native 生态：

- **[架构演进] 分布式多租户架构 (Multi-Tenant Architecture)**

  - 从当前的“单机沙盒”模式演进为支持多用户并发的云端系统。
  - 实现基于 **Firebase Auth / OAuth 2.0** 的身份认证与数据隔离。
  - 构建 **Sync Adapter**，实现本地 Room 数据库与云端数据库的双向增量同步，让用户的数字分身在多端无缝漫游。
- **[前沿探索] 端侧 Agent 编排与 MoE (Mixture of Experts) 机制**

  - **意图分发中枢**：升级端侧模型（Router），使其不再局限于简单的文本路由，而是进化为 **Agent Controller**。
  - **垂直领域模型调度**：根据用户指令的语义（如“画一张图”、“写一段旋律”），智能唤起专精的垂类模型（如 Stable Diffusion 用于绘图、MusicGen 用于生乐），实现 **“一脑多能”** 的混合专家体验。
- **[模型迭代] SOTA 模型适配与 NPU 加速**

  - **模型升级**：跟进业界最前沿的轻量级模型（如 **Llama 3.2 3B**, **Qwen 2.5**），以提升端侧的逻辑推理与指令遵循能力。
  - **硬件加速**：迁移至 **Vulkan / NNAPI** 推理后端，充分释放 Android 设备的 NPU 算力，降低推理延迟与功耗。
- **[记忆系统] 长期记忆向量库 (RAG on Device)**

  - 引入端侧向量数据库（Vector DB），对历史对话进行 Embedding 存储。
  - 利用 **RAG (检索增强生成)**  技术，让 Persona 能够“回想起”很久以前的对话细节，建立真正的情感连接。
