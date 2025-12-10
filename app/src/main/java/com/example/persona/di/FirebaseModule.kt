package com.example.persona.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseApp(@ApplicationContext context: Context): FirebaseApp {
        return if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
                ?: throw IllegalStateException("FirebaseApp initialization failed. Please check your google-services.json file.")
        } else {
            FirebaseApp.getInstance()
        }
    }

    @Provides
    @Singleton
    fun provideFirebaseAuth(app: FirebaseApp): FirebaseAuth {
        return FirebaseAuth.getInstance(app)
    }

    @Provides
    @Singleton
    fun provideFirebaseFirestore(app: FirebaseApp): FirebaseFirestore {
        return FirebaseFirestore.getInstance(app)
    }
}