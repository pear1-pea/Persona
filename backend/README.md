# Persona Backend

这个服务是 Android 客户端唯一的云端入口，负责四件事：邮箱密码认证、session 管理、读写 Persona、代理 DeepSeek streaming 请求。DeepSeek API key 只放在服务端环境变量或云厂商 Secret Manager 中。

## 配置

复制 `.env.example` 为 `.env`，填写：

```text
DATABASE_URL=postgres://persona:change-me@db.example.cn:5432/persona
DEEPSEEK_API_KEY=<server-side secret> # optional until chat is enabled
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL_ID=deepseek-v4-flash
MAX_REQUESTS_PER_MINUTE=30
MAX_AUTH_REQUESTS_PER_MINUTE=10
MAX_OUTPUT_TOKENS=1024
MAX_DAILY_INPUT_CHARS=200000
MAX_DAILY_OUTPUT_TOKENS=30000
MAX_CHAT_CONCURRENT_PER_USER=1
MAX_CHAT_CONCURRENT_GLOBAL=20
CHAT_UPSTREAM_TIMEOUT_MS=120000
SESSION_TTL_DAYS=30
TRUST_PROXY=true
```

用户通过邮箱和密码登录，Backend 以 PostgreSQL 中的 `users.id` 作为唯一身份。密码使用 Argon2id，客户端只持有 session token，数据库只保存 token hash。

邮箱目前只是唯一登录名，不代表已验证邮箱所有权。当前没有公开的“忘记密码”接口；管理员可以使用下面的命令重置密码，重置后所有旧 session 会立即失效：

```bash
node scripts/admin-user.mjs reset-password user@example.com 'new-password'
```

初始化 PostgreSQL：

```bash
psql "$DATABASE_URL" -f schema.sql
```

本地启动：

```bash
npm ci
npm run dev
```

从旧 Firestore 导出 JSON 后导入：

```bash
node scripts/import-personas.mjs ./seed-personas.json
```

输入可以是 Persona 数组，也可以是 `{ "personas": [] }`。每条记录至少需要 `id`、`name`、`creatorId`；`isPublic` 缺失时按公开处理，适用于你已经确认的旧公开 Persona。脚本使用事务和稳定 `id`；如果 system Persona 仍是旧随机 ID，会按 `creator_id + name` 升级为种子文件中的固定 ID，并清理同名旧 system 记录，因此重复执行不会重复创建。导入完成后抽查 `creator_id`、`is_public`、traits 数组以及图片 URL，再切换 Android 的 Backend 地址。

旧 Authing 用户需要先在新系统重新注册，再由管理员绑定旧 `creatorId`：

```bash
node scripts/admin-user.mjs bind-authing user@example.com '<old-authing-creator-id>'
```

该命令会记录身份映射，并把旧 Persona 的 `creator_id` 原子更新为新用户 UUID。不要提供客户端自助认领接口。

健康检查为 `GET /healthz`。生产部署时使用 HTTPS 域名，例如 `https://api.example.cn/`，把这个带根路径的 URL 配置到 Android 项目的 `local.properties`：

```properties
PERSONA_BACKEND_BASE_URL=https://api.example.cn/
```

## API 权限

认证接口：

```text
POST /api/auth/register
POST /api/auth/login
POST /api/auth/logout
GET  /api/auth/me
POST /api/auth/change-password
DELETE /api/auth/account
GET  /api/auth/sessions
POST /api/auth/sessions/revoke-others
DELETE /api/auth/sessions/:id
```

`GET /api/personas/public` 可以匿名访问，只返回 `is_public = true` 的记录。其他 Persona API 和 `/api/chat/completions` 都必须携带 session token。

`DELETE /api/auth/account` 还必须提交 `{ "currentPassword": "..." }`，服务端会在删除事务中重新验证 Argon2id password。

创建 Persona 时客户端不能提交 `creatorId`，服务端始终使用当前 session 的 `user.id`。详情查询只返回公开记录或当前用户自己的记录，删除同样按 `user.id` 做服务端条件删除。

Persona owner 可以通过 `PATCH /api/personas/:id` 编辑名称、traits、backstory 和公开状态。账号注销会删除该用户的 Persona 和所有 session。

管理员命令：

```bash
node scripts/admin-user.mjs disable user@example.com
node scripts/admin-user.mjs enable user@example.com
node scripts/admin-user.mjs reset-password user@example.com 'new-password'
```

## DeepSeek proxy

`POST /api/chat/completions` 只接受与 `DEEPSEEK_MODEL_ID` 相同的 model、最多 `MAX_MESSAGES` 条消息和受限消息长度，并按用户 ID 做分钟级限流。Backend 还限制每用户和单进程全局并发、每日输入/输出预算。未配置 `DEEPSEEK_API_KEY` 时，认证和 Persona API 仍可启动，chat endpoint 返回 `503`。服务端日志不要记录 Authorization、完整 prompt 或完整响应。

保留既有 `model/messages/stream/temperature/top_p/thinking` 字段；合法生成参数会透传，省略时仍使用 `temperature=0.7`、`top_p=0.8`、`thinking.type=disabled`。`temperature` 范围为 `[0, 2]`，`top_p` 为 `(0, 1]`，`thinking.type` 只能是 `enabled/disabled`。可选 `max_tokens` 必须是正整数且不超出 JavaScript 安全整数范围，发送上游时取它与 `MAX_OUTPUT_TOKENS` 的较小值。未传时使用服务端上限。Android 无需同步修改；不支持的字段仍返回 `400`。服务端固定请求最终 usage，客户端不能覆盖此选项。

预算采用两个 bucket 的同一 PostgreSQL 事务预留：输入按消息字符数、输出按本次 `max_tokens` 上限。并发拒绝、校验失败或任一预算不足都不扣预算（分钟请求限流仍计次）。结算语义如下：

- 发出上游请求前失败，或上游明确返回非 2xx：释放两项预留。401/403 对客户端映射为 502；429 保留状态和 `Retry-After`，只返回安全错误码，不透出上游错误原文。
- 请求已发出且没有明确拒绝：输入字符计入用量；拿到终止 chunk 的有效 `usage.completion_tokens` 后按实际输出结算，包括上游报告的 reasoning token 成本。重复 usage 不会重复退款，实际用量超出预留也会记账。
- 取消、超时、网络断流、格式错误或缺少最终 usage 时，已经收到的最终 usage 仍有效；没有它则保留输出上限预留。这是保守额度统计，不代表已知供应商的准确账单，不能根据已转发文本推算退款。
- 结算原子执行一次，旧窗口的结算不会退款到新的 24 小时窗口。数据库结算失败会记录 `chat_budget_settlement_failed` 并保留原预留；进程崩溃也可能留下预留，当前没有持久化请求账本或自动对账恢复。

`CHAT_UPSTREAM_TIMEOUT_MS` 从预留前开始计时，限制 fetch 和背压等待的总时长，持续输出不会重置；数据库操作不能被该 AbortSignal 即时打断，预留返回后会先检查取消再决定是否发出上游请求。客户端 close/error/abort 或 deadline 都会取消 fetch 和 drain 等待，并在退出时释放并发槽。上游必须返回 `text/event-stream` 和 `data: [DONE]`；SSE 字节保持原样转发，单帧最多 1,000,000 字符。尚未输出时的格式错误返回 502，开始输出后的断流/格式错误直接中断连接，不伪造成功结束。

限流记录保存在 PostgreSQL，可用于多实例部署。若 Nginx/API Gateway 已清洗并重写 `X-Forwarded-For`，设置 `TRUST_PROXY=true`；否则保持 `false`，避免客户端伪造 IP。

每日预算可跨实例共享；并发槽仍是进程内计数，多实例总并发需要网关或共享协调另行限制。

本地验证：

```bash
npm run typecheck
npm test
TEST_DATABASE_URL=postgres://test_user@127.0.0.1:5432/persona_test npm run test:integration
```

集成测试只使用固定假 key 和本机可控 HTTP 上游，不需要配置真实 DeepSeek key。`TEST_DATABASE_URL` 必须指向允许创建 schema/安装 `citext` 的测试库；每次创建独立随机 schema 并在结束时删除，后台进程的 `DATABASE_URL` 和云端配置全部替换为测试值。未提供此变量时集成测试显示 skipped。覆盖慢首包、增量/持续输出、断流、取消、429、SSE 格式与分片、暂停 TCP 读取造成的背压、并发槽回收、预算竞争/回滚/结算幂等/窗口切换，并运行既有双用户 auth/persona smoke。

部署后运行双用户权限验收：

```bash
PERSONA_BACKEND_BASE_URL=https://api.example.cn npm run smoke
```

该脚本会创建两个临时用户，验证公开 Persona 可读、私有 Persona 隔离、跨用户删除被拒绝、session 列表可用，并在结束时删除临时账号。
