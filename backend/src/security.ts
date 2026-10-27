import { createHash, randomBytes } from "node:crypto"

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

export function hashSessionToken(token: string): Buffer {
  return createHash("sha256").update(token, "utf8").digest()
}

export function createSessionToken(): string {
  return randomBytes(32).toString("base64url")
}
