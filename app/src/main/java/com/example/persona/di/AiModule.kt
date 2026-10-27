package com.example.persona.di

import com.example.persona.core.ai.AndroidSymlinkChecker
import com.example.persona.core.ai.SymlinkChecker
import com.example.persona.core.ai.mnn.NativeMnnSession
import com.example.persona.core.ai.mnn.NativeSessionFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideSymlinkChecker(): SymlinkChecker = AndroidSymlinkChecker

    @Provides
    @Singleton
    fun provideNativeSessionFactory(): NativeSessionFactory = NativeSessionFactory {
        NativeMnnSession()
    }
}
