package com.example.persona.data.remote

import retrofit2.HttpException
import retrofit2.Response

internal fun Response<Void>.requireSuccessfulNoContent() {
    if (!isSuccessful) throw HttpException(this)
}
