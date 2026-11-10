import test from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import { runSmoke } from "./smoke-auth.mjs"

async function fixture(t, options = {}) {
  let registrations = 0
  let personas = 0
  const deleted = []
  const server = createServer((req, res) => {
    req.resume()
    const reply = (status, body) => {
      res.writeHead(status, { "content-type": "application/json" })
      res.end(body === undefined ? undefined : JSON.stringify(body))
    }
    if (req.url === "/api/auth/register") {
      registrations++
      if (options.stall === "headers") return
      if (options.stall === "body") {
        res.writeHead(200, { "content-type": "application/json" })
        res.write('{"token":')
        return
      }
      return reply(options.failSecond && registrations === 2 ? 503 : 200, { token: String(registrations) })
    }
    if (req.url === "/api/auth/account") {
      deleted.push(req.headers.authorization)
      return reply(options.failCleanup && deleted.length === 1 ? 503 : 204)
    }
    if (req.method === "POST") return reply(options.failPersona ? 503 : 200, { id: ++personas })
    if (req.url === "/api/auth/sessions") return reply(200, [])
    if (req.url === "/api/personas/2" || (req.method === "DELETE" && req.headers.authorization === "Bearer 2")) return reply(404, {})
    return reply(req.method === "DELETE" ? 204 : 200, {})
  })
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve))
  t.after(async () => {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
  })
  return { deleted, run: () => runSmoke({ baseUrl: `http://127.0.0.1:${server.address().port}`, timeoutMs: options.stall ? 150 : 1000 }) }
}

test("success cleans both accounts", async t => {
  const f = await fixture(t)
  await f.run()
  assert.deepEqual(f.deleted, ["Bearer 1", "Bearer 2"])
})
test("second signup failure cleans first account and preserves primary failure", async t => {
  const f = await fixture(t, { failSecond: true, failCleanup: true })
  await assert.rejects(f.run(), error => {
    assert.match(error.message, /register/)
    assert.equal(error.cleanupErrors.length, 1)
    return true
  })
  assert.deepEqual(f.deleted, ["Bearer 1"])
})
test("cleanup failure still cleans next account and surfaces cleanup-only error", async t => {
  const f = await fixture(t, { failCleanup: true })
  await assert.rejects(f.run(), AggregateError)
  assert.deepEqual(f.deleted, ["Bearer 1", "Bearer 2"])
})
test("primary operation failure survives failed cleanup of both created accounts", async t => {
  const f = await fixture(t, { failPersona: true, failCleanup: true })
  await assert.rejects(f.run(), error => {
    assert.match(error.message, /personas/)
    assert.equal(error.cleanupErrors.length, 1)
    return true
  })
  assert.deepEqual(f.deleted, ["Bearer 1", "Bearer 2"])
})
for (const stall of ["headers", "body"]) {
  test(`${stall} stall times out`, { timeout: 2000 }, async t => {
    const f = await fixture(t, { stall })
    await assert.rejects(f.run(), /request timed out/)
  })
}
