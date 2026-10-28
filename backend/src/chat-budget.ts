import type { Pool } from "pg"

type Bucket = { key: string; amount: number; limit: number; window: string }
export type ChatBudget = { settle: (inputChars: number, outputTokens: number) => Promise<void> }

export async function reserveChatBudget(
  pool: Pool,
  userId: string,
  inputChars: number,
  outputTokens: number,
  inputLimit: number,
  outputLimit: number
): Promise<ChatBudget | null> {
  const buckets: Bucket[] = [
    { key: `chat-input:${userId}`, amount: inputChars, limit: inputLimit, window: "" },
    { key: `chat-output:${userId}`, amount: outputTokens, limit: outputLimit, window: "" }
  ]
  const client = await pool.connect()
  try {
    await client.query("BEGIN")
    for (const bucket of buckets) {
      const result = await client.query<{ window: string }>(
        `INSERT INTO usage_limits (bucket_key, window_start, usage_amount)
         SELECT $1, NOW(), $2::bigint WHERE $2::bigint <= $3::bigint
         ON CONFLICT (bucket_key) DO UPDATE SET
           window_start = CASE
             WHEN usage_limits.window_start < NOW() - INTERVAL '24 hours' THEN NOW()
             ELSE usage_limits.window_start
           END,
           usage_amount = CASE
             WHEN usage_limits.window_start < NOW() - INTERVAL '24 hours' THEN $2::bigint
             ELSE usage_limits.usage_amount + $2::bigint
           END
         WHERE CASE
           WHEN usage_limits.window_start < NOW() - INTERVAL '24 hours' THEN $2::bigint
           ELSE usage_limits.usage_amount + $2::bigint
         END <= $3::bigint
         RETURNING window_start::text AS window`,
        [bucket.key, bucket.amount, bucket.limit]
      )
      if (result.rowCount === 0) {
        await client.query("ROLLBACK")
        return null
      }
      bucket.window = result.rows[0].window
    }
    await client.query("COMMIT")
  } catch (error) {
    await client.query("ROLLBACK")
    throw error
  } finally {
    client.release()
  }

  let settlement: Promise<void> | undefined
  return {
    // Share one settlement attempt so concurrent exits cannot refund twice.
    settle(inputUsed, outputUsed) {
      settlement ??= settleBuckets(pool, buckets, [inputUsed, outputUsed])
      return settlement
    }
  }
}

async function settleBuckets(pool: Pool, buckets: Bucket[], actual: number[]): Promise<void> {
  if (actual.some(value => !Number.isSafeInteger(value) || value < 0)) {
    throw new Error("invalid_chat_usage")
  }
  const client = await pool.connect()
  try {
    await client.query("BEGIN")
    for (const [index, bucket] of buckets.entries()) {
      // An old reservation must never refund a newly rolled-over daily window.
      await client.query(
        `UPDATE usage_limits SET usage_amount = usage_amount - $2::bigint + $3::bigint
         WHERE bucket_key = $1 AND window_start = $4::timestamptz`,
        [bucket.key, bucket.amount, actual[index], bucket.window]
      )
    }
    await client.query("COMMIT")
  } catch (error) {
    await client.query("ROLLBACK")
    throw error
  } finally {
    client.release()
  }
}
