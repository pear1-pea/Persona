import "dotenv/config"
import { createServer, IncomingMessage, ServerResponse } from "node:http"
import { createHash, randomBytes, randomUUID } from "node:crypto"
import argon2 from "argon2"
import { Pool, type QueryResultRow } from "pg"
import { z } from "zod"

const port = Number(process.env.PORT ?? 8080)
const databaseUrl = requiredEnv("DATABASE_URL")
const deepSeekApiKey = process.env.DEEPSEEK_API_KEY?.trim() ?? ""
const deepSeekBaseUrl = (process.env.DEEPSEEK_BASE_URL ?? "https://api.deepseek.com").replace(/\/$/, "")
const deepSeekModelId = process.env.DEEPSEEK_MODEL_ID ?? "deepseek-v4-flash"
const maxRequestsPerMinute = Number(process.env.MAX_REQUESTS_PER_MINUTE ?? 30)
const maxAuthRequestsPerMinute = Number(process.env.MAX_AUTH_REQUESTS_PER_MINUTE ?? 10)
const maxMessages = Number(process.env.MAX_MESSAGES ?? 24)
const maxMessageChars = Number(process.env.MAX_MESSAGE_CHARS ?? 12000)
const maxOutputTokens = Number(process.env.MAX_OUTPUT_TOKENS ?? 1024)
const maxDailyInputChars = Number(process.env.MAX_DAILY_INPUT_CHARS ?? 200_000)
const maxDailyOutputTokens = Number(process.env.MAX_DAILY_OUTPUT_TOKENS ?? 30_000)
const maxChatConcurrentPerUser = Number(process.env.MAX_CHAT_CONCURRENT_PER_USER ?? 1)
const maxChatConcurrentGlobal = Number(process.env.MAX_CHAT_CONCURRENT_GLOBAL ?? 20)
const chatUpstreamTimeoutMs = Number(process.env.CHAT_UPSTREAM_TIMEOUT_MS ?? 120_000)
const sessionTtlDays = Number(process.env.SESSION_TTL_DAYS ?? 30)
const trustProxy = process.env.TRUST_PROXY === "true"
const activeChatByUser = new Map<string, number>()
let activeChatGlobal = 0

const pool = new Pool({ connectionString: databaseUrl, max: 10 })
setInterval(() => {
  pool.query("DELETE FROM rate_limits WHERE window_start < NOW() - INTERVAL '5 minutes'")
    .catch(() => undefined)
  pool.query("DELETE FROM usage_limits WHERE window_start < NOW() - INTERVAL '2 days'")
    .catch(() => undefined)
  pool.query("DELETE FROM sessions WHERE expires_at < NOW() OR revoked_at < NOW() - INTERVAL '30 days'")
    .catch(() => undefined)
}, 60_000).unref()

const createPersonaSchema = z.object({
    name: z.string().trim().min(1).max(120),
    traits: z.array(z.string().trim().min(1).max(80)).max(20),
    backstory: z.string().trim().min(1).max(12000),
    isPublic: z.boolean().default(true)
}).strict()

const updatePersonaSchema = createPersonaSchema

const authSchema = z.object({
  email: z.string().trim().toLowerCase().email().max(320),
  password: z.string().min(8).max(256)
}).strict()

const changePasswordSchema = z.object({
  currentPassword: z.string().min(8).max(256),
  newPassword: z.string().min(8).max(256)
}).strict()

const deleteAccountSchema = z.object({
  currentPassword: z.string().min(8).max(256)
}).strict()

const chatSchema = z.object({
  model: z.string().trim().min(1).max(100),
  messages: z.array(z.object({
    role: z.enum(["system", "user", "assistant"]),
    content: z.string().max(maxMessageChars)
  }).strict()).min(1).max(maxMessages),
  stream: z.literal(true),
  temperature: z.number().min(0).max(2).optional(),
  top_p: z.number().min(0).max(1).optional(),
  thinking: z.object({ type: z.string().max(40) }).optional()
}).strict()
const personaIdSchema = z.string().uuid()
const sessionIdSchema = z.string().uuid()

type AccountUser = QueryResultRow & {
  id: string
  email: string
}

type SessionUser = AccountUser & {
  sessionId: string
}

type PasswordRow = QueryResultRow & { password_hash: string }
type RateLimitRow = QueryResultRow & { request_count: number }
type UsageRow = QueryResultRow & { usage_amount: number }

const dummyPasswordHash = await argon2.hash("persona-invalid-password", { type: argon2.argon2id })

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

function hashSessionToken(token: string): Buffer {
  return createHash("sha256").update(token, "utf8").digest()
}

function createSessionToken(): string {
  return randomBytes(32).toString("base64url")
}

function sessionExpiry(): Date {
  return new Date(Date.now() + sessionTtlDays * 24 * 60 * 60 * 1000)
}

async function issueSession(user: AccountUser, request: IncomingMessage): Promise<Record<string, unknown>> {
  const token = createSessionToken()
  const expiresAt = sessionExpiry()
  await pool.query(
    `INSERT INTO sessions (id, user_id, token_hash, user_agent, ip_address, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6)`,
    [
      randomUUID(),
      user.id,
      hashSessionToken(token),
      request.headers["user-agent"] ?? null,
      clientAddress(request),
      expiresAt
    ]
  )
  return {
    token,
    expiresAt: expiresAt.toISOString(),
    user: { id: user.id, email: user.email }
  }
}

function clientAddress(request: IncomingMessage): string {
  if (trustProxy) {
    const forwarded = request.headers["x-forwarded-for"]
    const first = Array.isArray(forwarded) ? forwarded[0] : forwarded?.split(',')[0]
    if (first?.trim()) return first.trim()
  }
  return request.socket.remoteAddress ?? "unknown"
}

async function authenticateSession(request: IncomingMessage): Promise<SessionUser | null> {
  const header = request.headers.authorization
  if (!header?.startsWith("Bearer ")) return null
  const token = header.slice("Bearer ".length).trim()
  if (!token) return null
  const result = await pool.query<SessionUser>(
    `SELECT u.id, u.email, s.id AS "sessionId"
     FROM sessions s
     JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = $1
       AND s.revoked_at IS NULL
       AND s.expires_at > NOW()
       AND u.is_disabled = FALSE`,
    [hashSessionToken(token)]
  )
  if (result.rowCount === 0) return null
  await pool.query(
    `UPDATE sessions SET last_used_at = NOW()
     WHERE token_hash = $1 AND last_used_at < NOW() - INTERVAL '1 minute'`,
    [hashSessionToken(token)]
  )
  return result.rows[0]
}

async function registerUser(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const input = authSchema.parse(await readJson(request))
  const email = normalizeEmail(input.email)
  if (!(await allowRequest(`auth-register:${clientAddress(request)}`, maxAuthRequestsPerMinute)) ||
      !(await allowRequest(`auth-register-email:${email}`, maxAuthRequestsPerMinute))) {
    throw httpError(429, "rate_limited")
  }
  const passwordHash = await argon2.hash(input.password, { type: argon2.argon2id })
  const client = await pool.connect()
  try {
    await client.query("BEGIN")
    const result = await client.query<AccountUser>(
      `INSERT INTO users (id, email, password_hash)
       VALUES ($1, $2, $3)
       RETURNING id, email`,
      [randomUUID(), email, passwordHash]
    )
    const token = createSessionToken()
    const expiresAt = sessionExpiry()
    await client.query(
      `INSERT INTO sessions (id, user_id, token_hash, user_agent, ip_address, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [
        randomUUID(),
        result.rows[0].id,
        hashSessionToken(token),
        request.headers["user-agent"] ?? null,
        clientAddress(request),
        expiresAt
      ]
    )
    await client.query("COMMIT")
    writeJson(response, 201, {
      token,
      expiresAt: expiresAt.toISOString(),
      user: result.rows[0]
    })
  } catch (error) {
    await client.query("ROLLBACK")
    if (isUniqueViolation(error)) throw httpError(409, "email_already_registered")
    throw error
  } finally {
    client.release()
  }
}

async function loginUser(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const input = authSchema.parse(await readJson(request))
  const email = normalizeEmail(input.email)
  if (!(await allowRequest(`auth-login:${clientAddress(request)}`, maxAuthRequestsPerMinute)) ||
      !(await allowRequest(`auth-login-email:${email}`, maxAuthRequestsPerMinute))) {
    throw httpError(429, "rate_limited")
  }
  const result = await pool.query<AccountUser & PasswordRow>(
    `SELECT id, email, password_hash FROM users WHERE email = $1 AND is_disabled = FALSE`,
    [email]
  )
  const passwordHash = result.rows[0]?.password_hash ?? dummyPasswordHash
  const passwordMatches = await argon2.verify(passwordHash, input.password)
  if (result.rowCount === 0 || !passwordMatches) {
    throw httpError(401, "invalid_credentials")
  }
  writeJson(response, 200, await issueSession(result.rows[0], request))
}

async function logoutUser(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const token = bearerToken(request)
  if (token) {
    await pool.query(
      "UPDATE sessions SET revoked_at = NOW() WHERE token_hash = $1",
      [hashSessionToken(token)]
    )
  }
  response.writeHead(204)
  response.end()
}

async function changePassword(
  request: IncomingMessage,
  response: ServerResponse,
  user: SessionUser
): Promise<void> {
  const input = changePasswordSchema.parse(await readJson(request))
  const result = await pool.query<PasswordRow>(
    "SELECT password_hash FROM users WHERE id = $1 AND is_disabled = FALSE",
    [user.id]
  )
  if (result.rowCount === 0 ||
      !(await argon2.verify(result.rows[0].password_hash, input.currentPassword))) {
    throw httpError(401, "invalid_credentials")
  }
  const passwordHash = await argon2.hash(input.newPassword, { type: argon2.argon2id })
  const client = await pool.connect()
  try {
    await client.query("BEGIN")
    await client.query(
      "UPDATE users SET password_hash = $1, updated_at = NOW() WHERE id = $2",
      [passwordHash, user.id]
    )
    await client.query(
      "UPDATE sessions SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL",
      [user.id]
    )
    const token = createSessionToken()
    const expiresAt = sessionExpiry()
    await client.query(
      `INSERT INTO sessions (id, user_id, token_hash, user_agent, ip_address, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [
        randomUUID(),
        user.id,
        hashSessionToken(token),
        request.headers["user-agent"] ?? null,
        clientAddress(request),
        expiresAt
      ]
    )
    await client.query("COMMIT")
    writeJson(response, 200, {
      token,
      expiresAt: expiresAt.toISOString(),
      user: { id: user.id, email: user.email }
    })
  } catch (error) {
    await client.query("ROLLBACK")
    throw error
  } finally {
    client.release()
  }
}

async function deleteAccount(
  request: IncomingMessage,
  response: ServerResponse,
  user: SessionUser
): Promise<void> {
  const input = deleteAccountSchema.parse(await readJson(request))
  const client = await pool.connect()
  try {
    await client.query("BEGIN")
    const result = await client.query<PasswordRow>(
      "SELECT password_hash FROM users WHERE id = $1 AND is_disabled = FALSE",
      [user.id]
    )
    if (result.rowCount === 0 ||
        !(await argon2.verify(result.rows[0].password_hash, input.currentPassword))) {
      throw httpError(401, "invalid_credentials")
    }
    await client.query("DELETE FROM personas WHERE creator_id = $1", [user.id])
    await client.query("DELETE FROM users WHERE id = $1", [user.id])
    await client.query("COMMIT")
    response.writeHead(204)
    response.end()
  } catch (error) {
    await client.query("ROLLBACK")
    throw error
  } finally {
    client.release()
  }
}

async function listSessions(response: ServerResponse, user: SessionUser): Promise<void> {
  const result = await pool.query(
    `SELECT id, created_at AS "createdAt", last_used_at AS "lastUsedAt",
            user_agent AS "userAgent", ip_address AS "ipAddress",
            expires_at AS "expiresAt", id = $2 AS "isCurrent"
     FROM sessions
     WHERE user_id = $1 AND revoked_at IS NULL AND expires_at > NOW()
     ORDER BY last_used_at DESC`,
    [user.id, user.sessionId]
  )
  writeJson(response, 200, result.rows)
}

async function revokeSession(response: ServerResponse, user: SessionUser, sessionId: string): Promise<void> {
  await pool.query(
    "UPDATE sessions SET revoked_at = NOW() WHERE id = $1 AND user_id = $2",
    [sessionId, user.id]
  )
  response.writeHead(204)
  response.end()
}

async function revokeOtherSessions(response: ServerResponse, user: SessionUser): Promise<void> {
  await pool.query(
    "UPDATE sessions SET revoked_at = NOW() WHERE user_id = $1 AND id <> $2 AND revoked_at IS NULL",
    [user.id, user.sessionId]
  )
  response.writeHead(204)
  response.end()
}

function bearerToken(request: IncomingMessage): string | null {
  const header = request.headers.authorization
  return header?.startsWith("Bearer ") ? header.slice("Bearer ".length).trim() || null : null
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error &&
    (error as { code?: string }).code === "23505"
}

async function allowRequest(
  bucketKey: string,
  limit: number = maxRequestsPerMinute
): Promise<boolean> {
  const result = await pool.query<RateLimitRow>(
    `INSERT INTO rate_limits (bucket_key, window_start, request_count)
     VALUES ($1, NOW(), 1)
     ON CONFLICT (bucket_key) DO UPDATE SET
       window_start = CASE
         WHEN rate_limits.window_start < NOW() - INTERVAL '1 minute' THEN NOW()
         ELSE rate_limits.window_start
       END,
       request_count = CASE
         WHEN rate_limits.window_start < NOW() - INTERVAL '1 minute' THEN 1
         ELSE rate_limits.request_count + 1
       END
     RETURNING request_count`,
    [bucketKey]
  )
  return result.rows[0].request_count <= limit
}

async function allowDailyUsage(
  bucketKey: string,
  amount: number,
  limit: number
): Promise<boolean> {
  const result = await pool.query<UsageRow>(
    `INSERT INTO usage_limits (bucket_key, window_start, usage_amount)
     VALUES ($1, NOW(), $2)
     ON CONFLICT (bucket_key) DO UPDATE SET
       window_start = CASE
         WHEN usage_limits.window_start < NOW() - INTERVAL '24 hours' THEN NOW()
         ELSE usage_limits.window_start
       END,
       usage_amount = CASE
         WHEN usage_limits.window_start < NOW() - INTERVAL '24 hours' THEN $2
         ELSE usage_limits.usage_amount + $2
       END
     RETURNING usage_amount::double precision AS usage_amount`,
    [bucketKey, amount]
  )
  return result.rows[0].usage_amount <= limit
}

function acquireChatSlot(userId: string): boolean {
  const userCount = activeChatByUser.get(userId) ?? 0
  if (userCount >= maxChatConcurrentPerUser || activeChatGlobal >= maxChatConcurrentGlobal) {
    return false
  }
  activeChatByUser.set(userId, userCount + 1)
  activeChatGlobal += 1
  return true
}

function releaseChatSlot(userId: string): void {
  const userCount = activeChatByUser.get(userId) ?? 0
  if (userCount <= 1) activeChatByUser.delete(userId)
  else activeChatByUser.set(userId, userCount - 1)
  activeChatGlobal = Math.max(0, activeChatGlobal - 1)
}

async function readJson(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = []
  let bytes = 0
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    bytes += buffer.length
    if (bytes > 2_000_000) throw httpError(413, "request_too_large")
    chunks.push(buffer)
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"))
  } catch {
    throw httpError(400, "invalid_json")
  }
}

function writeJson(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" })
  response.end(JSON.stringify(body))
}

function httpError(status: number, code: string): Error & { status: number; code: string } {
  return Object.assign(new Error(code), { status, code })
}

function personaDto(row: Record<string, unknown>): Record<string, unknown> {
  return {
    id: row.id,
    name: row.name,
    avatarUrl: row.avatar_url,
    postImageUrl: row.post_image_url,
    traits: row.traits,
    backstory: row.backstory,
    creatorId: row.creator_id,
    isPublic: row.is_public
  }
}

async function handlePersonaList(response: ServerResponse, mine: boolean, user: SessionUser | null): Promise<void> {
  if (mine && !user) throw httpError(401, "authentication_required")
  const result = mine
    ? await pool.query("SELECT * FROM personas WHERE creator_id = $1 ORDER BY created_at DESC", [user!.id])
    : await pool.query("SELECT * FROM personas WHERE is_public = TRUE ORDER BY created_at DESC")
  writeJson(response, 200, result.rows.map(personaDto))
}

async function handlePersonaDetail(response: ServerResponse, id: string, user: SessionUser | null): Promise<void> {
  const result = await pool.query(
    "SELECT * FROM personas WHERE id = $1 AND (is_public = TRUE OR creator_id = $2)",
    [id, user?.id ?? ""]
  )
  if (result.rowCount === 0) throw httpError(404, "persona_not_found")
  writeJson(response, 200, personaDto(result.rows[0]))
}

async function handlePersonaCreate(request: IncomingMessage, response: ServerResponse, user: SessionUser): Promise<void> {
  const input = createPersonaSchema.parse(await readJson(request))
  const id = randomUUID()
  const result = await pool.query(
    `INSERT INTO personas
      (id, name, avatar_url, post_image_url, traits, backstory, creator_id, is_public)
     VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, $8)
     RETURNING *`,
    [
      id,
      input.name,
      `https://api.dicebear.com/7.x/bottts/png?seed=${encodeURIComponent(input.name)}`,
      `https://picsum.photos/seed/${id}/800/600`,
      JSON.stringify(input.traits),
      input.backstory,
      user.id,
      input.isPublic
    ]
  )
  writeJson(response, 201, personaDto(result.rows[0]))
}

async function handlePersonaUpdate(
  request: IncomingMessage,
  response: ServerResponse,
  id: string,
  user: SessionUser
): Promise<void> {
  const input = updatePersonaSchema.parse(await readJson(request))
  const result = await pool.query(
    `UPDATE personas SET
       name = $1,
       traits = $2::jsonb,
       backstory = $3,
       is_public = $4,
       updated_at = NOW()
     WHERE id = $5 AND creator_id = $6
     RETURNING *`,
    [input.name, JSON.stringify(input.traits), input.backstory, input.isPublic, id, user.id]
  )
  if (result.rowCount === 0) throw httpError(404, "persona_not_found")
  writeJson(response, 200, personaDto(result.rows[0]))
}

async function handlePersonaDelete(response: ServerResponse, id: string, user: SessionUser): Promise<void> {
  const result = await pool.query(
    "DELETE FROM personas WHERE id = $1 AND creator_id = $2 RETURNING id",
    [id, user.id]
  )
  if (result.rowCount === 0) throw httpError(404, "persona_not_found")
  response.writeHead(204)
  response.end()
}

async function handleChat(request: IncomingMessage, response: ServerResponse, user: SessionUser): Promise<void> {
  if (!deepSeekApiKey) throw httpError(503, "chat_not_configured")
  if (!(await allowRequest(`chat:${user.id}`))) throw httpError(429, "rate_limited")
  const input = chatSchema.parse(await readJson(request))
  if (input.model !== deepSeekModelId) throw httpError(400, "model_not_allowed")
  const inputChars = input.messages.reduce((total, message) => total + message.content.length, 0)
  if (!(await allowDailyUsage(`chat-input:${user.id}`, inputChars, maxDailyInputChars)) ||
      !(await allowDailyUsage(`chat-output:${user.id}`, maxOutputTokens, maxDailyOutputTokens))) {
    throw httpError(429, "daily_budget_exceeded")
  }
  if (!acquireChatSlot(user.id)) throw httpError(429, "too_many_concurrent_chats")
  const abortController = new AbortController()
  const closeUpstream = () => abortController.abort()
  request.once("aborted", closeUpstream)
  response.once("close", closeUpstream)
  const timeoutController = new AbortController()
  const timeout = setTimeout(() => timeoutController.abort(), chatUpstreamTimeoutMs)
  try {
    const upstream = await fetch(`${deepSeekBaseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${deepSeekApiKey}`,
        "content-type": "application/json"
      },
      body: JSON.stringify({
        model: deepSeekModelId,
        messages: input.messages,
        stream: true,
        temperature: 0.7,
        top_p: 0.8,
        thinking: { type: "disabled" },
        max_tokens: maxOutputTokens
      }),
      signal: AbortSignal.any([abortController.signal, timeoutController.signal])
    }).catch(error => {
      if (timeoutController.signal.aborted) throw httpError(504, "upstream_timeout")
      throw error
    })
    const downstreamStatus = upstream.status === 401 || upstream.status === 403
      ? 502
      : upstream.status
    response.writeHead(downstreamStatus, {
      "cache-control": "no-cache",
      connection: "keep-alive",
      "content-type": "text/event-stream"
    })
    if (!upstream.body) {
      response.end()
      return
    }
    for await (const chunk of upstream.body) {
      if (!response.write(chunk)) await onceDrain(response)
    }
    response.end()
  } finally {
    clearTimeout(timeout)
    request.off("aborted", closeUpstream)
    response.off("close", closeUpstream)
    releaseChatSlot(user.id)
  }
}

function onceDrain(response: ServerResponse): Promise<void> {
  return new Promise(resolve => response.once("drain", resolve))
}

async function route(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const url = new URL(request.url ?? "/", "http://localhost")
  if (request.method === "GET" && url.pathname === "/healthz") {
    writeJson(response, 200, { status: "ok" })
    return
  }

  if (request.method === "POST" && url.pathname === "/api/auth/register") {
    await registerUser(request, response)
    return
  }
  if (request.method === "POST" && url.pathname === "/api/auth/login") {
    await loginUser(request, response)
    return
  }

  const user = await authenticateSession(request)
  if (request.method === "POST" && url.pathname === "/api/auth/logout") {
    await logoutUser(request, response)
    return
  }
  if (request.method === "GET" && url.pathname === "/api/auth/me") {
    if (!user) throw httpError(401, "authentication_required")
    writeJson(response, 200, { id: user.id, email: user.email })
    return
  }
  if (request.method === "POST" && url.pathname === "/api/auth/change-password") {
    if (!user) throw httpError(401, "authentication_required")
    await changePassword(request, response, user)
    return
  }
  if (request.method === "DELETE" && url.pathname === "/api/auth/account") {
    if (!user) throw httpError(401, "authentication_required")
    await deleteAccount(request, response, user)
    return
  }
  if (request.method === "GET" && url.pathname === "/api/auth/sessions") {
    if (!user) throw httpError(401, "authentication_required")
    await listSessions(response, user)
    return
  }
  if (request.method === "POST" && url.pathname === "/api/auth/sessions/revoke-others") {
    if (!user) throw httpError(401, "authentication_required")
    await revokeOtherSessions(response, user)
    return
  }
  const sessionDetail = url.pathname.match(/^\/api\/auth\/sessions\/([^/]+)$/)
  if (sessionDetail && request.method === "DELETE") {
    if (!user) throw httpError(401, "authentication_required")
    await revokeSession(response, user, sessionIdSchema.parse(sessionDetail[1]))
    return
  }

  if (request.method === "GET" && url.pathname === "/api/personas/public") {
    await handlePersonaList(response, false, user)
    return
  }
  if (request.method === "GET" && url.pathname === "/api/personas/mine") {
    await handlePersonaList(response, true, user)
    return
  }
  const detail = url.pathname.match(/^\/api\/personas\/([^/]+)$/)
  if (detail && request.method === "GET") {
    await handlePersonaDetail(response, personaIdSchema.parse(detail[1]), user)
    return
  }
  if (detail && request.method === "DELETE") {
    if (!user) throw httpError(401, "authentication_required")
    await handlePersonaDelete(response, personaIdSchema.parse(detail[1]), user)
    return
  }
  if (detail && request.method === "PATCH") {
    if (!user) throw httpError(401, "authentication_required")
    await handlePersonaUpdate(request, response, personaIdSchema.parse(detail[1]), user)
    return
  }
  if (request.method === "POST" && url.pathname === "/api/personas") {
    if (!user) throw httpError(401, "authentication_required")
    await handlePersonaCreate(request, response, user)
    return
  }
  if (request.method === "POST" && url.pathname === "/api/chat/completions") {
    if (!user) throw httpError(401, "authentication_required")
    await handleChat(request, response, user)
    return
  }
  throw httpError(404, "not_found")
}

const server = createServer(async (request, response) => {
  try {
    await route(request, response)
  } catch (error) {
    const status = typeof error === "object" && error !== null && "status" in error
      ? Number((error as { status: number }).status)
      : error instanceof z.ZodError
        ? 400
      : 500
    const code = typeof error === "object" && error !== null && "code" in error
      ? String((error as { code: string }).code)
      : error instanceof z.ZodError
        ? "invalid_request"
      : "internal_error"
    if (!response.headersSent) writeJson(response, status, { error: { code } })
    else response.destroy()
  }
})

server.listen(port, "0.0.0.0", () => {
  console.log(`persona backend listening on ${port}`)
})

async function shutdown(): Promise<void> {
  server.close(async () => {
    await pool.end()
    process.exit(0)
  })
}

process.once("SIGTERM", shutdown)
process.once("SIGINT", shutdown)

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`Missing required environment variable: ${name}`)
  return value
}
