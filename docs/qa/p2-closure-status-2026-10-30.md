# P2 closure verification — 2026-10-30

后续人工结果、补评与 Mac 修复见
[2026-11-04 记录](p2-review-status-2026-11-04.md)。本页保留此前设备验证的源码与日期。

此前固定集缺少真实模型输出，界面验收未覆盖完整在线链路，真实账号清理又暴露了
成功的 `204 No Content` 被误判为异常的问题。本轮补齐实际推理、界面和网络验收，
修复空响应处理。本地保留集补测、工程检查和清理已完成，人工质量评审进行中；
不能把自动检查通过当成整个 P2 的质量验收。

## Source and environment

- Mac：`/Users/pear/Developer/Persona`，`codex/p2-product-flow`，base `911981a`。
- P2 改动仍未提交/推送，没有本轮远程 CI 或 PR 结论。
- Windows 构建沙箱：`C:\Users\pear\persona-m1-10c9727`；其 Git HEAD 不是当前源码版本。
  运行回执绑定工作源码快照、构建参数和实际安装 APK 哈希。
- 原工程 `C:\Users\pear\AndroidStudioProjects\Persona` 仅只读检查；未改原 `local.properties`、
  `.env` 或数据库。只安装/停止/卸载 `.validation` 与 `.validation.test`。
- 设备仍为 BLK-AL80 / arm64 / SDK 31。保留计费热点，不切换 Wi-Fi、不伪造热状态。

## Real no-content response fix

项目使用 Retrofit 2.9.0。原 `suspend fun ...: Unit` 遇到真实 204 响应时进入非空 body
等待路径，抛出 KotlinNullPointerException；服务端可能已经完成删除，客户端却未继续清理。
使用真实 Retrofit、现有 Gson/OkHttp 和受控 HTTP 传输复现，未 mock 应用 API：

```text
NoContentResponseIntegrationTest
5 tests completed, 3 failed
real Retrofit 204 account deletion ... FAILED
real Retrofit 204 session revocation ... FAILED
real Retrofit 204 persona deletion ... FAILED
```

401/404 拒绝路径在旧实现中正常，不能通过吞掉所有异常修复。
当前登出、账号删除、撤销其他 Session、Persona 删除使用 `Response<Void>`，调用方明确
检查 HTTP 成功状态；非成功仍抛 HttpException。账号删除的 NonCancellable 清理、
身份隔离和持久化重试语义保持不变。补上登出用例后，六项空响应回归进入完整单测。
没有升级 Retrofit 或引入新依赖。

修复后 Windows 实际输出：

```text
BUILD SUCCESSFUL in 1m 1s
93 actionable tasks: 25 executed, 68 up-to-date
tests=418 failures=0 errors=0 skipped=0
```

源码依据：[Retrofit 2.9 非空 body 等待](https://github.com/square/retrofit/blob/2.9.0/retrofit/src/main/java/retrofit2/KotlinExtensions.kt)、
[Response 包装等待路径](https://github.com/square/retrofit/blob/2.9.0/retrofit/src/main/java/retrofit2/HttpServiceMethod.java)。

## Actual UI and backend acceptance

`p2-ui-20261030-03` 在上述修复后通过：

- 七项离线界面测试：既有旋转/检查点/记忆流程，加真实创建、Feed/Discovery/Profile
  片段展示与搜索、编辑后公开/私有状态刷新、卡片打开正确聊天，以及消息长按、来源弹窗、
  记忆保存/编辑/删除按钮和长 Markdown 显示。
- 离线列表测试明确使用 Hilt Activity 承载真实片段，不把它冒充 MainActivity 登录流程。
- 一项真实在线流程：注册临时账号 → MainActivity → 创建 Persona → Profile/Feed/Discovery
  刷新与搜索 → 打开聊天 → 通过发送按钮取消未派发请求并重发，真实云端回复正常。
- 固定云端案例 `heldout-12` 独立磁盘数据库重开后执行三次，实际使用 CloudChatRepository、
  后端和 DeepSeek。私密记忆由 Room 权限过滤，三次授权证据列表均为空、回复状态 NORMAL。
  将 fixture owner 绑定实际临时账号，其他案例内容和应用上下文路径保持原样。
- 临时账号通过实际账号删除接口清理完成，没有客户端空响应异常。

```text
OK (7 tests)
OK (1 test)
```

该次 App APK SHA-256：`14167bd155f77db6cfb02eae53f34a456045a25ef14fa09f911847a0a79fe499`。
Test APK SHA-256：`ef45fe78b61e1267778ad8210498f1f8dbe39795685a3b0a62aae57ad812828d`。
源码输入：`sha256:ad00f43c4af5901f5e61b867601d117aed46e73583ed4448e4cb6452ca4c835d`。

云端仅一个服务模型配置，不能把三次输出描述为胜过本地或另一模型。
服务模型 ID 为 `deepseek-v4-flash`；供应商 revision/seed 未受控。实际客户端采样为
temperature 0.6、topP 0.8、maxTokens 256；不是旧计划文件中的 0.2/0.9/512。

## Real process interruption

`p2-ui-20261030-05` 通过实际云端请求验证，不再只预填 GENERATING 数据：

1. 通过 ChatActivity 发送，等待实际生成中的非空 Room 检查点。
2. Host 收到 READY 后，仅 force-stop 隔离应用。prepare 被终止是预期事件，不计普通通过用例。
3. 新进程检查原记录仍为 GENERATING，再经真实 Hilt/数据库 onOpen 路径恢复成 STOPPED。
4. 检查可见回复、保留文本、不自动再发送，再通过界面重发。

```text
OK (1 test)
first_resend_succeeded=true
synthetic_account_deleted=true
```

这是一个跨进程场景，不能把 prepare/verify 计成两个独立能力。
该次主 APK 与上节一致；Test APK 为
`02241c4e222ea393bdd8876c3329663f6990cc0e59a69b462f2c5e9a0e5e0c73`，源码输入为
`sha256:66eb859bacb6adf08f36e9b8cb533f7741b14de4d41808838fbbc6bd8d653a7e`。

先前 UI03 的首次重发失败、UI04 的显示前超时均保留，因当时取证不足，根因未确认。
UI05 加分阶段记录后首次重发即成功，未走测试中的 HTTP 429 有限用户重试分支。
不能据此宣称先前失败来自 429，也不能宣称已验证该重试分支或长期网络稳定性。

## Long-reply observation

真实 ChatAdapter/MarkdownHelper 展示 5,624 个合成字符，包含代码块、表格、列表、特殊字符。
40 次流式更新后，最终 Markdown 正常渲染，数据库完整文本一致。
UI02 的 debug 观测有 89 个帧样本：总帧时长 P50 约 12.83 ms、P95 约 48.98 ms；
更新方法最长约 0.65 ms。它不是旧版对照或 release 帧率基准，不能声称已经测得性能提升。
后续优化仍需固定构建和负载做对照，参考
[Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。

## Actual local model evaluation

使用真实 ChatViewModel/Hybrid/Room/MNN、实际应用 Prompt 与默认参数，模型包沿用已验收
0.5B/1.5B int4 工件。每案例、每配置三次；完整合成输出只保存到忽略目录的私有工件，
不打印到日志，不将期望或 rubric 传给模型。完整运行说明见
[REAL_MODEL.md](../../tools/persona-eval/REAL_MODEL.md)。

- `m5-quality-dev-20261030-03`：10 个 dev 案例 × 两个模型 × 三次，60 次生成全部完成。
  “完成”只表示执行成功，不是主观内容通过。
- `m5-quality-heldout-20261030-01`：退出前保留 16 次完成与 3 个云端 unsupported 占位，
  进程异常退出，整轮不完整。一次降温等待约 344.75 秒。找到的 10 月 28 日 Tokenizer
  崩溃栈属于历史问题，不能归因给本轮。该次退出原因尚不明确。
- 后续候选加上 process ID、active case/stage、卸载前 ApplicationExitInfo 和 PID 过滤崩溃日志，
  按相同模型/Prompt/采样配置补跑；`m5-quality-heldout-20261030-02` 的 18 次本地生成完成，
  无失败，另 6 个云端占位明确 unsupported。18 次实际执行参数均为默认配置，云端尝试为 0，
  降温等待合计约 0.93 秒。补测不覆盖第一次失败，也不能证明其根因已经消除。
- 本地 runner 不替代云端案例，明确保留 unsupported；云端实际输出见独立回执。

没有据此升级生产默认模型、调整采样值或更换 MNN。0.5B 先前记忆 smoke 不稳定的结论
仍保留；一次 1.5B 成功或一轮传输成功都不代表所有设备、所有输出质量达标。

## Backend isolation and transport limit

Windows 原 `.env` 仅用于读取服务端配置，不打印 Key。临时 PostgreSQL 16 cluster
使用 `127.0.0.1:55542`，后端使用 `18082`，未操作原 `persona` 数据库或原 PostgreSQL 服务。
临时账号、Persona 和请求均为合成数据。既有 Key 用于少量真实模型验证，始终在服务端。

Windows 本机和临时 HTTPS Tunnel 的短请求都实际收到完整 SSE 与 `[DONE]`。
但 [Cloudflare 官方 Quick Tunnel 文档](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)
注明不支持 SSE、无 uptime 保证、重启改变域名；一次观测成功不等于正式支持。
旧“每次换临时域名再重建”的步骤只能当开发临时流程，稳定云端体验仍需受支持的部署入口。
测试服务和临时 Tunnel 应在取证后停止，不把临时 URL 写回原工程。

清理检查已完成：独立数据库的 users/personas/sessions 均为 0；临时后端、Tunnel 和
PostgreSQL 已停止，18082/55542 端口关闭。原 PostgreSQL 5432 仍可访问；原工程保持
`803388f` 且 Git 干净，原 App 更新时间仍为 `2026-09-20 21:58:52`，验证包均已移除。
修改认证后按项目规则另跑实际后端 smoke，输出 `Auth and Persona isolation smoke test passed`。

## Evidence and pending gates

本机忽略目录：`build/verification/p2-quality-windows/`。
它包含源码包与哈希、构建/JUnit、UI01 编译失败、UI02 空响应异常、UI03/04 恢复失败、
UI05 成功回执、真实模型输出、首次保留集异常以及匿名人工评审页面。
开发集 30 组、本地保留集 9 组成对输出与云端三项单配置输出已生成可填写页面。
保留集页面另保留 3 组 unsupported 诊断，不给它们评分。pear 正在人工评审，尚未接收评分文件。
自动浏览器预览因 `file://` URL 安全策略被拒，未绕过；结构和脚本检查通过，未声称视觉验收。

最终又对当前源码运行生命周期固定集：20 pass / 6 unsupported，未发现不匹配，计费网络
拒绝对照通过，源码与实际安装 APK 哈希核对成功。外层汇总脚本最初把省略的零失败计数键
误判为异常，已按已有回执验证零失败，没有篡改观测或为此重跑设备。
评测/评分工具的 73 项 Python 测试通过；评分校验不补写缺项，也不自动决定发布。

P2 最终判定仍需实际人工人格/记忆评审，以及对未明进程退出和网络失败的风险结论。
生命周期能力范围见 [此前记录](p2-lifecycle-status-2026-10-30.md)，不把未来自动提取和
反向 fallback 凑成通过。当前未提交、未推送，不能宣称远程 CI 通过。
