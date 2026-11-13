package tech.amditis.flack

/** Confirms the token belongs to the workspace identified in the notification. No new scopes. */
class SlackWorkspaceCheck(private val accounts: SlackAccounts, private val transport: SlackTransport) {
    fun confirm(teamId: String): Boolean {
        val token = accounts.accessToken(teamId) ?: return false
        return confirmWithToken(teamId, token)
    }

    /** Dispatch checks the exact token it will use to post. */
    fun confirmWithToken(teamId: String, token: String): Boolean =
        transport.call("auth.test", emptyMap(), token).optString("team_id") == teamId
}
