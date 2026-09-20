import "dotenv/config"
import fs from "node:fs/promises"
import { randomUUID } from "node:crypto"
import pg from "pg"

const inputPath = process.argv[2]
if (!inputPath) {
  console.error("Usage: node scripts/import-personas.mjs personas.json")
  process.exit(1)
}

const databaseUrl = process.env.DATABASE_URL?.trim()
if (!databaseUrl) {
  console.error("DATABASE_URL is required")
  process.exit(1)
}

const raw = JSON.parse(await fs.readFile(inputPath, "utf8"))
const documents = Array.isArray(raw) ? raw : raw.personas
if (!Array.isArray(documents)) throw new Error("Input must be an array or { personas: [] }")

const pool = new pg.Pool({ connectionString: databaseUrl })
const client = await pool.connect()
try {
  await client.query("BEGIN")
  for (const document of documents) {
    const id = String(document.id || randomUUID())
    const name = String(document.name || "").trim()
    const creatorId = String(document.creatorId || "").trim()
    if (!name || !creatorId) throw new Error(`Invalid persona ${id}: name and creatorId are required`)

    const traits = Array.isArray(document.traits)
      ? document.traits.map(value => String(value).trim()).filter(Boolean)
      : []
    const avatarUrl = normalizeUrl(document.avatarUrl, name, "avatar")
    const postImageUrl = normalizeUrl(document.postImageUrl, id, "post")
    const isPublic = document.isPublic !== false
    const backstory = String(document.backstory || "").trim()
    const values = [
      id,
      name,
      avatarUrl,
      postImageUrl,
      JSON.stringify(traits),
      backstory,
      creatorId,
      isPublic
    ]

    let targetId = (await client.query(
      "SELECT id FROM personas WHERE id = $1",
      [id]
    )).rowCount > 0 ? id : null

    if (!targetId && creatorId === "system") {
      const legacy = await client.query(
        `SELECT id FROM personas
         WHERE creator_id = $1 AND name = $2
         ORDER BY created_at ASC
         LIMIT 1`,
        [creatorId, name]
      )
      targetId = legacy.rows[0]?.id ?? null
    }

    if (targetId) {
      await client.query(
        `UPDATE personas SET
           id = $1,
           name = $2,
           avatar_url = $3,
           post_image_url = $4,
           traits = $5::jsonb,
           backstory = $6,
           creator_id = $7,
           is_public = $8,
           updated_at = NOW()
         WHERE id = $9`,
        [...values, targetId]
      )

      if (creatorId === "system") {
        await client.query(
          "DELETE FROM personas WHERE creator_id = $1 AND name = $2 AND id <> $3",
          [creatorId, name, id]
        )
      }
    } else {
      await client.query(
        `INSERT INTO personas
           (id, name, avatar_url, post_image_url, traits, backstory, creator_id, is_public)
         VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, $8)`,
        values
      )
    }
  }
  await client.query("COMMIT")
  console.log(`Imported ${documents.length} personas`)
} catch (error) {
  await client.query("ROLLBACK")
  throw error
} finally {
  client.release()
  await pool.end()
}

function normalizeUrl(value, seed, kind) {
  const text = String(value || "").trim()
  const markdown = text.match(/^\[([^\]]+)\]\((https?:\/\/[^)]+)\)(?:\s+.*)?$/)
  if (markdown) return markdown[2]
  if (/^https?:\/\//.test(text)) return text
  return kind === "avatar"
    ? `https://api.dicebear.com/7.x/bottts/png?seed=${encodeURIComponent(seed)}`
    : `https://picsum.photos/seed/${encodeURIComponent(seed)}/800/600`
}
