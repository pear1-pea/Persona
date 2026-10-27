import test from "node:test"
import assert from "node:assert/strict"
import { personaDto } from "../src/dto.js"

test("personaDto maps database snake_case fields to the API contract", () => {
  const dto = personaDto({
    id: "persona-1",
    name: "Aetheris",
    avatar_url: "https://example.com/avatar.png",
    post_image_url: "https://example.com/post.png",
    traits: ["calm", "curious"],
    backstory: "A test persona",
    creator_id: "user-1",
    is_public: true
  })

  assert.deepEqual(dto, {
    id: "persona-1",
    name: "Aetheris",
    avatarUrl: "https://example.com/avatar.png",
    postImageUrl: "https://example.com/post.png",
    traits: ["calm", "curious"],
    backstory: "A test persona",
    creatorId: "user-1",
    isPublic: true
  })
})
