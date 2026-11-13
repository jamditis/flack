package tech.amditis.flack

import tech.amditis.flack.core.ApiDestination

/** One API reply's result. [slackCode] is Slack's documented error word, never content. */
data class ApiReplyResult(val status: HandoffStatus, val slackCode: String? = null)

/**
 * Posts one car reply with `chat.postMessage` as the signed-in person
 * (docs/SLACK_SIGN_IN.md, Dispatch). Exactly one request per call, never retried.
 * Run off the main thread. The text is held only for the request.
 */
class SlackReplySender(private val accounts: SlackAccounts, private val transport: SlackTransport) {

    fun send(destination: ApiDestination, text: String): ApiReplyResult {
        // The token is looked up by the destination's workspace ID, so a reply can only
        // go out under that workspace's sign-in. Refreshed here if it expires soon.
        val token = try {
            accounts.accessToken(destination.teamId)
        } catch (_: SlackCallException) {
            // The refresh or the stored sign-in failed before chat.postMessage was sent.
            return ApiReplyResult(HandoffStatus.NOT_SENT)
        } ?: return ApiReplyResult(HandoffStatus.NOT_SENT_SIGN_IN)

        val form = buildMap {
            put("channel", destination.channelId)
            put("text", escape(text))
            destination.threadTs?.let { put("thread_ts", it) }
        }
        val response = try {
            transport.call("chat.postMessage", form, token)
        } catch (e: SlackCallException) {
            return failure(e)
        }
        // Slack's answer must name the same conversation and thread that Flack asked for.
        val channel = response.optString("channel")
        val threadTs = response.optJSONObject("message")?.optString("thread_ts")?.takeIf { it.isNotEmpty() }
        val samePlace = channel == destination.channelId && threadTs == destination.threadTs
        return ApiReplyResult(if (samePlace) HandoffStatus.POSTED else HandoffStatus.POSTED_PLACE_UNCONFIRMED)
    }

    private fun failure(e: SlackCallException): ApiReplyResult = when {
        e.error == SlackError.NETWORK -> ApiReplyResult(HandoffStatus.NOT_SENT)
        e.error == SlackError.REFUSED && e.slackCode in REFUSALS -> ApiReplyResult(HandoffStatus.NOT_SENT, e.slackCode)
        // No answer, an unreadable answer, or an error that can follow a partial success.
        else -> ApiReplyResult(HandoffStatus.OUTCOME_UNKNOWN, e.slackCode)
    }

    companion object {
        /**
         * chat.postMessage errors that Slack documents as refusals: nothing was posted.
         * Any other error, including internal_error and fatal_error, leaves the outcome unknown.
         */
        val REFUSALS = setOf(
            "channel_not_found", "not_in_channel", "is_archived", "msg_too_long", "restricted_action",
            "invalid_auth", "not_authed", "token_expired", "token_revoked", "account_inactive", "missing_scope",
            "ratelimited",
        )

        /** Slack reads &, < and > as markup in message text. */
        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
