package com.example.persona.core.ai

/**
 * Failure classes used by model admission and retry policy.
 *
 * Permanent failures require a reinstall or a deliberate user retry; transient
 * failures get a bounded backoff instead of locking the model for 24 hours.
 */
enum class LocalModelFailureType {
    CORRUPT,
    INCOMPATIBLE,
    OUT_OF_MEMORY,
    THERMAL,
    USER_CANCELLED,
    TRANSIENT,
    UNKNOWN
}

data class LocalModelFailure(
    val count: Int,
    val lastFailureAtMillis: Long,
    val type: LocalModelFailureType
) {
    val isPermanent: Boolean
        get() = type == LocalModelFailureType.CORRUPT ||
            type == LocalModelFailureType.INCOMPATIBLE

    val retryDelayMillis: Long
        get() = when (type) {
            LocalModelFailureType.CORRUPT,
            LocalModelFailureType.INCOMPATIBLE -> Long.MAX_VALUE
            LocalModelFailureType.USER_CANCELLED -> 0L
            LocalModelFailureType.OUT_OF_MEMORY ->
                backoff(30_000L, 10 * 60_000L)
            LocalModelFailureType.THERMAL ->
                backoff(60_000L, 30 * 60_000L)
            LocalModelFailureType.TRANSIENT,
            LocalModelFailureType.UNKNOWN ->
                backoff(15_000L, 5 * 60_000L)
        }

    fun retryAtMillis(): Long {
        if (isPermanent) return Long.MAX_VALUE
        return lastFailureAtMillis + retryDelayMillis
    }

    fun isReadyToRetry(nowMillis: Long): Boolean {
        if (type == LocalModelFailureType.USER_CANCELLED) return true
        return nowMillis >= retryAtMillis()
    }

    private fun backoff(baseMillis: Long, maxMillis: Long): Long {
        val exponent = (count - 1).coerceIn(0, 6)
        return (baseMillis shl exponent).coerceAtMost(maxMillis)
    }
}

fun classifyLocalModelFailure(error: Throwable?): LocalModelFailureType {
    if (error == null) return LocalModelFailureType.UNKNOWN
    return when (error) {
        is OutOfMemoryError -> LocalModelFailureType.OUT_OF_MEMORY
        is UnsatisfiedLinkError -> LocalModelFailureType.INCOMPATIBLE
        is java.io.FileNotFoundException -> LocalModelFailureType.CORRUPT
        is SecurityException -> LocalModelFailureType.CORRUPT
        else -> {
            val message = error.message.orEmpty()
            when {
                message.contains("config", ignoreCase = true) ||
                    message.contains("tokenizer", ignoreCase = true) ||
                    message.contains("no such file", ignoreCase = true) ||
                    message.contains("symbolic link", ignoreCase = true) ->
                    LocalModelFailureType.CORRUPT
                message.contains("memory", ignoreCase = true) ||
                    message.contains("alloc", ignoreCase = true) ->
                    LocalModelFailureType.OUT_OF_MEMORY
                message.contains("symbol", ignoreCase = true) ||
                    message.contains("abi", ignoreCase = true) ->
                    LocalModelFailureType.INCOMPATIBLE
                else -> LocalModelFailureType.TRANSIENT
            }
        }
    }
}
