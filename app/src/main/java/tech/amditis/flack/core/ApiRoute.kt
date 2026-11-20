package tech.amditis.flack.core

/** A confirmed top-level Slack API destination. IDs stay in memory. */
data class ApiDestination(val teamId: String, val channelId: String)

/** Content-free reasons the API route is not offered. */
enum class ApiReadOnlyReason {
    NO_FIELD_MAPPING,
    GROUP_PLACEMENT_UNVERIFIED,
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
 * #29 identifies D-shaped DM shortcuts and exact workspace values, verified with
 * auth.test. An ID can identify a group or channel but cannot prove top-level
 * placement there. The fallback posts only to the main view of a one-to-one DM.
 */
object ApiRouteResolver {
    private val DM_ID = Regex("D[A-Z0-9]{8,}")
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
        when (conversation.isGroup) {
            false -> Unit
            true -> return readOnly(ApiReadOnlyReason.GROUP_PLACEMENT_UNVERIFIED)
            null -> return readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING)
        }
        val channelId = fields.shortcutId?.takeIf(DM_ID::matches) ?: return readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID)
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
