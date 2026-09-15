package com.example.persona.data.remote

sealed class CloudGenerationException(message: String) : Exception(message) {
    class NotConfigured : CloudGenerationException(
        "DeepSeek 未配置 API Key，请在 local.properties 填写 DEEPSEEK_API_KEY。"
    )

    class Network(cause: Throwable) : CloudGenerationException("DeepSeek 云端连接失败，请稍后再试。") {
        init {
            initCause(cause)
        }
    }

    class HttpError(val code: Int) : CloudGenerationException(
        "DeepSeek 云端请求失败($code)，请检查 API Key、模型名或余额。"
    )
}
