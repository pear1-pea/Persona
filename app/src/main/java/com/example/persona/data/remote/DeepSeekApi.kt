package com.example.persona.data.remote

import com.example.persona.data.remote.dto.ChatRequest
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming

interface DeepSeekApi {

    @POST("chat/completions")
    @Streaming
    fun streamChat(@Body request: ChatRequest): Call<ResponseBody>
}
