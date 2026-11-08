package com.example.persona.di

import android.content.Context
import com.example.persona.core.ai.BufferedInferenceTelemetry
import com.example.persona.core.ai.telemetry.FileInferenceJournal
import com.example.persona.core.ai.telemetry.InferenceTelemetry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InferenceDiagnosticsModule {
    @Provides
    @Singleton
    fun provideInferenceJournal(@ApplicationContext context: Context): FileInferenceJournal =
        FileInferenceJournal(File(context.filesDir, "inference-diagnostics"))

    @Provides
    @Singleton
    fun provideInferenceTelemetry(journal: FileInferenceJournal): InferenceTelemetry =
        BufferedInferenceTelemetry(journal)
}
