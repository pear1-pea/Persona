import test from "node:test"
import assert from "node:assert/strict"
import { createSessionToken, hashSessionToken, normalizeEmail } from "../src/security.js"

test("normalizeEmail trims and lowercases", () => {
  assert.equal(normalizeEmail("  USER@Example.COM "), "user@example.com")
})

test("hashSessionToken is deterministic and 32 bytes", () => {
  const first = hashSessionToken("token-value")
  const second = hashSessionToken("token-value")

  assert.deepEqual(first, second)
  assert.equal(first.length, 32)
  assert.notDeepEqual(first, hashSessionToken("other-value"))
})

test("createSessionToken returns a URL-safe random token", () => {
  const first = createSessionToken()
  const second = createSessionToken()

  assert.match(first, /^[A-Za-z0-9_-]+$/)
  assert.ok(first.length >= 40)
  assert.notEqual(first, second)
})
