import "dotenv/config"
import argon2 from "argon2"
import pg from "pg"

const [command, emailArg, value] = process.argv.slice(2)
const databaseUrl = process.env.DATABASE_URL?.trim()
if (!databaseUrl) throw new Error("DATABASE_URL is required")
if (!command || !emailArg) usage()

const email = emailArg.trim().toLowerCase()
const pool = new pg.Pool({ connectionString: databaseUrl })
try {
  if (command === "disable" || command === "enable") {
    const disabled = command === "disable"
    const result = await pool.query(
      "UPDATE users SET is_disabled = $1, updated_at = NOW() WHERE email = $2 RETURNING id",
      [disabled, email]
    )
    if (result.rowCount === 0) throw new Error("User not found")
    if (disabled) {
      await pool.query(
        "UPDATE sessions SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL",
        [result.rows[0].id]
      )
    }
    console.log(`${email} ${disabled ? "disabled" : "enabled"}`)
  } else if (command === "reset-password") {
    if (!value || value.length < 8) throw new Error("New password must contain at least 8 characters")
    const hash = await argon2.hash(value, { type: argon2.argon2id })
    const result = await pool.query(
      "UPDATE users SET password_hash = $1, updated_at = NOW() WHERE email = $2 RETURNING id",
      [hash, email]
    )
    if (result.rowCount === 0) throw new Error("User not found")
    await pool.query(
      "UPDATE sessions SET revoked_at = NOW() WHERE user_id = $1 AND revoked_at IS NULL",
      [result.rows[0].id]
    )
    console.log(`${email} password reset; all sessions revoked`)
  } else if (command === "bind-authing") {
    if (!value) throw new Error("Legacy Authing creatorId is required")
    const client = await pool.connect()
    try {
      await client.query("BEGIN")
      const user = await client.query("SELECT id FROM users WHERE email = $1", [email])
      if (user.rowCount === 0) throw new Error("User not found")
      await client.query(
        `INSERT INTO legacy_identities (provider, legacy_user_id, user_id)
         VALUES ('authing', $1, $2)
         ON CONFLICT (provider, legacy_user_id) DO UPDATE SET user_id = EXCLUDED.user_id`,
        [value, user.rows[0].id]
      )
      await client.query(
        "UPDATE personas SET creator_id = $1, updated_at = NOW() WHERE creator_id = $2",
        [user.rows[0].id, value]
      )
      await client.query("COMMIT")
      console.log(`Authing identity ${value} bound to ${email}`)
    } catch (error) {
      await client.query("ROLLBACK")
      throw error
    } finally {
      client.release()
    }
  } else {
    usage()
  }
} finally {
  await pool.end()
}

function usage() {
  console.error("Usage:")
  console.error("  node scripts/admin-user.mjs disable <email>")
  console.error("  node scripts/admin-user.mjs enable <email>")
  console.error("  node scripts/admin-user.mjs reset-password <email> <new-password>")
  console.error("  node scripts/admin-user.mjs bind-authing <email> <legacy-creator-id>")
  process.exit(1)
}
