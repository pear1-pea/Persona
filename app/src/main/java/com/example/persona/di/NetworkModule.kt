package com.example.persona.di

import com.example.persona.BuildConfig
import com.example.persona.core.auth.AuthManager
import com.example.persona.core.auth.AuthTokenProvider
import com.example.persona.data.remote.AuthApi
import com.example.persona.data.remote.BackendConfig
import com.example.persona.data.remote.CloudChatApi
import com.example.persona.data.remote.PersonaBackendApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.coroutines.runBlocking
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.io.IOException
import javax.inject.Singleton
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideBackendConfig(): BackendConfig {
        val url = BuildConfig.PERSONA_BACKEND_BASE_URL.trim().toHttpUrlOrNull()
            ?.takeIf {
                it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
                    it.query == null && it.fragment == null && it.encodedPath == "/"
            }
        return BackendConfig(
            baseUrl = url?.toString() ?: "https://backend.invalid/",
            modelId = BuildConfig.DEEPSEEK_MODEL_ID,
            isConfigured = url != null
        )
    }

    @Provides
    @Singleton
    fun provideAuthTokenProvider(authManager: AuthManager): AuthTokenProvider = authManager

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenProvider: AuthTokenProvider, config: BackendConfig): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            if (!config.isConfigured) throw IOException("请配置有效的 HTTPS Backend 地址")
            val endpoint = checkNotNull(config.baseUrl.toHttpUrlOrNull())
            val url = chain.request().url
            if (url.scheme != endpoint.scheme || url.host != endpoint.host || url.port != endpoint.port) {
                throw IOException("拒绝向其他服务发送登录凭证")
            }
            val anonymousAllowed = chain.request().allowsAnonymousRead()
            val publicList = chain.request().isPublicPersonaList()
            val token = if (publicList) {
                null
            } else if (anonymousAllowed) {
                tokenProvider.currentToken()
            } else {
                tokenProvider.currentToken() ?: runBlocking { tokenProvider.refreshToken() }
            }
            if (!anonymousAllowed && token.isNullOrBlank()) throw IOException("请重新登录后访问云端")
            val builder = chain.request().newBuilder()
                .removeHeader("Authorization")
                .header("Content-Type", "application/json")
            token
                ?.takeIf { it.isNotBlank() }
                ?.let { builder.header("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }

        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            redactHeader("Cookie")
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .authenticator { _, response ->
                if (response.retryCount() >= 2) return@authenticator null
                val previousToken = response.request.header("Authorization")
                tokenProvider.invalidateSession()
                if (response.request.allowsAnonymousRead() && previousToken != null) {
                    return@authenticator response.request.newBuilder()
                        .removeHeader("Authorization")
                        .build()
                }
                null
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("sse")
    fun provideSseOkHttpClient(client: OkHttpClient): OkHttpClient {
        return client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, config: BackendConfig): Retrofit {
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthRetrofit(config: BackendConfig): Retrofit {
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(@Named("auth") retrofit: Retrofit): AuthApi {
        return retrofit.create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    fun providePersonaBackendApi(retrofit: Retrofit): PersonaBackendApi {
        return retrofit.create(PersonaBackendApi::class.java)
    }

    @Provides
    @Singleton
    @Named("sse")
    fun provideSseRetrofit(
        @Named("sse") client: OkHttpClient,
        config: BackendConfig
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideCloudChatApi(@Named("sse") retrofit: Retrofit): CloudChatApi {
        return retrofit.create(CloudChatApi::class.java)
    }
}

private fun Response.retryCount(): Int {
    var count = 1
    var previous = priorResponse
    while (previous != null) {
        count += 1
        previous = previous.priorResponse
    }
    return count
}

private fun Request.isPublicPersonaList(): Boolean {
    return method == "GET" && url.encodedPath == "/api/personas/public"
}

private fun Request.allowsAnonymousRead(): Boolean {
    return isPublicPersonaList() ||
        (method == "GET" && url.encodedPath.matches(Regex("/api/personas/[^/]+")))
}
