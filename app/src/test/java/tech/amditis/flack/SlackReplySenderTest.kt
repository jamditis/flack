package tech.amditis.flack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.amditis.flack.core.ApiDestination
import tech.amditis.flack.core.WorkspaceToken

/** Invented workspaces, conversations and tokens only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlackReplySenderTest {
    @get:Rule val folder = TemporaryFolder()

    private class Call(val method: String, val form: Map<String, String>, val bearer: String?)

    private object PlainSealer : TokenSealer {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
        override fun destroy() = Unit
    }

    private val calls = mutableListOf<Call>()
    private var respond: (Call) -> JSONObject = { error("unexpected ${it.method}") }
    private var authTeam = "T0PAPER001"
    private var checkFailure: SlackCallException? = null
    private var afterCheck: () -> Unit = {}
    private val transport = SlackTransport { method, form, bearer ->
        val call = Call(method, form, bearer).also(calls::add)
        when (method) {
            "auth.test" -> {
                checkFailure?.let { throw it }
                afterCheck()
                JSONObject().put("ok", true).put("team_id", authTeam)
            }
            else -> respond(call)
        }
    }
    private var now = 1_000_000L
    private val store by lazy { AccountStore(folder.root.resolve("slack-accounts.bin"), PlainSealer) }
    private val sender by lazy { SlackReplySender(SlackAccounts(store, transport, "111.222") { now }, transport) }

    private val channel = ApiDestination("T0PAPER001", "D0KITES001")

    private fun signedIn(expiresAt: Long = now + 12 * 3_600_000L) = store.save(
        listOf(
            WorkspaceToken(
                teamId = "T0PAPER001", teamName = "Paper Kites", userId = "U0RILEY001", userName = "riley",
                accessToken = "xoxe.xoxp-access-1", refreshToken = "xoxe-1-refresh-1", expiresAtMillis = expiresAt,
            ),
        ),
    )

    private fun posted(channel: String = "D0KITES001", threadTs: String? = null): JSONObject {
        val message = JSONObject().put("text", "invented")
        threadTs?.let { message.put("thread_ts", it) }
        return JSONObject().put("ok", true).put("channel", channel).put("ts", "1700000100.000200").put("message", message)
    }

    private fun posts() = calls.filter { it.method == "chat.postMessage" }

    @Test fun aConfirmedPostSendsOnceToTheChannelWithEscapedText() {
        signedIn()
        respond = { posted() }
        assertEquals(ApiReplyResult(HandoffStatus.POSTED), sender.send(channel, "Kites & string <soon>"))
        val post = posts().single()
        assertEquals(mapOf("channel" to "D0KITES001", "text" to "Kites &amp; string &lt;soon&gt;"), post.form)
        assertEquals("xoxe.xoxp-access-1", post.bearer)
        assertEquals(listOf("auth.test", "chat.postMessage"), calls.map { it.method })
        assertEquals(emptyMap<String, String>(), calls[0].form)
        assertTrue(calls.all { it.bearer == post.bearer })
    }

    @Test fun aGroupReplyAlsoPostsOnlyAtTheTopLevel() {
        signedIn()
        respond = { posted(channel = "G0KITES001") }
        assertEquals(ApiReplyResult(HandoffStatus.POSTED), sender.send(ApiDestination("T0PAPER001", "G0KITES001"), "On my way"))
        assertEquals(mapOf("channel" to "G0KITES001", "text" to "On my way"), posts().single().form)
    }

    @Test fun anAnswerForAnotherPlaceIsNotReportedAsConfirmed() {
        signedIn()
        respond = { posted(channel = "C0OTHER0001") }
        assertEquals(HandoffStatus.POSTED_PLACE_UNCONFIRMED, sender.send(channel, "On my way").status)
        respond = { posted(threadTs = "1700000000.000100") }
        assertEquals(HandoffStatus.POSTED_PLACE_UNCONFIRMED, sender.send(channel, "On my way").status)
    }

    @Test fun aDocumentedRefusalIsNotSentWithSlacksCode() {
        signedIn()
        listOf("channel_not_found", "not_in_channel", "is_archived", "ratelimited", "rate_limited", "restricted_action_thread_locked").forEach { code ->
            calls.clear()
            respond = { throw SlackCallException(SlackError.REFUSED, code) }
            assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT, code), sender.send(channel, "On my way"))
            assertEquals(1, posts().size)
        }
    }

    @Test fun aFailureThatMayFollowAPartialPostIsUnknownAndNeverRetried() {
        signedIn()
        listOf(
            SlackCallException(SlackError.REFUSED, "internal_error"),
            SlackCallException(SlackError.REFUSED, "fatal_error"),
            SlackCallException(SlackError.REFUSED, "some_new_error"),
            SlackCallException(SlackError.NO_ANSWER),
            SlackCallException(SlackError.BAD_RESPONSE),
        ).forEach { failure ->
            calls.clear()
            respond = { throw failure }
            assertEquals(HandoffStatus.OUTCOME_UNKNOWN, sender.send(channel, "On my way").status)
            assertEquals(1, posts().size)
        }
    }

    @Test fun noConnectionMeansNotSent() {
        signedIn()
        respond = { throw SlackCallException(SlackError.NETWORK) }
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT), sender.send(channel, "On my way"))
        assertEquals(1, posts().size)
    }

    @Test fun aWorkspaceThatIsNotSignedInSendsNothing() {
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT_SIGN_IN), sender.send(channel, "On my way"))
        signedIn()
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT_SIGN_IN), sender.send(ApiDestination("T0OTHER0001", "D0KITES001"), "On my way"))
        assertTrue(calls.isEmpty())
    }

    @Test fun aTokenThatExpiresSoonIsRefreshedBeforeThePost() {
        signedIn(expiresAt = now + 60_000L)
        respond = {
            when (it.method) {
                "oauth.v2.access" -> JSONObject(
                    """{"ok":true,"team":{"id":"T0PAPER001"},"authed_user":{"id":"U0RILEY001","access_token":"xoxe.xoxp-access-2",
                    "refresh_token":"xoxe-1-refresh-2","expires_in":43200,"token_type":"user"}}""",
                )
                "auth.revoke" -> JSONObject("""{"ok":true,"revoked":true}""")
                "chat.postMessage" -> posted()
                else -> error(it.method)
            }
        }
        assertEquals(HandoffStatus.POSTED, sender.send(channel, "On my way").status)
        assertEquals("oauth.v2.access", calls.first().method)
        assertEquals("xoxe-1-refresh-1", calls.first().form["refresh_token"])
        assertEquals("xoxe.xoxp-access-2", posts().single().bearer)
    }

    @Test fun aCopyWithdrawnWhileTheReplyWaitedIsNotSent() {
        signedIn()
        respond = { posted() }
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT), sender.send(channel, "On my way") { false })
        assertTrue(posts().isEmpty())
    }

    @Test fun aMismatchedWorkspaceNeverPosts() {
        signedIn()
        respond = { error("must not post") }
        authTeam = "T0OTHER001"
        assertEquals(HandoffStatus.NOT_SENT, sender.send(channel, "On my way").status)
        authTeam = ""
        assertEquals(HandoffStatus.NOT_SENT, sender.send(channel, "On my way").status)
        assertTrue(posts().isEmpty())
    }

    @Test fun anyFailedConfirmationNeverPosts() {
        signedIn()
        respond = { error("must not post") }
        listOf(SlackCallException(SlackError.REFUSED, "invalid_auth"),
            SlackCallException(SlackError.NETWORK), SlackCallException(SlackError.NO_ANSWER),
            SlackCallException(SlackError.BAD_RESPONSE)).forEach {
            checkFailure = it
            assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT, it.slackCode), sender.send(channel, "On my way"))
        }
        checkFailure = SlackCallException(SlackError.REFUSED, "token_revoked")
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT_SIGN_IN, "token_revoked"), sender.send(channel, "On my way"))
        assertTrue(posts().isEmpty())
    }

    @Test fun withdrawalDuringConfirmationPreventsThePost() {
        signedIn()
        var current = true
        afterCheck = { current = false }
        assertEquals(HandoffStatus.NOT_SENT, sender.send(channel, "On my way") { current }.status)
        assertTrue(posts().isEmpty())
    }

    @Test fun aRefusedRefreshAsksForSignInAndSendsNothing() {
        signedIn(expiresAt = now + 60_000L)
        respond = { throw SlackCallException(SlackError.REFUSED, "invalid_refresh_token") }
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT_SIGN_IN), sender.send(channel, "On my way"))
        assertTrue(posts().isEmpty())
    }

    @Test fun aRefreshThatCannotReachSlackSendsNothing() {
        signedIn(expiresAt = now + 60_000L)
        respond = { throw SlackCallException(SlackError.NETWORK) }
        val result = sender.send(channel, "On my way")
        assertEquals(HandoffStatus.NOT_SENT, result.status)
        assertNull(result.slackCode)
        assertTrue(posts().isEmpty())
    }
}
