package com.example.persona.core.auth

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// Object identity distinguishes sessions even when an account signs in again.
class AuthIdentity internal constructor(
    val ownerId: String,
    internal val token: String?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuthIdentity>
}
