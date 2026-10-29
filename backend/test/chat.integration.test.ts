import test, { type TestContext } from "node:test"
import assert from "node:assert/strict"
import { createServer, request as httpRequest, type Server, type ServerResponse } from "node:http"
import { spawn } from "node:child_process"
import { once } from "node:events"
import { readFile } from "node:fs/promises"
import { setTimeout as delay } from "node:timers/promises"
import { Pool } from "pg"

const databaseUrl = process.env.TEST_DATABASE_URL
const chunk = (content = "hello") => `data: ${JSON.stringify({ choices: [{ delta: { content } }] })}\n\n`
const finish = (tokens = 2) => `data: ${JSON.stringify({ choices: [{ delta: {}, finish_reason: "stop" }], usage: { completion_tokens: tokens } })}\n\ndata: [DONE]\n\n`
const payload = { model: "deepseek-v4-flash", messages: [{ role: "user", content: "hello" }], stream: true }

async function listen(server: Server): Promise<string> {
  server.listen(0, "127.0.0.1")
  await once(server, "listening")
  const address = server.address()
  assert.ok(address && typeof address !== "string")
  return `http://127.0.0.1:${address.port}`
}

async function eventually(check: () => Promise<boolean>): Promise<void> {
  for (let i = 0; i < 100; i++) {
    if (await check()) return
    await delay(20)
  }
  assert.fail("condition did not become true")
}

test("chat proxy with isolated PostgreSQL and a controllable HTTP upstream", { skip: !databaseUrl, timeout: 30_000 }, async t => {
  const admin = new Pool({ connectionString: databaseUrl })
  const schema = `chat_test_${process.pid}_${Date.now()}`
  await admin.query(`CREATE SCHEMA ${schema}`)
  const url = new URL(databaseUrl!)
  url.searchParams.set("options", `-c search_path=${schema},public`)
  const db = new Pool({ connectionString: url.href })
  t.after(async () => {
    await db.end()
    await admin.query(`DROP SCHEMA ${schema} CASCADE`)
    await admin.end()
  })
  await db.query(await readFile(new URL("../schema.sql", import.meta.url), "utf8"))

  let upstreamCalls = 0
  let upstreamBody: Record<string, unknown> = {}
  let scenario: (response: ServerResponse) => void = response => {
    response.writeHead(200, { "content-type": "text/event-stream" })
    response.end(chunk() + finish())
  }
  const upstream = createServer(async (request, response) => {
    const buffers = []
    for await (const data of request) buffers.push(data)
    assert.equal(request.headers.authorization, "Bearer fake-integration-key")
    upstreamBody = JSON.parse(Buffer.concat(buffers).toString())
    upstreamCalls++
    scenario(response)
  })
  const upstreamUrl = await listen(upstream)
  t.after(() => { upstream.closeAllConnections(); upstream.close() })
  const portServer = createServer()
  const backendUrl = await listen(portServer)
  await new Promise<void>(resolve => portServer.close(() => resolve()))
  const child = spawn(process.execPath, ["--import", "tsx", "src/server.ts"], {
    cwd: new URL("..", import.meta.url),
    env: { ...process.env, DATABASE_URL: url.href, PORT: new URL(backendUrl).port,
      DEEPSEEK_API_KEY: "fake-integration-key", DEEPSEEK_BASE_URL: upstreamUrl,
      DEEPSEEK_MODEL_ID: "deepseek-v4-flash", MAX_REQUESTS_PER_MINUTE: "1000",
      MAX_AUTH_REQUESTS_PER_MINUTE: "1000", MAX_OUTPUT_TOKENS: "10",
      MAX_MESSAGES: "24", MAX_MESSAGE_CHARS: "12000", TRUST_PROXY: "false",
      MAX_DAILY_INPUT_CHARS: "100", MAX_DAILY_OUTPUT_TOKENS: "20",
      MAX_CHAT_CONCURRENT_PER_USER: "1", MAX_CHAT_CONCURRENT_GLOBAL: "1",
      CHAT_UPSTREAM_TIMEOUT_MS: "500" },
    stdio: ["ignore", "pipe", "pipe"]
  })
  let logs = ""
  child.stdout.on("data", data => { logs += data })
  child.stderr.on("data", data => { logs += data })
  t.after(async () => {
    if (child.exitCode === null) {
      child.kill("SIGKILL")
      await once(child, "exit")
    }
  })
  await eventually(async () => {
    assert.equal(child.exitCode, null, logs)
    return fetch(`${backendUrl}/healthz`).then(r => r.ok, () => false)
  })

  async function user() {
    const response = await fetch(`${backendUrl}/api/auth/register`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: `${crypto.randomUUID()}@example.com`, password: "integration-password" })
    })
    assert.equal(response.status, 201)
    return await response.json() as { token: string; user: { id: string } }
  }
  async function chat(token: string, body: unknown = payload, signal?: AbortSignal) {
    return fetch(`${backendUrl}/api/chat/completions`, { method: "POST", signal,
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(body) })
  }
  async function usage(id: string) {
    const result = await db.query("SELECT bucket_key, usage_amount::int FROM usage_limits WHERE bucket_key = ANY($1)",
      [[`chat-input:${id}`, `chat-output:${id}`]])
    return Object.fromEntries(result.rows.map(row => [row.bucket_key.split(":")[0], row.usage_amount]))
  }
  const normal = scenario
  async function caseTest(name: string, run: (account: Awaited<ReturnType<typeof user>>, ctx: TestContext) => Promise<void>) {
    await t.test(name, async ctx => { scenario = normal; await run(await user(), ctx) })
  }

  await caseTest("forwards legal generation parameters and settles final usage once", async account => {
    const response = await chat(account.token, { ...payload, temperature: 0, top_p: 0.95, thinking: { type: "enabled" }, max_tokens: 4 })
    assert.equal(response.status, 200)
    assert.equal(await response.text(), chunk() + finish())
    assert.equal(upstreamBody.temperature, 0)
    assert.equal(upstreamBody.top_p, 0.95)
    assert.deepEqual(upstreamBody.thinking, { type: "enabled" })
    assert.equal(upstreamBody.max_tokens, 4)
    await eventually(async () => (await usage(account.user.id))["chat-output"] === 2)
    assert.deepEqual(await usage(account.user.id), { "chat-input": 5, "chat-output": 2 })
  })
  await caseTest("429 releases both reservations and does not leak upstream errors", async account => {
    scenario = response => { response.writeHead(429, { "retry-after": "3" }); response.end("private upstream error") }
    for (let i = 0; i < 3; i++) {
      const response = await chat(account.token)
      assert.equal(response.status, 429)
      assert.equal(response.headers.get("retry-after"), "3")
      assert.doesNotMatch(await response.text(), /private upstream error|daily_budget_exceeded/)
    }
    assert.deepEqual(await usage(account.user.id), { "chat-input": 0, "chat-output": 0 })
  })
  await caseTest("output reservation uses the effective requested token limit", async account => {
    const requests = [4, 100, 393217]
    for (const [index, requested] of requests.entries()) {
      let finishResponse = () => undefined as void
      scenario = response => {
        response.writeHead(200, { "content-type": "text/event-stream" })
        response.write(chunk())
        finishResponse = () => { response.end(finish()) }
      }
      const response = await chat(account.token, { ...payload, max_tokens: requested })
      assert.equal(response.status, 200)
      const effective = Math.min(requested, 10)
      assert.equal(upstreamBody.max_tokens, effective)
      assert.deepEqual(await usage(account.user.id), { "chat-input": (index + 1) * 5, "chat-output": index * 2 + effective })
      finishResponse()
      await response.text()
      await eventually(async () => (await usage(account.user.id))["chat-output"] === (index + 1) * 2)
    }
  })
  await caseTest("budget rejection is atomic and repeated rejection does not consume input", async account => {
    await db.query("INSERT INTO usage_limits VALUES ($1, NOW(), 20)", [`chat-output:${account.user.id}`])
    for (let i = 0; i < 3; i++) assert.equal((await chat(account.token)).status, 429)
    assert.deepEqual(await usage(account.user.id), { "chat-output": 20 })
  })
  await caseTest("slow first packet and incremental output remain streaming", async account => {
    scenario = response => {
      setTimeout(() => {
        response.writeHead(200, { "content-type": "text/event-stream" })
        response.write(chunk("first"))
        setTimeout(() => response.end(chunk("second") + finish()), 80)
      }, 60)
    }
    const response = await chat(account.token)
    const reader = response.body!.getReader()
    const first = await reader.read()
    assert.match(new TextDecoder().decode(first.value), /first/)
    assert.doesNotMatch(new TextDecoder().decode(first.value), /second/)
    while (!(await reader.read()).done) { /* Consume the remaining stream. */ }
  })
  await caseTest("truncated SSE is an interrupted response and keeps uncertain costs", async account => {
    scenario = response => { response.writeHead(200, { "content-type": "text/event-stream" }); response.end(chunk()) }
    const response = await chat(account.token)
    await assert.rejects(response.text())
    await eventually(async () => (await usage(account.user.id))["chat-output"] === 10)
  })
  await caseTest("invalid content type is rejected and a following request can acquire the slot", async account => {
    scenario = response => { response.writeHead(200, { "content-type": "application/json" }); response.end("{}") }
    assert.equal((await chat(account.token)).status, 502)
    scenario = normal
    assert.equal((await chat(account.token)).status, 200)
  })
  await caseTest("timeout before headers releases the slot", async account => {
    scenario = () => undefined
    const response = await chat(account.token)
    assert.equal(response.status, 504)
    await response.text()
    scenario = normal
    assert.equal((await chat(account.token)).status, 200)
  })
  await caseTest("cancellation before headers cancels upstream and frees global slot", async account => {
    let closed = false
    scenario = response => response.on("close", () => { closed = true })
    const controller = new AbortController()
    const calls = upstreamCalls
    const pending = chat(account.token, payload, controller.signal)
    await eventually(async () => upstreamCalls > calls)
    controller.abort()
    await assert.rejects(pending)
    await eventually(async () => closed)
    scenario = normal
    assert.equal((await chat(account.token)).status, 200)
  })
  await caseTest("allowlist, message limits and invalid controls reject without upstream calls or budget", async account => {
    const before = upstreamCalls
    for (const body of [
      { ...payload, model: "other-model" }, { ...payload, top_p: 0 },
      { ...payload, thinking: { type: "automatic" } },
      { ...payload, messages: Array.from({ length: 25 }, () => payload.messages[0]) },
      { ...payload, messages: [{ role: "user", content: "x".repeat(12001) }] }
    ]) assert.equal((await chat(account.token, body)).status, 400)
    assert.equal(upstreamCalls, before)
    assert.deepEqual(await usage(account.user.id), {})
    const response = await chat(account.token, { ...payload, max_tokens: 100 })
    assert.equal(response.status, 200)
    await response.text()
    assert.equal(upstreamBody.max_tokens, 10)
  })
  await caseTest("per-user and global concurrency rejection never reserve budget", async account => {
    let closed = false
    scenario = response => {
      response.writeHead(200, { "content-type": "text/event-stream" })
      response.write(chunk())
      response.on("close", () => { closed = true })
    }
    const other = await user()
    const controller = new AbortController()
    await chat(account.token, payload, controller.signal)
    assert.equal((await chat(account.token)).status, 429)
    assert.equal((await chat(other.token)).status, 429)
    assert.deepEqual(await usage(account.user.id), { "chat-input": 5, "chat-output": 10 })
    assert.deepEqual(await usage(other.user.id), {})
    controller.abort()
    await eventually(async () => closed)
    scenario = normal
    const response = await chat(other.token)
    assert.equal(response.status, 200)
    await response.text()
  })
  await caseTest("continuous output still obeys the total deadline and frees the slot", async account => {
    scenario = response => {
      response.writeHead(200, { "content-type": "text/event-stream" })
      response.write(chunk())
      const timer = setInterval(() => response.write(chunk()), 30)
      response.on("close", () => clearInterval(timer))
    }
    const response = await chat(account.token)
    await assert.rejects(response.text())
    scenario = normal
    const next = await chat(account.token)
    assert.equal(next.status, 200)
    await next.text()
  })
  for (const cancel of [false, true]) {
    await caseTest(`paused TCP reader releases backpressure wait on ${cancel ? "cancel" : "deadline"}`, async (account, ctx) => {
      let closed = false
      let backpressured = false
      scenario = response => {
        response.writeHead(200, { "content-type": "text/event-stream" })
        const block = ": " + "x".repeat(32_000) + "\n\n"
        const pump = () => {
          for (let i = 0; i < 8192 && !response.destroyed; i++) {
            if (!response.write(block)) {
              backpressured = true
              response.once("drain", pump)
              return
            }
          }
        }
        response.on("close", () => { closed = true })
        pump()
      }
      const request = httpRequest(`${backendUrl}/api/chat/completions`, {
        method: "POST", headers: { authorization: `Bearer ${account.token}`, "content-type": "application/json" }
      })
      ctx.after(() => request.destroy())
      const paused = new Promise<void>((resolve, reject) => {
        request.on("response", response => { response.pause(); response.on("error", () => undefined); resolve() })
        request.on("error", reject)
      })
      request.end(JSON.stringify(payload))
      await paused
      await eventually(async () => backpressured)
      if (cancel) { await delay(80); request.destroy() }
      await eventually(async () => closed)
      scenario = normal
      const next = await chat(account.token)
      assert.equal(next.status, 200)
      await next.text()
    })
  }
  await caseTest("upstream transport disconnect is visible and releases the slot", async account => {
    scenario = response => {
      response.writeHead(200, { "content-type": "text/event-stream" })
      response.write(chunk())
      setTimeout(() => response.destroy(), 30)
    }
    const response = await chat(account.token)
    await assert.rejects(response.text())
    scenario = normal
    const next = await chat(account.token)
    assert.equal(next.status, 200)
    await next.text()
  })
  await caseTest("malformed SSE is rejected without refunding accepted work", async account => {
    scenario = response => {
      response.writeHead(200, { "content-type": "text/event-stream" })
      response.end("data: invalid-json\n\n")
    }
    const response = await chat(account.token)
    assert.equal(response.status, 502)
    await response.text()
    assert.deepEqual(await usage(account.user.id), { "chat-input": 5, "chat-output": 10 })
  })
  await caseTest("completed stream without usage keeps the conservative reservation", async account => {
    scenario = response => {
      response.writeHead(200, { "content-type": "text/event-stream" })
      response.end(chunk() + "data: [DONE]\n\n")
    }
    const response = await chat(account.token)
    assert.equal(response.status, 200)
    await response.text()
    assert.deepEqual(await usage(account.user.id), { "chat-input": 5, "chat-output": 10 })
  })
  await caseTest("terminal usage remains billable when the connection drops before DONE", async account => {
    scenario = response => {
      response.writeHead(200, { "content-type": "text/event-stream" })
      response.write(chunk() + finish(3).replace("data: [DONE]\n\n", ""))
      setTimeout(() => response.destroy(), 30)
    }
    const response = await chat(account.token)
    await assert.rejects(response.text())
    await eventually(async () => (await usage(account.user.id))["chat-output"] === 3)
    assert.deepEqual(await usage(account.user.id), { "chat-input": 5, "chat-output": 3 })
  })
  await caseTest("explicit upstream HTTP errors release reservations and mask credentials errors", async account => {
    for (const status of [400, 401, 403, 500, 503]) {
      scenario = response => { response.writeHead(status); response.end("private error") }
      const response = await chat(account.token)
      assert.equal(response.status, [401, 403].includes(status) ? 502 : status)
      assert.doesNotMatch(await response.text(), /private error/)
      assert.deepEqual(await usage(account.user.id), { "chat-input": 0, "chat-output": 0 })
    }
  })
  await caseTest("database reservation failure rolls back and releases the slot", async account => {
    await db.query(`ALTER TABLE usage_limits ADD CONSTRAINT fail_chat_reservation CHECK (bucket_key <> 'chat-output:${account.user.id}')`)
    assert.equal((await chat(account.token)).status, 500)
    assert.deepEqual(await usage(account.user.id), {})
    await db.query("ALTER TABLE usage_limits DROP CONSTRAINT fail_chat_reservation")
    const response = await chat(account.token)
    assert.equal(response.status, 200)
    await response.text()
  })
  await caseTest("failed settlement preserves reservations and releases the slot", async account => {
    await db.query(`ALTER TABLE usage_limits ADD CONSTRAINT fail_chat_settlement CHECK (bucket_key <> 'chat-output:${account.user.id}' OR usage_amount = 10)`)
    const logStart = logs.length
    const response = await chat(account.token)
    assert.equal(response.status, 200)
    await response.text()
    await eventually(async () => logs.slice(logStart).includes("chat_budget_settlement_failed"))
    assert.deepEqual(await usage(account.user.id), { "chat-input": 5, "chat-output": 10 })
    await db.query("ALTER TABLE usage_limits DROP CONSTRAINT fail_chat_settlement")
    const next = await chat(account.token)
    assert.equal(next.status, 200)
    await next.text()
    await eventually(async () => (await usage(account.user.id))["chat-output"] === 12)
  })
  await t.test("existing two-user auth/persona smoke passes on the isolated backend", async ctx => {
    const smoke = spawn(process.execPath, ["scripts/smoke-auth.mjs"], {
      cwd: new URL("..", import.meta.url), env: { ...process.env, PERSONA_BACKEND_BASE_URL: backendUrl },
      stdio: ["ignore", "pipe", "pipe"]
    })
    let output = ""
    smoke.stdout.on("data", data => { output += data })
    smoke.stderr.on("data", data => { output += data })
    const [code] = await once(smoke, "exit")
    assert.equal(code, 0, output)
    ctx.diagnostic(output.trim())
  })
})
