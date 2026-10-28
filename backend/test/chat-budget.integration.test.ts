import test from "node:test"
import assert from "node:assert/strict"
import { Pool } from "pg"
import { reserveChatBudget } from "../src/chat-budget.js"

const databaseUrl = process.env.TEST_DATABASE_URL

test("budget transactions against isolated PostgreSQL", { skip: !databaseUrl }, async t => {
  const admin = new Pool({ connectionString: databaseUrl })
  const schema = `budget_test_${process.pid}_${Date.now()}`
  await admin.query(`CREATE SCHEMA ${schema}`)
  const url = new URL(databaseUrl!)
  url.searchParams.set("options", `-c search_path=${schema}`)
  const db = new Pool({ connectionString: url.href, max: 10 })
  t.after(async () => { await db.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end() })
  await db.query("CREATE TABLE usage_limits (bucket_key text PRIMARY KEY, window_start timestamptz NOT NULL, usage_amount bigint NOT NULL)")
  const reserve = (id: string, input = 5, output = 10) => reserveChatBudget(db, id, input, output, 20, 20)
  const amounts = async (id: string) => (await db.query(
    "SELECT usage_amount::int AS amount FROM usage_limits WHERE bucket_key = ANY($1) ORDER BY bucket_key",
    [[`chat-input:${id}`, `chat-output:${id}`]]
  )).rows.map(row => row.amount)

  await t.test("both buckets reserve atomically under concurrent requests", async () => {
    const results = await Promise.all(Array.from({ length: 8 }, () => reserve("race")))
    assert.equal(results.filter(Boolean).length, 2)
    assert.deepEqual(await amounts("race"), [10, 20])
    await Promise.all(results.filter(result => result !== null).map(result => result.settle(0, 0)))
    assert.deepEqual(await amounts("race"), [0, 0])
  })
  await t.test("concurrent/repeated settlement refunds at most once", async () => {
    const budget = await reserve("once")
    assert.ok(budget)
    await Promise.all([budget.settle(5, 3), budget.settle(0, 0), budget.settle(5, 3)])
    assert.deepEqual(await amounts("once"), [5, 3])
  })
  await t.test("input rejection never changes output or input", async () => {
    assert.equal(await reserve("too-big", 21), null)
    assert.deepEqual(await amounts("too-big"), [])
  })
  await t.test("actual usage above the reservation is recorded as debt", async () => {
    const budget = await reserve("debt")
    assert.ok(budget)
    await budget.settle(5, 25)
    assert.deepEqual(await amounts("debt"), [5, 25])
    assert.equal(await reserve("debt"), null)
    assert.deepEqual(await amounts("debt"), [5, 25])
  })
  await t.test("a late settlement cannot refund a replacement window", async () => {
    const old = await reserve("rollover")
    assert.ok(old)
    await db.query("UPDATE usage_limits SET window_start = NOW() - INTERVAL '25 hours' WHERE bucket_key = ANY($1)",
      [["chat-input:rollover", "chat-output:rollover"]])
    const current = await reserve("rollover", 7, 12)
    assert.ok(current)
    await old.settle(0, 0)
    assert.deepEqual(await amounts("rollover"), [7, 12])
    await current.settle(7, 4)
    assert.deepEqual(await amounts("rollover"), [7, 4])
  })
  await t.test("SQL failure rolls back the whole reservation and returns the client", async () => {
    await db.query("ALTER TABLE usage_limits ADD CONSTRAINT test_failure CHECK (bucket_key <> 'chat-output:failure')")
    await assert.rejects(reserve("failure"))
    assert.deepEqual(await amounts("failure"), [])
    await db.query("ALTER TABLE usage_limits DROP CONSTRAINT test_failure")
    assert.ok(await reserve("failure"))
    assert.equal(db.waitingCount, 0)
  })
  await t.test("failed settlement is atomic and is never retried as a second refund", async () => {
    const budget = await reserve("settlement-failure")
    assert.ok(budget)
    await db.query("ALTER TABLE usage_limits ADD CONSTRAINT test_settlement_failure CHECK (bucket_key <> 'chat-output:settlement-failure' OR usage_amount = 10)")
    await assert.rejects(budget.settle(0, 0))
    assert.deepEqual(await amounts("settlement-failure"), [5, 10])
    await db.query("ALTER TABLE usage_limits DROP CONSTRAINT test_settlement_failure")
    await assert.rejects(budget.settle(0, 0))
    assert.deepEqual(await amounts("settlement-failure"), [5, 10])
  })
})
