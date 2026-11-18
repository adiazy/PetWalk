package com.alexdiaz.mailtriage

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft sign-in for personal Hotmail / Outlook.com / Live accounts.
 * Microsoft no longer accepts passwords over IMAP, so we get an OAuth token
 * and log in with XOAUTH2.
 */
object OutlookAuth {
    private val config = AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
    )
    const val REDIRECT_URI = "com.alexdiaz.mailtriage://oauth"
    private const val SCOPES =
        "https://outlook.office.com/IMAP.AccessAsUser.All offline_access openid email"

    fun signInIntent(context: Context, clientId: String, loginHint: String): Intent {
        val request = AuthorizationRequest.Builder(
            config, clientId, ResponseTypeValues.CODE, Uri.parse(REDIRECT_URI)
        )
            .setScope(SCOPES)
            .setLoginHint(loginHint.ifBlank { null })
            .setPrompt("select_account")
            .build()
        return AuthorizationService(context).getAuthorizationRequestIntent(request)
    }

    /** Finishes sign-in; returns the serialized AuthState to store with the account. */
    suspend fun completeSignIn(context: Context, data: Intent): String {
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) throw error ?: IllegalStateException("Sign-in was cancelled")

        val state = AuthState(response, error)
        val service = AuthorizationService(context)
        try {
            suspendCancellableCoroutine { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { tok, ex ->
                    state.update(tok, ex)
                    if (tok != null) cont.resume(Unit)
                    else cont.resumeWithException(ex ?: IllegalStateException("Token exchange failed"))
                }
            }
        } finally {
            service.dispose()
        }
        return state.jsonSerializeString()
    }

    /** Returns a fresh access token and the (possibly refreshed) AuthState JSON. */
    suspend fun freshToken(context: Context, authStateJson: String): Pair<String, String> {
        val state = AuthState.jsonDeserialize(authStateJson)
        val service = AuthorizationService(context)
        try {
            val token = suspendCancellableCoroutine { cont ->
                state.performActionWithFreshTokens(service) { access, _, ex ->
                    if (access != null) cont.resume(access)
                    else cont.resumeWithException(
                        ex ?: IllegalStateException("Microsoft sign-in expired — sign in again")
                    )
                }
            }
            return token to state.jsonSerializeString()
        } finally {
            service.dispose()
        }
    }
}
