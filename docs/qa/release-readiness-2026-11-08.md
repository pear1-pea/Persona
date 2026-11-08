# 公开商店发布：通用准备记录

日期：2026-11-08。范围：先完成不依赖商店的工程准备，尚未选定商店。
当前缺口是 Debug 验收不能证明 Release 构建和原生兼容性，且旧 Zod 依赖存在已登记的 high 告警。
本轮修复依赖、补充 Release 检查与产物回执；正式包名、签名和稳定服务未提供，当前不能上架。

## 已确认决策与原修复交付

- pear 选择公开商店发布，暂未确定商店，正式 applicationId、Release 签名文件及稳定 HTTPS 后端均没有。
- 不代选永久包名，不生成临时签名冒充正式签名，不把旧 QA tunnel 写入分发包。
- 取消后零输出修复和 Qwen3 研究工具已通过 [PR #9 CI](https://github.com/pear1-pea/Persona/actions/runs/42482858226)，由 [PR #9](https://github.com/pear1-pea/Persona/pull/9) 合入 `master@fd5c12b`。
- Qwen3 固定提取质量0/6，仍不采用；不恢复人工评分任务，也不把质量失败记成通过。

## 本地完成的发布修复

Zod 从3.25.76升级到精确4.6.7，未升级其他依赖、未使用 `zod/v3` 兼容解析器。
直接升级会收紧 UUID 版本/variant 校验，因此使用 v4 `z.guid()` 保留原十六进制 ID 合同。
增加请求边界、默认值、禁止 creatorId、嵌套严格对象与真实本地 HTTP 错误响应的回归测试。

```text
npm ci
found 0 vulnerabilities
npm run typecheck
exit 0
npm test
tests 28 / pass 28 / fail 0 / skipped 0
npm audit --omit=dev
vulnerabilities total=0

python3 -B -m unittest discover -s tools/release/tests -v
Ran 10 tests
OK
```

旧版本新增合同28项通过，直接升级后 UUID 兼容测试失败，最小适配后28项全绿。
audit 是本次注册表观测，不是永久无漏洞承诺；CI 新增生产依赖 high/critical 阻断。
Mac 未执行数据库集成或 Android 构建，后续结果以同一 PR 的远程检查为准。

## 新增的实际产物验收

CI 保留现有六个自动 job；Windows job 仍为手动，不因其跳过宣称 Windows 已跑。
Android job 在 Debug 之后增加：

```text
./gradlew assembleRelease bundleRelease testReleaseUnitTest
python3 tools/release/check_apk.py <unsigned-apk> --source-revision <checkout-sha> --output <report>
zipalign -c -P 16 -v 4 <unsigned-apk>
```

检查实际 APK 中每个 `.so` 的 arm64 ELF64 格式、LOAD 范围、至少16KiB ELF对齐；另用 SDK 工具检查 ZIP 对齐。
记录源码身份、APK及原生库哈希。`ready_for_distribution` 始终为 false，签名、后端和真机状态各自保留。
十项本地测试验证检查器的行为；只有 CI 对真实 Release 产物执行成功，才构成静态兼容性证据。

本次 PR 的 `android-reports` 中保存 Debug/Release Gradle 日志、两种 JVM 结果、`release-inspection.json` 与 `release-zipalign.log`。
`release-unsigned-not-for-installation` 保存未签名 APK/AAB，保留七天；AAB只做生成检查，分发配置和生成 split APK 另行验收。
实际远程运行状态、最终 commit 与产物以本次 PR Checks 和主分支流水线为准，不从本地夹具测试推断通过。

## 上架前仍需补齐

| 项目 | 当前状态 | 放行证据 |
| --- | --- | --- |
| 商店及正式应用身份 | 商店未选定，仍为 com.example.persona | 确认应用归属、唯一包名和版本策略 |
| 正式签名与恢复 | 无签名文件 | 确认签名归属、备份/恢复及签名证书；校验最终签名包 |
| 服务入口 | 无稳定 HTTPS 后端 | 确认部署与数据库、版本对应地址；真实登录、Persona、持续SSE与取消验收 |
| 签名包安装/升级 | 未运行 | 对目标包、签名和版本验证新装及升级后的数据保留，不操作未备份的原 App |
| Native 16KiB运行 | 本轮静态检查，运行未验收 | 在对应 arm64/16KiB 系统安装、加载模型、生成和取消 |
| 模型交付 | 继续使用已验收 Qwen2.5，不采用Qwen3 | 核对实际分发包、许可/来源声明、SAF导入与完整性 |
| 商店资料 | 未选渠道，尚未定稿 | 依所选商店准备真实功能说明、隐私/数据声明、运营联系和截图 |
| 生产部署 | 既有 QA 不能替代 | 部署后的账号删除、权限、预算和流式检查；healthz成功不等于数据库/上游可用 |

目前 Release 不启用 R8；不得因为“发布”字样就立即开启并宣称兼容。正式签名和包名确认后再做相应 Release 回归。
现有 Dockerfile 默认以root运行、数据库初始化与管理员脚本在镜像之外；部署方案确认后补齐其执行和验收，不用QA后端结果替代。
现有本地模型仅覆盖已验证设备与热准入范围；新设备、长时间负载和模型质量的限制保留在原验收记录中。

## 首轮真实试用记录方式

拿到可分发的签名候选后，先在受控账号和目标设备验证创建/发现Persona、聊天/停止/重发、进程重启恢复、模型安装切换、记忆保存修订删除、退出和注销。
记录版本/签名、设备/系统、模型包、操作、预期/实际行为与复现条件；避免上传私人聊天原文或凭证。
问题按崩溃/数据或权限错误、流程阻断、体验/质量分别处理。每个问题以实际复现和回归关闭，不预设已经存在尚未观察到的用户缺陷。
试用不要求重新做大规模人工评分；人格与记忆质量仍按实验能力说明。

相关入口：[Release 检查工具](../../tools/release/README.md)、[构建配置](../agent/build.md)、[最近真机实验](qwen3-native-probe-windows-2026-11-08.md)。

Sources:
- [Zod high 告警与修复范围](https://github.com/advisories/GHSA-mrhx-c7xc-m3w5)
- [Zod 4 迁移说明](https://zod.dev/v4/changelog)
- [Android 原生16KiB检查及运行验证](https://developer.android.com/guide/practices/page-sizes)
