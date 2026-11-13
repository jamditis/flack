package tech.amditis.flack.core

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/** Slack OAuth with PKCE for a public client (docs/SLACK_SIGN_IN.md). No client secret. */
object SlackOAuth {
    const val AUTHORIZE_URL = "https://slack.com/oauth/v2/authorize"
    /** A custom scheme: Slack treats it as a desktop redirect, with a secretless refresh. */
    const val REDIRECT_URI = "tech.amditis.flack://oauth"
    val USER_SCOPES = listOf("chat:write", "search:read", "users:read")

    /** URL-safe Base64 of 32 random bytes: 43 characters, inside RFC 7636's 43 to 128. */
    fun verifier(random: ByteArray): String {
        require(random.size == 32) { "verifier needs 32 random bytes" }
        return base64Url(random)
    }

    /** S256, the only method Slack supports. */
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(clientId: String, state: String, challenge: String): String {
        val params = listOf(
            "client_id" to clientId,
            // User token only. Slack refuses bot scopes for public clients.
            "scope" to "",
            "user_scope" to USER_SCOPES.joinToString(","),
            "redirect_uri" to REDIRECT_URI,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        return AUTHORIZE_URL + "?" + params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

enum class SignInFailure {
    /** No sign-in was started, or it was already used. */
    NO_ATTEMPT,
    /** The result does not belong to the sign-in Flack started. */
    STATE_MISMATCH,
    /** Slack returned an error, such as a refusal or a pending admin approval. */
    SLACK_ERROR,
    NO_CODE,
}

sealed interface SignInCallback {
    data class Exchange(val code: String, val verifier: String) : SignInCallback {
        override fun toString() = "Exchange([omitted])"
    }
    data class Failed(val reason: SignInFailure) : SignInCallback
}

/**
 * The one pending sign-in. The verifier and state live in memory only: if Android
 * stops the app during the browser step, the person starts again.
 */
class SignInAttempts(private val randomBytes: (Int) -> ByteArray) {
    private class Pending(val state: String, val verifier: String)

    private var pending: Pending? = null

    class Start(val state: String, val challenge: String)

    /** Starts a new attempt and drops any earlier one. */
    @Synchronized
    fun start(): Start {
        val verifier = SlackOAuth.verifier(randomBytes(32))
        val state = randomBytes(16).joinToString("") { "%02x".format(it) }
        pending = Pending(state, verifier)
        return Start(state, SlackOAuth.challenge(verifier))
    }

    /**
     * Matches a redirect to the pending attempt. A result with the wrong state is
     * refused and leaves the real attempt pending, so a forged redirect cannot end it.
     * A matching result uses the attempt up, whatever its outcome.
     */
    @Synchronized
    fun complete(state: String?, code: String?, error: String?): SignInCallback {
        val current = pending ?: return SignInCallback.Failed(SignInFailure.NO_ATTEMPT)
        if (state == null || !MessageDigest.isEqual(state.toByteArray(), current.state.toByteArray())) {
            return SignInCallback.Failed(SignInFailure.STATE_MISMATCH)
        }
        pending = null
        if (error != null) return SignInCallback.Failed(SignInFailure.SLACK_ERROR)
        if (code.isNullOrEmpty()) return SignInCallback.Failed(SignInFailure.NO_CODE)
        return SignInCallback.Exchange(code, current.verifier)
    }
}

/**
 * One person's sign-in for one workspace. Tokens never leave this object in text:
 * [toString] omits them, so a log line or exception cannot carry them.
 */
data class WorkspaceToken(
    val teamId: String,
    val teamName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    /** Null when Slack issued a token that does not rotate. */
    val refreshToken: String?,
    /** [Long.MAX_VALUE] when the token does not expire. */
    val expiresAtMillis: Long,
    /** The refresh token stopped working; the person must sign in again. */
    val needsSignIn: Boolean = false,
    /** Replaced tokens that Slack did not confirm revoking; sign-out tries them again. */
    val staleTokens: List<String> = emptyList(),
    /** A refresh may have issued tokens Flack never received, so sign-out cannot be confirmed. */
    val unconfirmedRefresh: Boolean = false,
) {
    fun needsRefresh(nowMillis: Long): Boolean =
        refreshToken != null && expiresAtMillis - nowMillis < REFRESH_MARGIN_MILLIS

    override fun toString() = "WorkspaceToken([omitted])"

    companion object {
        /** Refresh a token that expires within five minutes. */
        const val REFRESH_MARGIN_MILLIS = 5 * 60 * 1000L
    }
}
