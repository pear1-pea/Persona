package com.example.persona.evaluation

import com.google.gson.JsonObject

internal enum class M5LifecycleAction { CANCEL, RESEND, DELETE, REVOKE_CLOUD, REVISE, REPLAY, EXECUTOR }

internal data class M5LifecycleEvent(
    val action: M5LifecycleAction,
    val target: String? = null,
    val executor: String? = null,
    val replacementRef: String? = null,
    val replacementRevision: Int? = null,
    val replacementContent: String? = null
) {
    companion object {
        fun read(row: JsonObject): M5LifecycleEvent {
            val action = when (row.get("action")?.asString) {
                "cancel" -> M5LifecycleAction.CANCEL
                "resend" -> M5LifecycleAction.RESEND
                "delete" -> M5LifecycleAction.DELETE
                "revoke_cloud" -> M5LifecycleAction.REVOKE_CLOUD
                "revise" -> M5LifecycleAction.REVISE
                "replay" -> M5LifecycleAction.REPLAY
                "executor" -> M5LifecycleAction.EXECUTOR
                else -> throw IllegalArgumentException("Unknown fixture lifecycle event")
            }
            if (action in setOf(M5LifecycleAction.CANCEL, M5LifecycleAction.RESEND)) {
                require(row.keySet() == setOf("action"))
                return M5LifecycleEvent(action)
            }
            if (action == M5LifecycleAction.EXECUTOR) {
                val executor = row.get("value").asString
                require(row.keySet() == setOf("action", "value") && executor in setOf("local", "cloud"))
                return M5LifecycleEvent(action, executor = executor)
            }
            val target = row.get("target").asString
            require(target.isNotBlank())
            if (action in setOf(M5LifecycleAction.DELETE, M5LifecycleAction.REVOKE_CLOUD)) {
                require(row.keySet() == setOf("action", "target"))
                return M5LifecycleEvent(action, target = target)
            }
            require(row.keySet() == setOf("action", "target", "replacement"))
            val replacement = row.getAsJsonObject("replacement")
            require(replacement.keySet().all { it in setOf("ref", "version", "content", "updated_at", "deleted") })
            val ref = replacement.get("ref").asString
            val revision = replacement.get("version").asInt
            require(ref.isNotBlank() && revision > 1)
            return M5LifecycleEvent(action, target, replacementRef = ref, replacementRevision = revision,
                replacementContent = replacement.get("content")?.asString)
        }
    }
}
