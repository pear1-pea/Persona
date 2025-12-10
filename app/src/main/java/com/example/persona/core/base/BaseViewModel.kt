package com.example.persona.core.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

abstract class BaseViewModel : ViewModel() {

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents = _errorEvents.asSharedFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        handleException(throwable)
    }

    protected fun launchCatching(
        block: suspend CoroutineScope.() -> Unit,
        onError: ((Throwable) -> Unit)? = null
    ) {
        viewModelScope.launch(exceptionHandler) {
            try {
                block()
            } catch (e: Exception) {
                if (onError != null) {
                    onError(e)
                } else {
                    handleException(e)
                }
            }
        }
    }

    protected fun emitError(message: String) {
        viewModelScope.launch {
            _errorEvents.emit(message)
        }
    }

    private fun handleException(t: Throwable) {
        val message = when (t) {
            is SocketTimeoutException -> "请求超时，服务器响应过慢"
            is IOException -> "网络连接异常，请检查网络设置"
            is HttpException -> {
                when (t.code()) {
                    401 -> "认证失败 (API Key 无效)"
                    403 -> "访问被拒绝"
                    404 -> "资源未找到"
                    500, 502, 503 -> "服务器内部错误，请稍后重试"
                    else -> "网络请求失败 (${t.code()})"
                }
            }
            else -> t.message ?: "发生未知错误"
        }
        emitError(message)
    }
}