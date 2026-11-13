package tech.amditis.flack

import org.json.JSONArray
import org.json.JSONObject
import tech.amditis.flack.core.SlackOAuth
import tech.amditis.flack.core.WorkspaceToken
import java.io.File

/** The sealed data can never be opened: its key is gone or the data is damaged. */
class SealedDataLost : Exception()

/** Encrypts the token file. Production uses [KeystoreSealer]; tests use a stand-in. */
interface TokenSealer {
    fun seal(plain: ByteArray): ByteArray
    /** Throws [SealedDataLost] for data that can never be opened, anything else for a failure that may pass. */
    fun open(sealed: ByteArray): ByteArray
    /** Deletes the key, so an old copy of the file can never be opened. */
    fun destroy()
}

/** The signed-in workspaces, as one encrypted file in the no-backup directory. */
class AccountStore(private val file: File, private val sealer: TokenSealer) {

    /**
     * Throws [SlackCallException] with [SlackError.STORAGE] when the file cannot be
     * read now; the file and key are kept, so a passing failure loses nothing. Only
     * data that can never be opened is deleted, and the person signs in again.
     */
    fun load(): List<WorkspaceToken> {
        if (!file.exists()) return emptyList()
        val plain = try {
            sealer.open(file.readBytes())
        } catch (_: SealedDataLost) {
            clear()
            return emptyList()
        } catch (_: Exception) {
            throw SlackCallException(SlackError.STORAGE)
        }
        return try {
            decode(plain.toString(Charsets.UTF_8))
        } catch (_: org.json.JSONException) {
            clear()
            emptyList()
        }
    }

    fun save(tokens: List<WorkspaceToken>) {
        if (tokens.isEmpty()) {
            clear()
            return
        }
        val temp = File(file.path + ".tmp")
        val saved = try {
            temp.writeBytes(sealer.seal(encode(tokens).toByteArray()))
            temp.renameTo(file)
        } catch (_: Exception) {
            // A full disk or a Keystore failure. The message never carries a token.
            false
        }
        if (!saved) {
            temp.delete()
            throw SlackCallException(SlackError.STORAGE)
        }
    }

    fun clear() {
        file.delete()
        sealer.destroy()
    }

    companion object {
        fun encode(tokens: List<WorkspaceToken>): String = JSONArray(
            tokens.map {
                JSONObject()
                    .put("teamId", it.teamId).put("teamName", it.teamName)
                    .put("userId", it.userId).put("userName", it.userName)
                    .put("accessToken", it.accessToken).put("refreshToken", it.refreshToken ?: JSONObject.NULL)
                    .put("expiresAt", it.expiresAtMillis).put("needsSignIn", it.needsSignIn)
                    .put("staleTokens", JSONArray(it.staleTokens)).put("unconfirmedRefresh", it.unconfirmedRefresh)
            },
        ).toString()

        fun decode(text: String): List<WorkspaceToken> {
            val array = JSONArray(text)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                WorkspaceToken(
                    teamId = item.getString("teamId"),
                    teamName = item.getString("teamName"),
                    userId = item.getString("userId"),
                    userName = item.getString("userName"),
                    accessToken = item.getString("accessToken"),
                    refreshToken = if (item.isNull("refreshToken")) null else item.getString("refreshToken"),
                    expiresAtMillis = item.getLong("expiresAt"),
                    needsSignIn = item.getBoolean("needsSignIn"),
                    staleTokens = item.optJSONArray("staleTokens")?.let { stale -> (0 until stale.length()).map(stale::getString) } ?: emptyList(),
                    unconfirmedRefresh = item.optBoolean("unconfirmedRefresh", false),
                )
            }
        }
    }
}

/**
 * Sign-in, token refresh and sign-out for every signed-in workspace
 * (docs/SLACK_SIGN_IN.md). Calls are serialized, so only one refresh runs at a
 * time: Slack revokes tokens beyond two active ones. Run off the main thread.
 */
class SlackAccounts(
    private val store: AccountStore,
    private val transport: SlackTransport,
    private val clientId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun workspaces(): List<WorkspaceToken> = store.load()

    /**
     * The saved sign-in. [previousRevoked] is false when this replaced an earlier
     * sign-in for the workspace and Slack did not confirm revoking its tokens.
     */
    class SignInResult(val token: WorkspaceToken, val previousRevoked: Boolean)

    /** Exchanges a sign-in code for a user token and saves the workspace. */
    @Synchronized
    fun complete(code: String, verifier: String): SignInResult {
        requireClientId()
        val grant = transport.call(
            "oauth.v2.access",
            mapOf("client_id" to clientId, "code" to code, "code_verifier" to verifier, "redirect_uri" to SlackOAuth.REDIRECT_URI),
            null,
        )
        val token = parseGrant(grant, previous = null).let { it.copy(userName = it.userId) }
        if (!grantedScopes(grant).containsAll(SlackOAuth.USER_SCOPES)) {
            throw SlackCallException(SlackError.REFUSED, if (revokeAll(token)) "missing_scope" else NOT_REVOKED)
        }
        // A new sign-in replaces this workspace's old one. Save the new grant with the old
        // tokens first, so a stopped process loses nothing that sign-out could revoke.
        val previous = try {
            store.load().firstOrNull { it.teamId == token.teamId }
        } catch (e: SlackCallException) {
            throw unsaved(token, e)
        }
        val saved = token.copy(staleTokens = previous?.let(::tokensOf).orEmpty(), unconfirmedRefresh = previous?.unconfirmedRefresh == true)
        try {
            replace(saved)
        } catch (e: SlackCallException) {
            // The old record stays on disk and keeps working; revoke only the new grant.
            throw unsaved(token, e)
        }
        // Revoke the old tokens, and keep any that Slack did not confirm.
        val leftover = saved.staleTokens.filterNot(::revoke)
        // The name is for the phone screen only; without it the sign-in stays listed under the user ID.
        val name = try {
            transport.call("auth.test", emptyMap(), saved.accessToken).optString("user")
        } catch (_: SlackCallException) {
            ""
        }
        val named = saved.copy(userName = name.ifEmpty { saved.userName }, staleTokens = leftover)
        if (named != saved) {
            try {
                replace(named)
            } catch (_: SlackCallException) {
                // The saved record still works. Sign-out retries its old tokens, and Slack
                // answers token_revoked for the ones already revoked.
            }
        }
        return SignInResult(named, leftover.isEmpty() && !named.unconfirmedRefresh)
    }

    /** Not saved, so nothing could sign [token] out later: revoke it now. */
    private fun unsaved(token: WorkspaceToken, cause: SlackCallException): SlackCallException =
        if (revokeAll(token)) cause else SlackCallException(SlackError.STORAGE, NOT_REVOKED)

    /**
     * A current access token for [teamId], refreshed when it expires within five
     * minutes. Null when the workspace is not signed in or must sign in again.
     * Throws [SlackCallException] when a needed refresh cannot reach Slack.
     */
    @Synchronized
    fun accessToken(teamId: String): String? {
        val token = store.load().firstOrNull { it.teamId == teamId } ?: return null
        if (token.needsSignIn) return null
        if (!token.needsRefresh(now())) return token.accessToken
        return refresh(token)?.accessToken
    }

    /**
     * Revokes and forgets [teamId]. With token rotation, auth.revoke revokes only
     * the token it is given, so every token Flack holds for it is revoked.
     * True only when Slack confirmed all of them.
     */
    @Synchronized
    fun signOut(teamId: String): Boolean {
        // No record proves nothing about Slack, for example after an earlier, unconfirmed sign-out.
        val token = store.load().firstOrNull { it.teamId == teamId } ?: return false
        // After a refused refresh, Slack's answer about the refresh token proves nothing.
        // A refresh whose answer was lost may have left tokens that Flack cannot revoke.
        val revoked = revokeAll(token) && !token.needsSignIn && !token.unconfirmedRefresh
        // Forget the workspace even when Slack could not be reached.
        store.save(store.load().filterNot { it.teamId == teamId })
        return revoked
    }

    private fun revoke(token: String): Boolean = try {
        transport.call("auth.revoke", emptyMap(), token).optBoolean("revoked", false)
    } catch (e: SlackCallException) {
        // Slack's own answer that the token no longer works counts as revoked.
        e.error == SlackError.REFUSED && e.slackCode in DEAD_TOKEN_ERRORS
    }

    private fun refresh(token: WorkspaceToken): WorkspaceToken? {
        val refreshToken = token.refreshToken ?: return token
        requireClientId()
        // Until Slack answers, the refresh may issue tokens Flack never receives. Record that first.
        replace(token.copy(unconfirmedRefresh = true))
        val response = try {
            transport.call(
                "oauth.v2.access",
                mapOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken),
                null,
            )
        } catch (e: SlackCallException) {
            val answered = e.error == SlackError.REFUSED && e.slackCode !in UNCERTAIN_ERRORS
            // Only a refused credential means signing in again. An outage keeps the sign-in.
            if (answered && e.slackCode in TERMINAL_REFRESH_ERRORS) {
                replace(token.copy(needsSignIn = true))
                return null
            }
            // Slack answered and issued nothing, so the earlier record stands.
            if (answered) replace(token)
            throw e
        }
        val grant = parseGrant(response, previous = token)
        // Refresh leaves earlier access tokens valid until they expire. Revoke them, and
        // keep the ones Slack did not confirm, so sign-out can try again.
        val stale = (token.staleTokens + token.accessToken).filterNot(::revoke)
        val fresh = grant.copy(staleTokens = stale, unconfirmedRefresh = token.unconfirmedRefresh)
        try {
            replace(fresh)
        } catch (e: SlackCallException) {
            // The file keeps the redeemed refresh token, which soon stops working, and
            // nothing could sign out the new tokens later: revoke them now.
            revokeAll(fresh)
            throw e
        }
        return fresh
    }

    private fun replace(token: WorkspaceToken) {
        store.save(store.load().filterNot { it.teamId == token.teamId } + token)
    }

    /**
     * Revokes every token in [token]. True when Slack confirmed all of them. The
     * phone clock is never proof that a token expired: only Slack's answer counts.
     */
    private fun revokeAll(token: WorkspaceToken): Boolean = tokensOf(token).map(::revoke).all { it }

    private fun tokensOf(token: WorkspaceToken) = token.staleTokens + token.accessToken + listOfNotNull(token.refreshToken)

    private fun requireClientId() {
        if (clientId.isEmpty()) throw SlackCallException(SlackError.REFUSED)
    }

    /** Reads a code exchange or a refresh. A refresh must stay in the same workspace. */
    private fun parseGrant(json: JSONObject, previous: WorkspaceToken?): WorkspaceToken {
        val user = json.optJSONObject("authed_user")?.takeIf { it.has("access_token") } ?: json
        fun text(source: JSONObject, key: String) = source.optString(key).takeIf { it.isNotEmpty() }
        val access = text(user, "access_token") ?: throw SlackCallException(SlackError.BAD_RESPONSE)
        if (user.optString("token_type", "user") != "user") throw SlackCallException(SlackError.BAD_RESPONSE)
        val team = json.optJSONObject("team")
        val teamId = team?.let { text(it, "id") } ?: previous?.teamId ?: throw SlackCallException(SlackError.BAD_RESPONSE)
        if (previous != null && teamId != previous.teamId) throw SlackCallException(SlackError.BAD_RESPONSE)
        val expiresIn = user.optLong("expires_in", 0L)
        return WorkspaceToken(
            teamId = teamId,
            teamName = team?.let { text(it, "name") } ?: previous?.teamName ?: teamId,
            userId = text(user, "id") ?: text(json, "user_id") ?: previous?.userId ?: throw SlackCallException(SlackError.BAD_RESPONSE),
            userName = previous?.userName ?: "",
            accessToken = access,
            refreshToken = text(user, "refresh_token") ?: previous?.refreshToken,
            expiresAtMillis = if (expiresIn > 0) now() + expiresIn * 1000 else Long.MAX_VALUE,
        )
    }

    private fun grantedScopes(grant: JSONObject): Set<String> {
        val user = grant.optJSONObject("authed_user") ?: grant
        return user.optString("scope").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    companion object {
        /** Flack's own code: Slack did not confirm revoking tokens Flack has now forgotten. */
        const val NOT_REVOKED = "flack_not_revoked"

        /** Slack errors after which oauth.v2.access may still have issued tokens. */
        val UNCERTAIN_ERRORS = setOf("internal_error", "fatal_error")

        /** Slack errors that mean the token given can never work again. */
        private val DEAD_TOKEN_ERRORS = setOf("token_expired", "token_revoked")

        /** Slack errors that mean the refresh token can never work again. */
        // Not invalid_auth: Slack also sends it for a request from an IP address the workspace does not allow.
        private val TERMINAL_REFRESH_ERRORS = setOf("invalid_refresh_token", "token_revoked", "token_expired", "account_inactive")
    }
}
