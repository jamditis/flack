package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackSignInTest {
    private var counter = 0
    private val attempts = SignInAttempts { size -> ByteArray(size) { (counter++).toByte() } }

    @Test fun challengeMatchesSlacksDocumentedExample() {
        // From https://docs.slack.dev/authentication/using-pkce
        assertEquals("ldMBaaWcQYtSATMV_IG8mf3wp7A6EW80arYoSW80ntU", SlackOAuth.challenge("secretpassword"))
    }

    @Test fun verifierIsFortyThreeUrlSafeCharacters() {
        val verifier = SlackOAuth.verifier(ByteArray(32) { 0xFF.toByte() })
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun authorizeUrlAsksForUserScopesOnlyWithS256() {
        val url = SlackOAuth.authorizeUrl("111.222", "abc", "xyz")
        assertTrue(url.startsWith("https://slack.com/oauth/v2/authorize?"))
        assertTrue(url.contains("&scope=&"))
        assertTrue(url.contains("user_scope=chat%3Awrite%2Csearch%3Aread%2Cusers%3Aread&"))
        assertTrue(url.contains("redirect_uri=tech.amditis.flack%3A%2F%2Foauth"))
        assertTrue(url.contains("code_challenge=xyz&code_challenge_method=S256"))
        assertFalse(url.contains("client_secret"))
    }

    @Test fun matchingRedirectGivesTheVerifierOnce() {
        val start = attempts.start()
        val result = attempts.complete(start.state, "code-1", null)
        assertTrue(result is SignInCallback.Exchange)
        assertEquals(start.challenge, SlackOAuth.challenge((result as SignInCallback.Exchange).verifier))
        assertEquals(SignInCallback.Failed(SignInFailure.NO_ATTEMPT), attempts.complete(start.state, "code-1", null))
    }

    @Test fun forgedRedirectIsRefusedAndKeepsTheRealAttempt() {
        val start = attempts.start()
        assertEquals(SignInCallback.Failed(SignInFailure.STATE_MISMATCH), attempts.complete("forged", "code-x", null))
        assertEquals(SignInCallback.Failed(SignInFailure.STATE_MISMATCH), attempts.complete(null, "code-x", null))
        assertTrue(attempts.complete(start.state, "code-1", null) is SignInCallback.Exchange)
    }

    @Test fun slackErrorOrMissingCodeEndsTheAttempt() {
        val first = attempts.start()
        assertEquals(SignInCallback.Failed(SignInFailure.SLACK_ERROR), attempts.complete(first.state, null, "access_denied"))
        assertEquals(SignInCallback.Failed(SignInFailure.NO_ATTEMPT), attempts.complete(first.state, "code", null))
        val second = attempts.start()
        assertEquals(SignInCallback.Failed(SignInFailure.NO_CODE), attempts.complete(second.state, "", null))
    }

    @Test fun aNewAttemptReplacesTheOldOne() {
        val old = attempts.start()
        attempts.start()
        assertEquals(SignInCallback.Failed(SignInFailure.STATE_MISMATCH), attempts.complete(old.state, "code", null))
    }

    @Test fun tokensNeverAppearInText() {
        val token = WorkspaceToken("T0INVENTED1", "Paper Kites", "U0INVENTED1", "riley", "xoxe.xoxp-secret", "xoxe-1-secret", 0L)
        assertFalse(token.toString().contains("secret"))
        assertFalse(SignInCallback.Exchange("code-secret", "verifier-secret").toString().contains("secret"))
    }

    @Test fun refreshesFiveMinutesBeforeExpiry() {
        val token = WorkspaceToken("T", "W", "U", "u", "a", "r", expiresAtMillis = 10 * 60 * 1000L)
        assertFalse(token.needsRefresh(4 * 60 * 1000L))
        assertTrue(token.needsRefresh(6 * 60 * 1000L))
        assertFalse(token.copy(refreshToken = null).needsRefresh(Long.MAX_VALUE - 1))
    }
}
