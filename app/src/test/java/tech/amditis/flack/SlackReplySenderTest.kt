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
    private val transport = SlackTransport { method, form, bearer -> Call(method, form, bearer).also(calls::add).let(respond) }
    private var now = 1_000_000L
    private val store by lazy { AccountStore(folder.root.resolve("slack-accounts.bin"), PlainSealer) }
    private val sender by lazy { SlackReplySender(SlackAccounts(store, transport, "111.222") { now }, transport) }

    private val channel = ApiDestination("T0PAPER001", "C0KITES001", null)
    private val thread = ApiDestination("T0PAPER001", "C0KITES001", "1700000000.000100")

    private fun signedIn(expiresAt: Long = now + 12 * 3_600_000L) = store.save(
        listOf(
            WorkspaceToken(
                teamId = "T0PAPER001", teamName = "Paper Kites", userId = "U0RILEY001", userName = "riley",
                accessToken = "xoxe.xoxp-access-1", refreshToken = "xoxe-1-refresh-1", expiresAtMillis = expiresAt,
            ),
        ),
    )

    private fun posted(channel: String = "C0KITES001", threadTs: String? = null): JSONObject {
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
        assertEquals(mapOf("channel" to "C0KITES001", "text" to "Kites &amp; string &lt;soon&gt;"), post.form)
        assertEquals("xoxe.xoxp-access-1", post.bearer)
    }

    @Test fun aThreadReplyNamesItsThreadAndSlackMustConfirmIt() {
        signedIn()
        respond = { posted(threadTs = "1700000000.000100") }
        assertEquals(ApiReplyResult(HandoffStatus.POSTED), sender.send(thread, "On my way"))
        assertEquals("1700000000.000100", posts().single().form["thread_ts"])
    }

    @Test fun anAnswerForAnotherPlaceIsNotReportedAsConfirmed() {
        signedIn()
        respond = { posted(channel = "C0OTHER0001") }
        assertEquals(HandoffStatus.POSTED_PLACE_UNCONFIRMED, sender.send(channel, "On my way").status)
        respond = { posted() }
        assertEquals(HandoffStatus.POSTED_PLACE_UNCONFIRMED, sender.send(thread, "On my way").status)
        respond = { posted(threadTs = "1700000000.000100") }
        assertEquals(HandoffStatus.POSTED_PLACE_UNCONFIRMED, sender.send(channel, "On my way").status)
    }

    @Test fun aDocumentedRefusalIsNotSentWithSlacksCode() {
        signedIn()
        respond = { throw SlackCallException(SlackError.REFUSED, "not_in_channel") }
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT, "not_in_channel"), sender.send(channel, "On my way"))
        assertEquals(1, posts().size)
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
        assertEquals(ApiReplyResult(HandoffStatus.NOT_SENT_SIGN_IN), sender.send(ApiDestination("T0OTHER0001", "C0KITES001", null), "On my way"))
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
