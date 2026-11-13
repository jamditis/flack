package tech.amditis.flack

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Fixed, content-free reasons a Slack call failed. Never carries a token or response body. */
enum class SlackError {
    /** No connection, or it failed before the request was sent. */
    NETWORK,
    /** Slack answered with something that is not a Slack API response. */
    BAD_RESPONSE,
    /** Slack answered `ok: false`. [SlackCallException.slackCode] has Slack's error code. */
    REFUSED,
    /** Flack could not save the sign-in on the phone. */
    STORAGE,
}

/**
 * [slackCode] is Slack's documented error code, such as `invalid_auth`. It is a
 * fixed word from Slack's API reference, never message content.
 */
class SlackCallException(val error: SlackError, val slackCode: String? = null) : Exception(error.name)

/** One Slack Web API call. A seam so tests can stand in for the network. */
fun interface SlackTransport {
    /** Returns Slack's JSON for `ok: true`, or throws [SlackCallException]. */
    fun call(method: String, form: Map<String, String>, bearer: String?): JSONObject
}

/** HTTPS to slack.com only. Nothing here logs. */
object SlackHttp : SlackTransport {
    private const val BASE = "https://slack.com/api/"
    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val READ_TIMEOUT_MILLIS = 10_000

    override fun call(method: String, form: Map<String, String>, bearer: String?): JSONObject {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray()
        val text = try {
            val connection = URL(BASE + method).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                connection.outputStream.use { it.write(body) }
                connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            // The message of an IOException can echo the request; drop it.
            throw SlackCallException(SlackError.NETWORK)
        }
        return parse(text)
    }

    fun parse(text: String): JSONObject {
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            throw SlackCallException(SlackError.BAD_RESPONSE)
        }
        if (!json.optBoolean("ok", false)) {
            throw SlackCallException(SlackError.REFUSED, json.optString("error").takeIf { SLACK_CODE.matches(it) })
        }
        return json
    }

    // Slack error codes are lowercase words with underscores.
    private val SLACK_CODE = Regex("[a-z_]{1,64}")
}
