# P2 请求生命周期验收 — 2026-10-30

上一批只测到 Room/仓库/Prompt 组件，未覆盖记忆修改、取消和执行器切换中的真实请求。
本轮接入 ChatViewModel、Hybrid、真实 Room 和受控传输，发现并修复迟到通知误取消新请求、
云端记忆来源删除监测不完整的问题。最终固定集 20 项通过、6 项未支持、0 项不匹配。
这不是模型质量结论，P2 仍待扩大真实 1.5B 质量评测及人工评审。

## 版本与边界

- 工作区 `/Users/pear/Developer/Persona`，分支 `codex/p2-product-flow`，base
  `911981a22b81cd63b93fe10ffcd09cfd31b6f5aa`；尚未提交、推送，没有新远程 CI。
- Windows 仍使用独立候选 `C:\Users\pear\persona-m1-10c9727`，目录 HEAD 为
  `10c9727`，实际构建以 335 个逐项哈希匹配的源文件为准。
- Android SDK 36、JBR 21.0.8、NDK 27.0.11718014；BLK-AL80、SDK 31、arm64。
  MNN submodule 保持 `d407447ed56c4121a11ccbd266dc184ca1ead0c2` 且干净。
- 生产改动仅在 ChatViewModel 的请求记忆状态及 Hybrid 内部网络读取依赖。
  Schema、隐私规则、采样、模型选择和实际网络设置不变。

`room-and-lifecycle-boundary-v4` 保留 14 个静态 Room 组件案例，并新增六项真实编排验证：
撤销云授权、local→cloud 失败回退、修订后重新发送、删除、删除后拒绝重写、用户取消。
真实 AuthManager、Room 仓库、ChatViewModel、HybridRouter、Hybrid 和云端上下文规划器运行；
只替换 LocalAiEngine 与 CloudChatApi，输出固定合成文本。dummy model 包仅用于真实
validator/manager 的生命周期准入，不能加载为 MNN 模型，也不作为模型工件交付。

等待点位于用户消息实际保存后、路由/执行器派发前。变更或取消必须使原 request job 退出，
再释放等待点也不得恢复派发；resend 使用新 session，必须产生 NORMAL Room 终态且未被停止。
回退检查实际执行器边界的引用，云端消息不能携带私有记忆或本地部分输出。

## 发现的问题及修复

1. 写入 epoch 通知或 Room 修订通知迟到时，会直接 stopGenerating，误取消刚重新发送的
   新请求。现在 send 时绑定 epoch/session，选入证据后记录快照，旧回调仅在仍匹配当前
   session 且当前证据已改变时停止。两个调度回归先在旧实现失败，再通过修复。
2. 本地和云端各自过滤并取前四条；云端使用的较旧授权记忆可能不属于 local top four。
   只复查 local 选择会漏掉该记忆的 source 删除。现在独立跟踪 cloudEvidence，并在登记
   删除监测后再次查询云端选择。两项来源删除/准备窗口回归先红后绿。
3. 测试清理原来可能在 job join 超时后跳过其余资源释放；已将清理放进 finally。
   验收还要求 fresh resend 真正完成，不能只看它曾到达 dispatch。
4. 首次生命周期 instrumentation 失败后，runner 原先在导出诊断前清理了验证包。
   现在即使 JUnit 失败也先尝试保留私有 observations，再判断结果并清理。
   该 runner 回归先红后绿；失败位置只记录类名/栈位置，不记录完整 Prompt 或响应。

首次 `m5-life-dev-20261030-01` 保留构建、失败栈和清理日志，未取得已删除私有诊断，
因此不对那次 instrumentation 的具体根因作确定归因。
第二次实体网络运行 dev 得到 15 pass / 3 unsupported，其中一项为计费网络条件阻断。
这些历史结果不计入最终通过数量。

## 热点与网络策略对照

实际手机网络始终为 METERED，Mac/Windows 继续使用热点，未切换校园 Wi-Fi。
生产公开注入构造仍读取真实 RoutingContextProvider；Hybrid 的 internal 构造接收网络读取函数，
仅在显式测试开关下提供受控输入。生产计费网络仍禁止自动转云，用户主动选云的既有规则不变。

`--controlled-network` 必须配合 `--with-lifecycle`：同一真实编排分别执行
METERED（本地失败，零云派发，最终 FAILED）和 UNMETERED（本地失败后云派发并完成）两组。
实际传输均为内存替身，没有真实网络请求或费用。导出分别记录 physicalNetworkState 和
policyNetworkState，host receipt 要求计费拒绝对照通过；不将其描述为真实移动流量上传测试。

## 验证结果

Mac 定向检查：Chat/Prompt/Session 29 项、Hybrid 42 项、协议 10 项通过。
Windows 全套实际执行的 JVM 测试统计为 412，零失败/错误/跳过；Python 工具 57 项通过。

```text
# dev：m5-life-dev-20261030-03
BUILD SUCCESSFUL in 58s
93 actionable tasks: 25 executed, 68 up-to-date
Time: 3.934
OK (1 test)

# all：m5-life-all-20261030-01
BUILD SUCCESSFUL in 12s
93 actionable tasks: 2 executed, 91 up-to-date
Time: 8.411
OK (1 test)
```

| 运行 | pass | unsupported | 不匹配 | 其中生命周期案例 |
| --- | ---: | ---: | ---: | ---: |
| dev | 16 | 2 | 0 | 5 |
| all（含 dev） | 20 | 6 | 0 | 6 |

两轮重叠不相加；20 项由 14 个静态组件案例和 6 个请求案例组成，不能称为 20 项完整端到端测试。
计费拒绝 control 独立记录，不增加固定案例通过数。JUnit 一项只表示整份观测导出完成。
comparison 退出 1，application_conformance 仍为 reported_observations_incomplete。
五项自动候选来源入口及 cloud→local 自动回退尚未实现，保持 unsupported。
它们是后续能力边界，不能填空结果凑满通过；固定数据和参考期望均未修改。

## 证据与清理

最终构建输入 revision：
`sha256:e196a9cdc7293d92b7e42e5417077294241c6e7933eb2f673f56a8d8b7f77e0a`。
App APK SHA-256：
`d40af70ed0ca6e2e8d7da64b5cd751fd9ae4babe99e5daf35144961c8444e893`。
Test APK SHA-256：
`f7187dc692cc640e09f5ef6496a353143e64289b6836f57689b78b81eabb7be2`。

忽略目录 `build/verification/p2-lifecycle-windows/` 保存完整构建、412 项 JUnit XML、
57 项 Python 输出、各轮原始观测、源码修复前后清单、source/APK/bundle receipt 和清理日志。
归档 `p2-lifecycle-evidence-2026-10-30.tar.gz` 的 SHA-256：
`dae3a95d79641b17af56d7add0a1ecb9c611e365cdd3bce22cc2e1ca07a82f57`。
Mac 的失败与成功定向日志仍保留在 `p2-memory-mac/runs`、`p2-adapter-mac` 和
`p2-lifecycle-mac` 对应忽略目录；原始窗口测试和本轮测试均可追溯。

验证 App/Test 包均已卸载；原 Persona 更新时间仍为 `2026-09-20 21:58:52`。
Windows 原 Android Studio 工程仍为 `803388f` 且干净，未修改手机热点、原模型或原应用数据。
没有使用真实个人聊天进行评测，没有真实云调用，也没有新增模型工件。

下一步以现有 1.5B、固定 Prompt/参数/上下文版本扩大开发集质量评测，再执行配置冻结后的
保留集和人工人格评审。0.5B 已知的不稳定召回仍保留为失败基线，本轮没有证明其已改善。

Sources: [Room 数据库测试](https://developer.android.com/training/data-storage/room/testing-db)、
[AndroidJUnitRunner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)。
