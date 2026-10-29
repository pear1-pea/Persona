import { z } from "zod"

const maxMessages = Number(process.env.MAX_MESSAGES ?? 24)
const maxMessageChars = Number(process.env.MAX_MESSAGE_CHARS ?? 12000)

export const createPersonaSchema = z.object({
    name: z.string().trim().min(1).max(120),
    traits: z.array(z.string().trim().min(1).max(80)).max(20),
    backstory: z.string().trim().min(1).max(12000),
    isPublic: z.boolean().default(true)
}).strict()

export const updatePersonaSchema = createPersonaSchema

export const authSchema = z.object({
  email: z.string().trim().toLowerCase().email().max(320),
  password: z.string().min(8).max(256)
}).strict()

export const changePasswordSchema = z.object({
  currentPassword: z.string().min(8).max(256),
  newPassword: z.string().min(8).max(256)
}).strict()

export const deleteAccountSchema = z.object({
  currentPassword: z.string().min(8).max(256)
}).strict()

export const chatSchema = z.object({
  model: z.string().trim().min(1).max(100),
  messages: z.array(z.object({
    role: z.enum(["system", "user", "assistant"]),
    content: z.string().max(maxMessageChars)
  }).strict()).min(1).max(maxMessages),
  stream: z.literal(true),
  temperature: z.number().min(0).max(2).optional(),
  top_p: z.number().gt(0).max(1).optional(),
  thinking: z.object({ type: z.enum(["enabled", "disabled"]) }).strict().optional(),
  max_tokens: z.number().int().positive().max(Number.MAX_SAFE_INTEGER).optional()
}).strict()

export const personaIdSchema = z.string().uuid()
export const sessionIdSchema = z.string().uuid()
