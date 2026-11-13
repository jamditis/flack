package tech.amditis.flack.core

/** A confirmed top-level Slack API destination. IDs stay in memory. */
data class ApiDestination(val teamId: String, val channelId: String)

/** Content-free reasons the API route is not offered. */
enum class ApiReadOnlyReason {
    NO_FIELD_MAPPING,
    UNTITLED_GROUP,
    NOT_SIGNED_IN,
    SIGN_IN_EXPIRED,
    ACCOUNTS_UNREADABLE,
    NO_CONVERSATION_ID,
    WORKSPACE_UNKNOWN,
    WORKSPACE_UNCONFIRMED,
    WORKSPACE_AMBIGUOUS,
}

sealed interface ApiRoute {
    data class Certain(val destination: ApiDestination) : ApiRoute
    data class ReadOnly(val reason: ApiReadOnlyReason) : ApiRoute
}

/**
 * #29, November 13 field report: shortcutId names a D conversation for DMs or a G
 * conversation for groups. Workspace extras have no known key; their whole value
 * must equal the signed-in workspace ID, confirmed with auth.test. The coordinator
 * also permits C-shaped group destinations. Multiple matching workspaces fail closed.
 * No notification timestamp is used: this route always posts at the top level.
 */
object ApiRouteResolver {
    private val DM_ID = Regex("D[A-Z0-9]{8,}")
    private val GROUP_ID = Regex("[CG][A-Z0-9]{8,}")
    private val WORKSPACE_ID = Regex("T[A-Z0-9]{8,}")

    /** Exact extra values only: never pick a numbered key or an ID inside another value. */
    fun workspaceIds(fields: RouteFields): Set<String> = fields.extras.map { it.second }.filter(WORKSPACE_ID::matches).toSet()

    /** Runs off the main thread. [confirm] checks auth.test with that workspace's token. */
    fun resolve(
        conversation: Conversation,
        fields: RouteFields,
        workspaces: () -> List<WorkspaceToken>,
        confirm: (teamId: String) -> Boolean,
    ): ApiRoute {
        val shape = when (conversation.isGroup) {
            false -> DM_ID
            true -> GROUP_ID
            null -> return readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING)
        }
        if (conversation.isGroup == true && conversation.title.isNullOrBlank()) return readOnly(ApiReadOnlyReason.UNTITLED_GROUP)
        val channelId = fields.shortcutId?.takeIf(shape::matches) ?: return readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID)
        val teams = workspaceIds(fields)
        if (teams.isEmpty()) return readOnly(ApiReadOnlyReason.WORKSPACE_UNKNOWN)
        if (teams.size != 1) return readOnly(ApiReadOnlyReason.WORKSPACE_AMBIGUOUS)
        val signedIn = try {
            workspaces()
        } catch (_: Exception) {
            return readOnly(ApiReadOnlyReason.ACCOUNTS_UNREADABLE)
        }
        if (signedIn.isEmpty()) return readOnly(ApiReadOnlyReason.NOT_SIGNED_IN)
        val matching = signedIn.filter { it.teamId in teams }
        if (matching.isEmpty()) return readOnly(ApiReadOnlyReason.NOT_SIGNED_IN)
        if (matching.size != 1) return readOnly(ApiReadOnlyReason.WORKSPACE_AMBIGUOUS)
        val workspace = matching.single()
        if (workspace.needsSignIn) return readOnly(ApiReadOnlyReason.SIGN_IN_EXPIRED)
        val confirmed = try {
            confirm(workspace.teamId)
        } catch (_: Exception) {
            return readOnly(ApiReadOnlyReason.WORKSPACE_UNCONFIRMED)
        }
        if (!confirmed) return readOnly(ApiReadOnlyReason.WORKSPACE_UNCONFIRMED)
        return ApiRoute.Certain(ApiDestination(workspace.teamId, channelId))
    }

    private fun readOnly(reason: ApiReadOnlyReason) = ApiRoute.ReadOnly(reason)
}
