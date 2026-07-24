package com.example.persona.core.auth

import android.util.Log
import com.example.persona.core.util.UsernameGenerator
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import androidx.activity.ComponentActivity
import com.example.persona.features.auth.TAG
import com.google.firebase.auth.PhoneAuthOptions


interface PhoneVerificationCallback {
    fun onCodeSent(verificationId: String)
    fun onVerificationCompleted(credential: PhoneAuthCredential)
    fun onVerificationFailed(e: Exception)
}
@Singleton
class AuthManager @Inject constructor(
    private val auth: FirebaseAuth
) {
    private val _isLoggedIn = MutableStateFlow(auth.currentUser != null)

    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val currentUserId: String? get() = auth.currentUser?.uid
    internal val offlineUserId = "OFFLINE_USER"
    val currentRepoId: String get() = currentUserId ?: offlineUserId

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _isLoggedIn.value = firebaseAuth.currentUser != null
        }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
        ensureDisplayName()
    }

    suspend fun signUp(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).await()
        ensureDisplayName()
    }

    suspend fun signInWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await()
        ensureDisplayName()
    }

    suspend fun signInAnonymously() {
        auth.signInAnonymously().await()
        ensureDisplayName()
    }

    fun logout() {
        auth.signOut()
    }

    fun startPhoneNumberVerification(
        phoneNumber: String,
        callback: PhoneVerificationCallback,
        activity: ComponentActivity,
        timeoutSeconds: Long = 60L
    ) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    callback.onCodeSent(verificationId)
                    resendToken = token
                }

                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    callback.onVerificationCompleted(credential)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    callback.onVerificationFailed(e)
                }
            })
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    suspend fun signInWithPhoneNumber(verificationId: String, code: String) {
        Log.d(TAG, "signInWithPhoneNumber called with verificationId: $verificationId")
        try {
            val credential = PhoneAuthProvider.getCredential(verificationId, code)
            Log.d(TAG, "Phone credential created, signing in...")
            auth.signInWithCredential(credential).await()
            ensureDisplayName()
            Log.d(TAG, "Phone sign in successful")
        } catch (e: Exception) {
            Log.e(TAG, "Error signing in with phone number", e)
            throw e
        }
    }

    suspend fun signInWithCredential(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential).await()
        ensureDisplayName()
    }

    private suspend fun ensureDisplayName() {
        val user = auth.currentUser
        if (user != null && user.displayName.isNullOrBlank()) {
            val randomName = UsernameGenerator.generate()
            val profileUpdates = UserProfileChangeRequest.Builder()
                .setDisplayName(randomName)
                .build()
            try {
                user.updateProfile(profileUpdates).await()
                Log.d("AuthManager", "User display name updated to: $randomName")
            } catch (e: Exception) {
                Log.e("AuthManager", "Error updating user profile", e)
            }
        }
    }
}