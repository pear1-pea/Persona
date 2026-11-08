import test from "node:test"
import assert from "node:assert/strict"
import { once } from "node:events"
import { createServer } from "node:http"
import { spawn } from "node:child_process"
import { z } from "zod"
import {
  authSchema, chatSchema, createPersonaSchema, updatePersonaSchema,
  changePasswordSchema, deleteAccountSchema, personaIdSchema, sessionIdSchema
} from "../src/validation.js"

test("auth schema normalizes email and accepts a valid password", () => {
  const parsed = authSchema.parse({
    email: "  USER@Example.COM ",
    password: "password123"
  })

  assert.equal(parsed.email, "user@example.com")
})

test("auth schema rejects a short password", () => {
  assert.throws(() => authSchema.parse({
    email: "user@example.com",
    password: "short"
  }))
})

test("auth schema rejects unknown fields", () => {
  assert.throws(() => authSchema.parse({
    email: "user@example.com",
    password: "password123",
    admin: true
  }))
})

test("persona schema rejects a client supplied creatorId", () => {
  assert.throws(() => createPersonaSchema.parse({
    name: "Aetheris",
    traits: ["calm"],
    backstory: "A test persona",
    isPublic: true,
    creatorId: "attacker"
  }))
})

test("persona schema accepts a valid payload and defaults isPublic", () => {
  const parsed = createPersonaSchema.parse({
    name: "Aetheris",
    traits: ["calm"],
    backstory: "A test persona"
  })

  assert.equal(parsed.isPublic, true)
})

test("chat schema accepts a valid streaming request", () => {
  const parsed = chatSchema.parse({
    model: "deepseek-v4-flash",
    messages: [{ role: "user", content: "hello" }],
    stream: true,
    temperature: 0.7,
    top_p: 0.9,
    thinking: { type: "disabled" }
  })

  assert.equal(parsed.messages.length, 1)
})

test("chat schema rejects more than the configured message count", () => {
  const messages = Array.from({ length: 25 }, () => ({
    role: "user" as const,
    content: "hello"
  }))

  assert.throws(() => chatSchema.parse({
    model: "deepseek-v4-flash",
    messages,
    stream: true
  }))
})

test("chat schema rejects an overlong message", () => {
  assert.throws(() => chatSchema.parse({
    model: "deepseek-v4-flash",
    messages: [{ role: "user", content: "x".repeat(12_001) }],
    stream: true
  }))
})

test("chat schema rejects an unsupported role and non-streaming requests", () => {
  assert.throws(() => chatSchema.parse({
    model: "deepseek-v4-flash",
    messages: [{ role: "tool", content: "hello" }],
    stream: true
  }))

  assert.throws(() => chatSchema.parse({
    model: "deepseek-v4-flash",
    messages: [{ role: "user", content: "hello" }],
    stream: false
  }))
})

test("chat schema rejects invalid generation controls and accepts boundary values", () => {
  const base = { model: "deepseek-v4-flash", messages: [{ role: "user", content: "hello" }], stream: true }
  for (const controls of [
    { temperature: -0.1 }, { temperature: 2.1 }, { top_p: 0 }, { top_p: 1.1 },
    { thinking: { type: "automatic" } }, { thinking: { type: "disabled", extra: true } },
    { max_tokens: 0 }, { max_tokens: 1.5 }, { max_tokens: Number.MAX_SAFE_INTEGER + 1 }, { max_tokens: "10" }
  ]) assert.throws(() => chatSchema.parse({ ...base, ...controls }))
  assert.equal(chatSchema.parse({ ...base, temperature: 0, top_p: 1, max_tokens: 1 }).max_tokens, 1)
})

test("identifier schemas preserve existing hexadecimal UUID inputs", () => {
  for (const schema of [personaIdSchema, sessionIdSchema]) {
    for (const id of [
      "550e8400-e29b-41d4-a716-446655440000",
      "ABCDEF01-2345-6789-0123-456789ABCDEF",
      "00000000-0000-0000-0000-000000000000"
    ]) assert.equal(schema.parse(id), id)
    for (const id of ["not-a-uuid", "550e8400e29b41d4a716446655440000",
      "550e8400-e29b-41d4-a716-44665544000g", " 550e8400-e29b-41d4-a716-446655440000"]
    ) assert.throws(() => schema.parse(id), z.ZodError)
  }
})

test("auth preserves normalized email syntax and password boundaries", () => {
  for (const email of ["USER+tag@Example.COM", "o'neil@example.com", "user@host-.example.com"]) {
    assert.equal(authSchema.parse({ email: ` ${email} `, password: "x".repeat(256) }).email, email.toLowerCase())
  }
  for (const email of [".user@example.com", "user..name@example.com", "user@localhost", "用户@example.com"]) {
    assert.throws(() => authSchema.parse({ email, password: "password123" }), z.ZodError)
  }
  for (const password of ["x".repeat(7), "x".repeat(257), 12345678, null]) {
    assert.throws(() => authSchema.parse({ email: "user@example.com", password }), z.ZodError)
  }
  const email = `${"x".repeat(64)}@${"a".repeat(63)}.${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(63)}`
  assert.equal(email.length, 320)
  assert.equal(authSchema.parse({ email, password: "12345678" }).email, email)
  assert.throws(() => authSchema.parse({ email: `x${email}`, password: "12345678" }), z.ZodError)
})

test("persona create and update preserve trimming, limits, defaults and ownership rejection", () => {
  const valid = { name: ` ${"n".repeat(120)} `, traits: Array(20).fill(` ${"t".repeat(80)} `),
    backstory: ` ${"b".repeat(12000)} ` }
  for (const schema of [createPersonaSchema, updatePersonaSchema]) {
    const parsed = schema.parse(valid)
    assert.equal(parsed.name.length, 120)
    assert.equal(parsed.traits[0].length, 80)
    assert.equal(parsed.backstory.length, 12000)
    assert.equal(parsed.isPublic, true)
    assert.equal(schema.parse({ ...valid, isPublic: false }).isPublic, false)
    for (const patch of [
      { name: " " }, { name: "n".repeat(121) }, { traits: [" "] },
      { traits: ["t".repeat(81)] }, { traits: Array(21).fill("calm") },
      { backstory: " " }, { backstory: "b".repeat(12001) },
      { isPublic: "false" }, { isPublic: null }, { creatorId: "attacker" }, { unknown: true }
    ]) assert.throws(() => schema.parse({ ...valid, ...patch }), z.ZodError)
  }
})

test("password management retains required fields, limits and strict objects", () => {
  assert.equal(changePasswordSchema.parse({ currentPassword: "12345678", newPassword: "x".repeat(256) }).newPassword.length, 256)
  assert.equal(deleteAccountSchema.parse({ currentPassword: "12345678" }).currentPassword, "12345678")
  for (const password of [undefined, null, "short", "x".repeat(257), 12345678]) {
    assert.throws(() => changePasswordSchema.parse({ currentPassword: password, newPassword: "12345678" }), z.ZodError)
    assert.throws(() => changePasswordSchema.parse({ currentPassword: "12345678", newPassword: password }), z.ZodError)
    assert.throws(() => deleteAccountSchema.parse({ currentPassword: password }), z.ZodError)
  }
  assert.throws(() => changePasswordSchema.parse({ currentPassword: "12345678", newPassword: "12345678", admin: true }), z.ZodError)
  assert.throws(() => deleteAccountSchema.parse({ currentPassword: "12345678", userId: "other" }), z.ZodError)
})

test("chat preserves exact limits, optional controls and strict nested objects", () => {
  const base = { model: ` ${"m".repeat(100)} `, messages: Array.from({ length: 24 },
    () => ({ role: "user", content: "x".repeat(12000) })), stream: true }
  const parsed = chatSchema.parse(base)
  assert.equal(parsed.model.length, 100)
  assert.equal(parsed.messages.length, 24)
  for (const field of ["temperature", "top_p", "thinking", "max_tokens"]) assert.equal(field in parsed, false)
  assert.equal(chatSchema.parse({ ...base, temperature: 2, top_p: Number.MIN_VALUE,
    max_tokens: Number.MAX_SAFE_INTEGER }).max_tokens, Number.MAX_SAFE_INTEGER)
  assert.equal(chatSchema.parse({ ...base, messages: [{ role: "assistant", content: "" }] }).messages[0].content, "")
  for (const patch of [
    { model: " " }, { model: "m".repeat(101) }, { messages: [] },
    { messages: [{ role: "user", content: "hello", creatorId: "attacker" }] },
    { messages: [{ role: "user", content: 1 }] }, { messages: Array(2000).fill(null) },
    { creatorId: "attacker" }, { temperature: "1" }, { temperature: NaN },
    { temperature: Infinity }, { top_p: Infinity }, { max_tokens: Infinity }
  ]) assert.throws(() => chatSchema.parse({ ...base, ...patch }), z.ZodError)
})

test("HTTP validation retains controlled public errors without database access", { timeout: 10000 }, async t => {
  const reservation = createServer()
  reservation.listen(0, "127.0.0.1")
  await once(reservation, "listening")
  const address = reservation.address()
  assert.ok(address && typeof address !== "string")
  await new Promise<void>(resolve => reservation.close(() => resolve()))
  const child = spawn(process.execPath, ["--import", "tsx", "src/server.ts"], {
    cwd: new URL("..", import.meta.url),
    env: {
      PATH: process.env.PATH, SystemRoot: process.env.SystemRoot,
      NODE_ENV: "test", PORT: String(address.port),
      DOTENV_CONFIG_PATH: process.platform === "win32" ? "NUL" : "/dev/null",
      DATABASE_URL: "postgres://127.0.0.1:1/unused"
    },
    stdio: ["ignore", "pipe", "pipe"]
  })
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = once(child, "exit")
      child.kill("SIGKILL")
      await exited
    }
  })
  let stderr = ""
  child.stderr.on("data", data => { stderr += data })
  await new Promise<void>((resolve, reject) => {
    child.once("error", reject)
    child.once("exit", code => reject(new Error(`backend exited ${code}: ${stderr}`)))
    child.stdout.on("data", data => {
      if (String(data).includes("persona backend listening")) resolve()
    })
  })
  for (const route of ["register", "login"]) {
    for (const body of [
      JSON.stringify({ email: "private-user@example.com", password: "short" }),
      JSON.stringify({ email: "user@example.com", password: "password123", creatorId: "attacker" }),
      JSON.stringify({ email: ["private-user@example.com"], password: "password123" }),
      "null"
    ]) {
      const response: Response = await fetch(`http://127.0.0.1:${address.port}/api/auth/${route}`, {
        method: "POST", headers: { "content-type": "application/json" }, body
      })
      assert.equal(response.status, 400)
      assert.deepEqual(await response.json(), { error: { code: "invalid_request" } })
    }
  }
  const response = await fetch(`http://127.0.0.1:${address.port}/api/auth/register`, {
    method: "POST", headers: { "content-type": "application/json" }, body: "{"
  })
  assert.equal(response.status, 400)
  assert.deepEqual(await response.json(), { error: { code: "invalid_json" } })
})
