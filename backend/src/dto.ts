export function personaDto(row: Record<string, unknown>): Record<string, unknown> {
  return {
    id: row.id,
    name: row.name,
    avatarUrl: row.avatar_url,
    postImageUrl: row.post_image_url,
    traits: row.traits,
    backstory: row.backstory,
    creatorId: row.creator_id,
    isPublic: row.is_public
  }
}
