package com.example.persona.data.repository

import android.util.Log
import com.example.persona.BuildConfig
import com.example.persona.data.remote.VolcApi
import com.example.persona.data.remote.dto.ChatRequest
import com.example.persona.data.remote.dto.ChatResponse
import com.example.persona.data.remote.dto.MessageDto
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import javax.inject.Inject
import kotlinx.coroutines.flow.toList

class CloudChatRepository @Inject constructor(
    private val api: VolcApi
) {
    fun streamResponse(systemPrompt: String, userMessage: String): Flow<String> = flow {

        val messages = listOf(
            MessageDto("system", systemPrompt), 
            MessageDto("user", userMessage)     
        )
        val request = ChatRequest(
            model = BuildConfig.VOLC_MODEL_ID, 
            messages = messages
        )

        val response = api.streamChat(request).execute()

        if (!response.isSuccessful) {
            Log.e("ChatRepo", "Error: ${response.errorBody()?.string()}")
            emit("Error: AI 脑子短路了...")
            return@flow
        }

        val source = response.body()?.byteStream()?.bufferedReader() ?: return@flow
        val gson = Gson()

        try {
            var line: String? = source.readLine()
            while (line != null) {
                if (line.startsWith("data:")) {
                    val jsonStr = line.substring(5).trim() 

                    if (jsonStr == "[DONE]") break 

                    try {
                        // parse JSON
                        val chatResponse = gson.fromJson(jsonStr, ChatResponse::class.java)
                        val content = chatResponse.choices.firstOrNull()?.delta?.content

                        if (!content.isNullOrEmpty()) {
                            emit(content)
                        }
                    } catch (e: Exception) {
                    }
                }
                line = source.readLine()
            }
        } finally {
            source.close()
        }

    }.flowOn(Dispatchers.IO) 

    suspend fun generatePersonaProfile(keywords: String): String {
        val systemPrompt = """
            You are a creative character designer.
            Task: Create a unique persona based on user keywords.
            
            IMPORTANT: You must return ONLY a raw JSON object. Do not wrap it in markdown code blocks (like ```json).
            The JSON structure must be:
            {
              "name": "Character Name",
              "backstory": "A short, engaging biography (under 100 words).",
              "traits": ["Trait1", "Trait2", "Trait3"]
            }
        """.trimIndent()

        val userPrompt = if (keywords.isBlank()) "Theme: Sci-Fi, Mysterious" else "Keywords: $keywords"

        val fullResponseBuilder = StringBuilder()

        streamResponse(systemPrompt, userPrompt).collect { token ->
            fullResponseBuilder.append(token)
        }

        return fullResponseBuilder.toString()
    }
}