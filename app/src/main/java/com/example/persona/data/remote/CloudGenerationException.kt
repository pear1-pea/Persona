package com.example.persona.data.remote

sealed class CloudGenerationException(message: String) : Exception(message) {
    class NotConfigured : CloudGenerationException(
        "Backend session 未配置，请先登录。"
    )

    class Network(cause: Throwable) : CloudGenerationException("Backend 云端连接失败，请稍后再试。") {
        init {
            initCause(cause)
        }
    }

    class HttpError(val code: Int) : CloudGenerationException(
        "Backend 云端请求失败($code)，请稍后再试。"
    )
}
