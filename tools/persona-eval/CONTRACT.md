# 确定性契约 v2

此协议映射本次 M6 的显式记忆实现，不代表应用已经通过评测。
adapter 必须调用生产路径并记录证据；不得把 expected 或 oracle 输出当作实测。

## 字段与来源

记忆身份为 `(owner, memory_id)`，scope 变化不创建新身份。生产表的 ID 全局唯一；
fixture 中不同 owner 的同名 ID 是权限攻击样例，adapter 可映射为不同物理 ID。
同一身份的多版本 fixture 表示一个可修订行的快照，取最新版，不能退回已删除旧版。

| Fixture | Production / 约束 |
| --- | --- |
| owner | 可信会话的 ownerId |
| persona | personaId；null 表示用户勾选共享到此身份下全部 Persona |
| kind / version | MemoryType / revision |
| deleted / expires_at | 删除状态 / expiresAt；右边界已过期 |
| cloud_allowed | 独立云端授权，本地共享不隐含授权 |
| source_message_id | 单个原始 NORMAL 用户消息 ID，手动笔记为 null |
| created_at / updated_at | 带时区的合成时间，adapter 转毫秒 |
| ref | 只用于评测的证据版本标签 |

`save_source` 是写入路径的测试元数据，不是新的数据库字段。手动来源须明确确认；
消息来源只允许匹配 owner、scope 的 NORMAL 用户消息。助手输出、失败输出、角色虚构、
模拟 Feed 和未确认候选不自动写入。用户主动手写一条资料属于明确确认，内容不等于
经过客观事实核查。首版没有自动提取、多消息来源、未来生效时间或细粒度共享名单。

## 检索、预算与修改

先过滤 owner、persona、deleted、expiresAt 和云端权限；按 updatedAt 降序、id 升序
选择最新最多 4 条，记录 candidates。注入前再次复核权限与状态，单条内容 1..400、
累计不超过 1200 UTF-16 单位；完整保留可用记录，不截断内容，记录 injected。
英文/中文/emoji 均按 Kotlin String.length 的 UTF-16 单位计数。

key/query_keys 仅供人工评审标记主题，不用于首版候选过滤。允许候选中有无关事实，
生成层必须只使用相关事实、对未知信息澄清；这由主观轨道检查，不能伪称已实现检索质量。

executor 事件模拟允许的 fallback，必须重新授权上下文。revise、delete、revoke_cloud
停止当前旧请求；resend 明确表示用户重新发送，才可注入修订后的记录。cancel 之后
单纯 fallback 不会重启请求。delete 保留 tombstone，后续 replay/旧编辑不能复活同一 ID；
被拒绝的写入记录 rejected_writes。这里不承诺阻止用户主动创建新的同义笔记。

生产实现另会排除受影响范围的旧聊天上下文，并排除含记忆轮次的自动重放。
原聊天仍可查看，已经发出的内容不能被追溯撤回。fixture 不替代真实取消、事务、
迁移、来源删除、跨进程和历史出端回归，这些由 Android/JVM 用例覆盖。

## 模型输入与验收

用例独立初始化。只向模型传入最终允许的证据、persona_profile.personaBackstory
与 messages；expected、rubric、events 和所有未授权记录都不能成为模型输入。
固定合成 Persona 为书屋管理员岚，不代表产品必须全局固定中文；生产规则仍跟随用户语言。

应用观测必须包含对应 split 的全部确定性案例、revision 或脏工作区快照、adapter 版本、
测试日志与数据集 SHA-256。compare 只比较被报告的证据集合，不能验证提供者真的运行过应用。
默认组件模式覆盖 Room/仓库/Prompt。显式启用 v4 生命周期与受控网络对照后，
设备验证 20 项通过（14 静态、6 请求）、6 项 unsupported，
完整应用 conformance 仍为 reported_observations_incomplete。
模型与人工评审也保持 not_run，
Python 工具测试与 26 项参考契约通过均不能证明应用权限或人格质量合格。

## 当前应用映射限制

手写保存就是用户确认；当前没有自动候选的 confirmed/provenance API。
dev-16/17、heldout-01～03 的未确认或非用户来源场景，不能把“未调用保存”报告成
“生产拒写”，也不能在 adapter 中复制 save_allowed 后冒充应用校验。
来源消息的 NORMAL、用户身份和作用域限制可直接在生产 DAO 写入路径观测。

当前只实现符合授权的本地失败转云，没有云失败自动转本地策略。
heldout-07 可以作为执行器重授权的设计契约；手动调用 prepareContext 并不能证明
该自动恢复行为已实现。fixture 时间、物理 ID 和 candidates/注入两处观测还须
与生产时钟、排序和生命周期对齐；未接入的请求边界继续保留未支持/未执行状态。

`room-memory-boundary-v2` 将 fixture 时间相对平移到设备当前时间，不修改系统时钟；
用保序的 UTF-16 十六进制 ID 加 owner 摘要避免同名 ID 冲突。候选来自真实 DAO，
注入列表来自真实仓库并核对实际 MemoryPrompt 的 JSON 事实；这里的 injected 仅表示
进入 Prompt 组件输出，不证明执行器接收、模型遵守或取消中的派发边界。
快照直接种入独立 Room，不能用此证明保存入口的来源验证；本组件没有执行拒写的案例。

请求生命周期事件和未接入的自动来源入口整例标 `unsupported`，不预先过滤非法记忆。
输出必须附原因且 actual=null；compare 保留这些行并退出 1，状态为
`reported_observations_incomplete`。`not_run`、丢案例和畸形输入仍拒绝，不自动降为 unsupported。

以上整例跳过事件描述的是默认组件模式。`--with-lifecycle` 新增真实请求取消、撤权、删除、
修订重发、删除后拒绝重写及 local→cloud 回退观测；传输和引擎为明确替身，不测模型质量。
计费设备可显式加 `--controlled-network`，同一真实编排验证 METERED 禁止回退和
UNMETERED 允许回退，记录物理/策略两种网络状态；生产仍读取真实网络，测试不改变热点。
该 control 不增加固定案例通过数。五个未实现来源入口及 cloud→local 策略继续 unsupported。

dev 用于开发；冻结配置后才运行 heldout 的真实模型评测。v2 是首版冻结前将契约对齐
当前 M6，未针对 heldout 模型输出调参。保留集公开可检查 schema，但不得据此声称统计独立。
