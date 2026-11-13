package tech.amditis.flack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Invented workspaces and tokens only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlackAccountsTest {
    @get:Rule val folder = TemporaryFolder()

    private class Call(val method: String, val form: Map<String, String>, val bearer: String?)

    /** Flips every byte, so a token that reaches the file unsealed is visible. */
    private class FlipSealer : TokenSealer {
        var destroyed = 0
        var sealFailure: Exception? = null
        var openFailure: Exception? = null
        override fun seal(plain: ByteArray): ByteArray {
            sealFailure?.let { throw it }
            return ByteArray(plain.size) { (plain[it].toInt() xor 0xFF).toByte() }
        }
        override fun open(sealed: ByteArray): ByteArray {
            openFailure?.let { throw it }
            return ByteArray(sealed.size) { (sealed[it].toInt() xor 0xFF).toByte() }
        }
        override fun destroy() { destroyed++ }
    }

    private val calls = mutableListOf<Call>()
    private var respond: (Call) -> JSONObject = { error("unexpected ${it.method}") }
    private val transport = SlackTransport { method, form, bearer ->
        Call(method, form, bearer).also(calls::add).let(respond)
    }
    private val sealer = FlipSealer()
    private var now = 1_000_000L
    private val file by lazy { folder.root.resolve("slack-accounts.bin") }
    private val accounts by lazy { SlackAccounts(AccountStore(file, sealer), transport, "111.222") { now } }

    private fun grant(team: String, access: String, refresh: String, scope: String = "chat:write,search:read,users:read") = JSONObject(
        """{"ok":true,"team":{"id":"$team","name":"Paper $team"},"authed_user":{"id":"U0INVENTED1","scope":"$scope",
        "access_token":"$access","refresh_token":"$refresh","expires_in":43200,"token_type":"user"}}""",
    )

    private fun signIn(team: String = "T0INVENTED1", access: String = "xoxe.xoxp-access-1", refresh: String = "xoxe-1-refresh-1") {
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant(team, access, refresh)
                "auth.test" -> JSONObject("""{"ok":true,"user":"riley"}""")
                "auth.revoke" -> JSONObject("""{"ok":true,"revoked":true}""")
                else -> error(it.method)
            }
        }
        accounts.complete("code-1", "verifier-1")
    }

    @Test fun signInSendsTheVerifierNotASecretAndSealsTheToken() {
        signIn()
        val exchange = calls.first()
        assertEquals("oauth.v2.access", exchange.method)
        assertEquals(mapOf("client_id" to "111.222", "code" to "code-1", "code_verifier" to "verifier-1", "redirect_uri" to "tech.amditis.flack://oauth"), exchange.form)
        assertNull(exchange.bearer)
        assertEquals("xoxe.xoxp-access-1", calls[1].bearer)
        val saved = accounts.workspaces().single()
        assertEquals("T0INVENTED1", saved.teamId)
        assertEquals("riley", saved.userName)
        assertEquals(now + 43_200_000L, saved.expiresAtMillis)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("access-1"))
    }

    @Test fun missingScopeIsRefusedRevokedAndNotSaved() {
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant("T0INVENTED1", "xoxe.xoxp-partial", "r", scope = "chat:write")
                "auth.revoke" -> JSONObject("""{"ok":true,"revoked":true}""")
                else -> error(it.method)
            }
        }
        try {
            accounts.complete("code-1", "verifier-1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals("missing_scope", e.slackCode)
        }
        assertEquals(listOf("xoxe.xoxp-partial", "r"), calls.filter { it.method == "auth.revoke" }.map { it.bearer })
        assertTrue(accounts.workspaces().isEmpty())
    }

    @Test fun keepsOneTokenPerWorkspace() {
        signIn(team = "T0INVENTED1")
        signIn(team = "T0INVENTED2", access = "xoxe.xoxp-access-2")
        signIn(team = "T0INVENTED1", access = "xoxe.xoxp-access-3")
        assertEquals(setOf("T0INVENTED1", "T0INVENTED2"), accounts.workspaces().map { it.teamId }.toSet())
        assertEquals("xoxe.xoxp-access-3", accounts.accessToken("T0INVENTED1"))
        assertNull(accounts.accessToken("T0OTHER0001"))
    }

    @Test fun refreshesNearExpiryWithoutASecretAndKeepsTheNewRefreshToken() {
        signIn()
        now += 43_200_000L - 60_000L
        respond = {
            JSONObject("""{"ok":true,"team":{"id":"T0INVENTED1"},"access_token":"xoxe.xoxp-access-2","refresh_token":"xoxe-1-refresh-2","expires_in":43200,"token_type":"user"}""")
        }
        assertEquals("xoxe.xoxp-access-2", accounts.accessToken("T0INVENTED1"))
        assertEquals(mapOf("client_id" to "111.222", "grant_type" to "refresh_token", "refresh_token" to "xoxe-1-refresh-1"), calls.last { it.method == "oauth.v2.access" }.form)
        assertEquals("xoxe-1-refresh-2", accounts.workspaces().single().refreshToken)
        assertEquals(listOf("xoxe.xoxp-access-1", "xoxe-1-refresh-1"), calls.filter { it.method == "auth.revoke" }.map { it.bearer })
        assertEquals("riley", accounts.workspaces().single().userName)
    }

    @Test fun refusedRefreshAsksToSignInAgain() {
        signIn()
        now += 43_200_000L
        respond = { throw SlackCallException(SlackError.REFUSED, "invalid_refresh_token") }
        assertNull(accounts.accessToken("T0INVENTED1"))
        assertTrue(accounts.workspaces().single().needsSignIn)
        assertNull(accounts.accessToken("T0INVENTED1"))
    }

    @Test fun anOutageDuringRefreshKeepsTheSignIn() {
        signIn()
        now += 43_200_000L
        respond = { throw SlackCallException(SlackError.REFUSED, "internal_error") }
        try {
            accounts.accessToken("T0INVENTED1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals("internal_error", e.slackCode)
        }
        assertFalse(accounts.workspaces().single().needsSignIn)
    }

    @Test fun signingInAgainRevokesTheReplacedTokens() {
        signIn(access = "xoxe.xoxp-old", refresh = "xoxe-1-old")
        val previousCalls = calls.size
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant("T0INVENTED1", "xoxe.xoxp-new", "xoxe-1-new")
                "auth.test" -> JSONObject("""{"ok":true,"user":"sam"}""")
                else -> JSONObject("""{"ok":true,"revoked":true}""")
            }
        }
        assertTrue(accounts.complete("code-2", "verifier-2").previousRevoked)
        assertEquals(listOf("xoxe.xoxp-old", "xoxe-1-old"), calls.drop(previousCalls).filter { it.method == "auth.revoke" }.map { it.bearer })
        assertEquals("xoxe.xoxp-new", accounts.workspaces().single().accessToken)
    }

    @Test fun aReadFailureAfterTheGrantRevokesIt() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        sealer.openFailure = java.security.KeyStoreException("busy")
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant("T0INVENTED1", "xoxe.xoxp-access-1", "xoxe-1-refresh-1")
                "auth.test" -> JSONObject("""{"ok":true,"user":"riley"}""")
                else -> JSONObject("""{"ok":true,"revoked":true}""")
            }
        }
        try {
            accounts.complete("code-1", "verifier-1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals(SlackError.STORAGE, e.error)
        }
        assertEquals(listOf("xoxe.xoxp-access-1", "xoxe-1-refresh-1"), calls.filter { it.method == "auth.revoke" }.map { it.bearer })
    }

    @Test fun signOutOfAnAbsentWorkspaceIsNotConfirmed() {
        assertFalse(accounts.signOut("T0INVENTED1"))
    }

    @Test fun refreshIntoAnotherWorkspaceIsRejected() {
        signIn()
        now += 43_200_000L
        respond = { JSONObject("""{"ok":true,"team":{"id":"T0INVENTED9"},"access_token":"x","refresh_token":"y","expires_in":43200}""") }
        try {
            accounts.accessToken("T0INVENTED1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals(SlackError.BAD_RESPONSE, e.error)
        }
        assertEquals("xoxe.xoxp-access-1", accounts.workspaces().single().accessToken)
    }

    @Test fun signOutRevokesBothTokensThenForgetsAndDestroysTheKey() {
        signIn()
        respond = { JSONObject("""{"ok":true,"revoked":true}""") }
        assertTrue(accounts.signOut("T0INVENTED1"))
        val revokes = calls.filter { it.method == "auth.revoke" }
        assertEquals(listOf("xoxe.xoxp-access-1", "xoxe-1-refresh-1"), revokes.map { it.bearer })
        assertTrue(accounts.workspaces().isEmpty())
        assertFalse(file.exists())
        assertEquals(1, sealer.destroyed)
    }

    @Test fun signOutIsUnconfirmedWhenSlackKeepsTheRefreshToken() {
        signIn()
        respond = {
            if (it.bearer == "xoxe-1-refresh-1") throw SlackCallException(SlackError.REFUSED, "invalid_auth")
            JSONObject("""{"ok":true,"revoked":true}""")
        }
        assertFalse(accounts.signOut("T0INVENTED1"))
        assertTrue(accounts.workspaces().isEmpty())
    }

    @Test fun signOutWithoutNetworkStillForgetsTheWorkspace() {
        signIn(team = "T0INVENTED1")
        signIn(team = "T0INVENTED2")
        respond = { throw SlackCallException(SlackError.NETWORK) }
        assertFalse(accounts.signOut("T0INVENTED1"))
        assertEquals(listOf("T0INVENTED2"), accounts.workspaces().map { it.teamId })
        assertEquals(0, sealer.destroyed)
    }

    @Test fun unreadableFileIsClearedNotRepaired() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(accounts.workspaces().isEmpty())
        assertFalse(file.exists())
        assertEquals(1, sealer.destroyed)
    }

    @Test fun aPassingReadFailureKeepsTheSignIn() {
        signIn()
        sealer.openFailure = java.security.KeyStoreException("busy")
        try {
            accounts.workspaces()
            fail()
        } catch (e: SlackCallException) {
            assertEquals(SlackError.STORAGE, e.error)
        }
        assertTrue(file.exists())
        assertEquals(0, sealer.destroyed)
        sealer.openFailure = null
        assertEquals(1, accounts.workspaces().size)
    }

    @Test fun lostKeyClearsTheFile() {
        signIn()
        sealer.openFailure = SealedDataLost()
        assertTrue(accounts.workspaces().isEmpty())
        assertFalse(file.exists())
        assertEquals(1, sealer.destroyed)
    }

    @Test fun signInIsKeptWhenSlackCannotGiveTheName() {
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant("T0INVENTED1", "xoxe.xoxp-access-1", "xoxe-1-refresh-1")
                else -> throw SlackCallException(SlackError.NETWORK)
            }
        }
        assertEquals("U0INVENTED1", accounts.complete("code-1", "verifier-1").token.userName)
        assertEquals(1, accounts.workspaces().size)
    }

    @Test fun aGrantThatCannotBeSavedIsRevoked() {
        sealer.sealFailure = java.io.IOException("disk full")
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant("T0INVENTED1", "xoxe.xoxp-access-1", "xoxe-1-refresh-1")
                "auth.test" -> JSONObject("""{"ok":true,"user":"riley"}""")
                else -> JSONObject("""{"ok":true,"revoked":true}""")
            }
        }
        try {
            accounts.complete("code-1", "verifier-1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals(SlackError.STORAGE, e.error)
        }
        assertEquals(listOf("xoxe.xoxp-access-1", "xoxe-1-refresh-1"), calls.filter { it.method == "auth.revoke" }.map { it.bearer })
        assertFalse(file.exists())
    }

    @Test fun anUnsavedGrantSlackDidNotRevokeIsReported() {
        sealer.sealFailure = java.io.IOException("disk full")
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant("T0INVENTED1", "xoxe.xoxp-access-1", "xoxe-1-refresh-1")
                "auth.test" -> JSONObject("""{"ok":true,"user":"riley"}""")
                else -> throw SlackCallException(SlackError.NETWORK)
            }
        }
        try {
            accounts.complete("code-1", "verifier-1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals(SlackError.STORAGE, e.error)
            assertEquals(SlackAccounts.NOT_REVOKED, e.slackCode)
        }
    }

    @Test fun aBuildWithoutAClientIdStillSignsOut() {
        signIn()
        val unconfigured = SlackAccounts(AccountStore(file, sealer), transport, "") { now }
        assertEquals(1, unconfigured.workspaces().size)
        assertTrue(unconfigured.signOut("T0INVENTED1"))
        assertTrue(unconfigured.workspaces().isEmpty())
    }

    @Test fun aRefreshThatCannotBeSavedRevokesTheNewTokens() {
        signIn()
        now += 43_200_000L - 60_000L
        respond = {
            when (it.method) {
                "oauth.v2.access" -> {
                    // Fail the save of the new tokens, not the record written before the request.
                    sealer.sealFailure = java.io.IOException("disk full")
                    JSONObject("""{"ok":true,"team":{"id":"T0INVENTED1"},"access_token":"xoxe.xoxp-access-2","refresh_token":"xoxe-1-refresh-2","expires_in":43200,"token_type":"user"}""")
                }
                else -> JSONObject("""{"ok":true,"revoked":true}""")
            }
        }
        try {
            accounts.accessToken("T0INVENTED1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals(SlackError.STORAGE, e.error)
        }
        assertEquals(
            listOf("xoxe.xoxp-access-1", "xoxe-1-refresh-1", "xoxe.xoxp-access-2", "xoxe-1-refresh-2"),
            calls.filter { it.method == "auth.revoke" }.map { it.bearer },
        )
    }

    @Test fun signOutRevokesAnOldAccessTokenSlackDidNotConfirm() {
        signIn()
        now += 43_200_000L - 60_000L
        respond = {
            when (it.method) {
                "oauth.v2.access" -> JSONObject("""{"ok":true,"team":{"id":"T0INVENTED1"},"access_token":"xoxe.xoxp-access-2","refresh_token":"xoxe-1-refresh-2","expires_in":43200,"token_type":"user"}""")
                else -> throw SlackCallException(SlackError.NETWORK)
            }
        }
        assertEquals("xoxe.xoxp-access-2", accounts.accessToken("T0INVENTED1"))
        val before = calls.size
        respond = { JSONObject("""{"ok":true,"revoked":true}""") }
        assertTrue(accounts.signOut("T0INVENTED1"))
        assertEquals(
            setOf("xoxe.xoxp-access-1", "xoxe-1-refresh-1", "xoxe.xoxp-access-2", "xoxe-1-refresh-2"),
            calls.drop(before).filter { it.method == "auth.revoke" }.map { it.bearer }.toSet(),
        )
    }

    @Test fun signOutDoesNotTrustThePhoneClockForExpiry() {
        signIn()
        now += 86_400_000L
        respond = {
            if (it.bearer == "xoxe.xoxp-access-1") throw SlackCallException(SlackError.NETWORK)
            JSONObject("""{"ok":true,"revoked":true}""")
        }
        assertFalse(accounts.signOut("T0INVENTED1"))
    }

    @Test fun slacksAnswerThatATokenExpiredCountsAsRevoked() {
        signIn()
        respond = {
            if (it.bearer == "xoxe.xoxp-access-1") throw SlackCallException(SlackError.REFUSED, "token_expired")
            JSONObject("""{"ok":true,"revoked":true}""")
        }
        assertTrue(accounts.signOut("T0INVENTED1"))
    }

    @Test fun signOutRetriesTokensASecondSignInCouldNotRevoke() {
        signIn(access = "xoxe.xoxp-old", refresh = "xoxe-1-old")
        respond = {
            when (it.method) {
                "oauth.v2.access" -> grant("T0INVENTED1", "xoxe.xoxp-new", "xoxe-1-new")
                "auth.test" -> JSONObject("""{"ok":true,"user":"sam"}""")
                else -> throw SlackCallException(SlackError.NETWORK)
            }
        }
        assertFalse(accounts.complete("code-2", "verifier-2").previousRevoked)
        assertEquals("sam", accounts.workspaces().single().userName)
        val before = calls.size
        respond = { JSONObject("""{"ok":true,"revoked":true}""") }
        assertTrue(accounts.signOut("T0INVENTED1"))
        assertEquals(
            listOf("xoxe.xoxp-old", "xoxe-1-old", "xoxe.xoxp-new", "xoxe-1-new"),
            calls.drop(before).filter { it.method == "auth.revoke" }.map { it.bearer },
        )
    }

    @Test fun aLostRefreshAnswerLeavesSignOutUnconfirmed() {
        signIn()
        now += 43_200_000L - 60_000L
        respond = { throw SlackCallException(SlackError.NETWORK) }
        try {
            accounts.accessToken("T0INVENTED1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals(SlackError.NETWORK, e.error)
        }
        respond = { JSONObject("""{"ok":true,"revoked":true}""") }
        assertFalse(accounts.signOut("T0INVENTED1"))
    }

    @Test fun aRefreshSlackRefusedWithoutIssuingKeepsSignOutConfirmable() {
        signIn()
        now += 43_200_000L - 60_000L
        respond = { throw SlackCallException(SlackError.REFUSED, "invalid_client_id") }
        try {
            accounts.accessToken("T0INVENTED1")
            fail()
        } catch (e: SlackCallException) {
            assertEquals("invalid_client_id", e.slackCode)
        }
        respond = { JSONObject("""{"ok":true,"revoked":true}""") }
        assertTrue(accounts.signOut("T0INVENTED1"))
    }

    @Test fun aMalformedAnswerIsNotARefusal() {
        for (text in listOf("{}", """{"ok":null}""", """{"ok":false}""")) {
            try {
                SlackHttp.parse(text)
                fail(text)
            } catch (e: SlackCallException) {
                assertEquals(text, SlackError.BAD_RESPONSE, e.error)
            }
        }
    }

    @Test fun errorsCarryNoResponseText() {
        val error = try {
            SlackHttp.parse("""{"ok":false,"error":"invalid_auth","detail":"xoxe.xoxp-secret"}""")
            null
        } catch (e: SlackCallException) {
            e
        }!!
        assertEquals("invalid_auth", error.slackCode)
        assertFalse(error.toString().contains("secret"))
        assertNull(
            try {
                SlackHttp.parse("""{"ok":false,"error":"Riley said hi"}""")
                "parsed"
            } catch (e: SlackCallException) {
                e.slackCode
            },
        )
    }
}
