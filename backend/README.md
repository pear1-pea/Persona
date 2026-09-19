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
npm install
npm run dev
```

从旧 Firestore 导出 JSON 后导入：

```bash
node scripts/import-personas.mjs ./personas.json
```

输入可以是 Persona 数组，也可以是 `{ "personas": [] }`。每条记录至少需要 `id`、`name`、`creatorId`；`isPublic` 缺失时按公开处理，适用于你已经确认的旧公开 Persona。脚本使用事务和 `id` upsert，重复执行不会重复创建。导入完成后抽查 `creator_id`、`is_public`、traits 数组以及图片 URL，再切换 Android 的 Backend 地址。

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

`POST /api/chat/completions` 只接受 allowlist 中的 model、最多 `MAX_MESSAGES` 条消息和受限消息长度，并按用户 ID 做分钟级限流。Backend 还限制每用户和全局并发、每日输入/输出预算，并固定上游 `max_tokens`。未配置 `DEEPSEEK_API_KEY` 时，认证和 Persona API 仍可启动，chat endpoint 返回 `503`。上游请求断开时会取消 DeepSeek fetch。服务端日志不要记录 Authorization、完整 prompt 或完整响应。

限流记录保存在 PostgreSQL，可用于多实例部署。若 Nginx/API Gateway 已清洗并重写 `X-Forwarded-For`，设置 `TRUST_PROXY=true`；否则保持 `false`，避免客户端伪造 IP。

部署后运行双用户权限验收：

```bash
PERSONA_BACKEND_BASE_URL=https://api.example.cn npm run smoke
```

该脚本会创建两个临时用户，验证公开 Persona 可读、私有 Persona 隔离、跨用户删除被拒绝、session 列表可用，并在结束时删除临时账号。
