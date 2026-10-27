import test from "node:test"
import assert from "node:assert/strict"
import { authSchema, chatSchema, createPersonaSchema } from "../src/validation.js"

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
