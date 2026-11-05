# P2 组件 adapter：Windows/设备验收 — 2026-10-30

Mac 候选此前仅完成协议和类库检查，无法证明实际 Android 导出能被比较工具读取。
本轮运行 Windows 构建与真实 Room 组件评测，发现并修复 Gson 省略 null 字段的问题。
最终 14 项静态契约通过、12 项明确未支持、0 项不匹配；完整 P2 和模型质量仍未完成。

## 源码和安全范围

- Mac 工作区 `/Users/pear/Developer/Persona`，分支 `codex/p2-product-flow`，base
  `911981a22b81cd63b93fe10ffcd09cfd31b6f5aa`；改动仍未提交或推送。
- Windows 候选 `C:\Users\pear\persona-m1-10c9727`，Git HEAD 仍为
  `10c972785a2e4f2b35ab8d26fa24c35eb601ea47`。实际构建使用 Mac 全量快照，
  329 个源文件逐项 SHA-256 一致；不能把候选目录 HEAD 当作本次源码版本。
- JBR 21.0.8、SDK 36、NDK 27.0.11718014；设备 BLK-AL80、SDK 31、arm64。
  MNN `d407447ed56c4121a11ccbd266dc184ca1ead0c2` 保持干净。
- 只安装独立验证包与测试包，完成取证后均已卸载；未操作原应用数据库、真实账号或云端。
  原应用更新时间仍为 `2026-09-20 21:58:52`。
- Windows 原 Android Studio 工程仍为 `803388f` 且干净，没有修改其源码或模型。

## 失败与修复

首次运行 `m5-dev-20261030-01` 的 Android 构建和 adapter 导出成功，但比较工具返回 2：
Gson 默认略过 Map 中的 null，七个 unsupported 行缺少协议要求的 `actual=null`，
比较报 `ERROR: 'actual'`。该失败和原测试源码均保留，没有把缺失字段当作通过。

将实际导出统一经 `M5Fixtures.observationJson`，使用
`GsonBuilder().serializeNulls().create()`；新增 wire-format 回归先在旧序列化失败，
修复后 Mac 九项协议测试通过。adapter 版本升为 `room-memory-boundary-v2`，
输入协议、数据集、比较规则和生产代码均未放宽或改变。

首次桥接的 Windows host 脚本还遇到父目录未创建，以及 PowerShell 将 unittest stderr
当成异常的问题，已在本地验证辅助脚本中修正；这些不属于 Android 产品缺陷。
Windows 工具测试改由 Python 捕获 stdout/stderr，54 项通过，完整日志保留。

## 实际执行结果

执行入口：

```powershell
python tools/persona-eval/run_device.py --serial 4ACVB24C10039864 --run-id m5-dev-20261030-02
python tools/persona-eval/run_device.py --serial 4ACVB24C10039864 --run-id m5-all-20261030-01 --split all
```

两次构建均调用隔离版本的 testDebugUnitTest、assembleDebug、assembleDebugAndroidTest。
实际输出：

```text
# dev
BUILD SUCCESSFUL in 26s
93 actionable tasks: 9 executed, 84 up-to-date
Time: 1.623
OK (1 test)

# all
BUILD SUCCESSFUL in 12s
93 actionable tasks: 2 executed, 91 up-to-date
Time: 1.369
OK (1 test)
```

JUnit 一项表示整个 adapter 导出成功，案例结果来自逐条比较：

| 运行 | pass | unsupported | 不匹配 | 比较退出码 |
| --- | ---: | ---: | ---: | ---: |
| dev v2 | 11 | 7 | 0 | 1 |
| all v2（含 dev） | 14 | 12 | 0 | 1 |

两次重叠案例不相加。all 补验三个静态场景，包括有效期右边界；没有运行 heldout 模型，
也没有依据保留集模型输出调参。退出 1 是协议要求的不完整状态，
`application_conformance=reported_observations_incomplete`，不是全 P2 通过。

现有 408 项 JVM XML 保持零失败/错误/跳过；生产与 JVM 源码未变，Gradle 将本轮
单测任务标为 UP-TO-DATE，不能描述成重新执行了 408 项测试。
真实 Room、生成 DAO 和 Prompt 组件在设备中执行；没有启动 MNN、下载模型或调用 DeepSeek。

本轮证明 owner/Persona/云端过滤、共享、排序、时效和预算在固定静态输入上的组件行为。
fixture 直接种入隔离内存 Room，不能证明用户写入入口、ChatViewModel、请求取消、
fallback 或最终执行器派发。12 项未接入场景整例 unsupported，不用空观测伪造拒写。

## 绑定与证据

v2 的实际源码输入 revision：
`sha256:4777399a598a6798c46fc13293793d33d1c566f65ffe14fc412374f4f5faf1a2`。
两次运行均核对构建前后源码、bundle 摘要和实际安装 APK 哈希。
App APK SHA-256（生产代码未改）：
`1fd70ac2df611b07f7ec4e58e59ea71120178a4a739003535a2db72e7f191444`。
Test APK SHA-256：
`a9acc4c2f359c47719932ba0bc6552d4bca7efe6f71e7d5f80c187e4736b91e5`。

本机忽略目录 `build/verification/p2-adapter-windows/` 保存初次失败、v2 结果、
原始观测、完整构建和安装/清理日志、JUnit XML、旧源码及修复前后清单。
归档 `p2-m5-device-evidence-2026-10-30.tar.gz` 的 SHA-256：
`ec0d15f4413540501a31f7dfe0006238cd0b8c267d486ffa49078d1975b12185`。
取证摘要见 `device-acceptance.json`，最后交接与文档快照见 `state.json`。
没有新的 P2 PR 或远程 CI 结果。

下一步接入真实请求生命周期的观测边界，并以现有 1.5B 扩大开发集质量评测。
14 项主观案例和人工人格评审仍未执行；0.5B 的已知召回失败也没有被本轮组件通过所解决。
