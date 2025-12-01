package com.example.persona.di

import com.example.persona.domain.repository.PersonaRepository
import com.example.persona.data.repository.MockPersonaRepository
import com.example.persona.data.repository.RoomChatRepository
import com.example.persona.data.repository.RoomPersonaRepository
import com.example.persona.domain.repository.ChatRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPersonaRepository(
        impl: RoomPersonaRepository
    ): PersonaRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(
        impl: RoomChatRepository
    ): ChatRepository
}