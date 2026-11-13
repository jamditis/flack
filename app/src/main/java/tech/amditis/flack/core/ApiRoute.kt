package tech.amditis.flack.core

/** A structured notification field. Never a title, message text, sender name or action label. */
enum class RouteField { SHORTCUT_ID, LOCUS_ID, TAG, SENDER_KEY, SENDER_URI, EXTRA }

/**
 * Where one ID is read: a field (for [RouteField.EXTRA], the extras key) and a
 * pattern whose first group is the ID. The pattern must match exactly once.
 */
class FieldRef(val field: RouteField, val pattern: Regex, val extraKey: String? = null) {
    init {
        require((field == RouteField.EXTRA) == (extraKey != null)) { "extraKey is for EXTRA fields only" }
    }
}

/** The conversation kind a mapping covers, from the notification's group flag only. */
enum class MessageKind { DIRECT, GROUP }

/**
 * One evidence-backed field mapping for one message kind (docs/SLACK_SIGN_IN.md).
 *
 * [threadTs] gives the place: a value means a reply in that thread, an absent field
 * means the conversation itself. Add a mapping only when the route field probe
 * evidence shows all three for this kind, and cite it in [evidence].
 */
class FieldMapping(
    val kind: MessageKind,
    val workspace: FieldRef,
    val conversation: FieldRef,
    val threadTs: FieldRef,
    val evidence: String,
)

/**
 * The shipped mappings. Empty until the route field probe (#29) shows which
 * fields carry the IDs, so every message without a usable original reply action
 * stays read-only.
 */
object FieldMappings {
    val ALL: List<FieldMapping> = emptyList()
}

/** A certain Slack API destination. Memory only: the IDs are Slack identifiers. */
data class ApiDestination(val teamId: String, val channelId: String, val threadTs: String?)

/** Content-free reasons the API route is not used. Counted in the test report. */
enum class ApiReadOnlyReason {
    /** No evidence-backed mapping for this message kind, or the kind is unknown. */
    NO_FIELD_MAPPING,
    /** A group without a title: the driver could not hear where the reply goes. */
    UNTITLED_GROUP,
    WORKSPACE_UNKNOWN,
    NOT_SIGNED_IN,
    SIGN_IN_EXPIRED,
    /** The signed-in workspaces could not be read on the phone. */
    ACCOUNTS_UNREADABLE,
    NO_CONVERSATION_ID,
    NO_PLACE,
}

sealed interface ApiRoute {
    data class Certain(val destination: ApiDestination) : ApiRoute
    data class ReadOnly(val reason: ApiReadOnlyReason) : ApiRoute
}

/**
 * Picks a Slack API destination from structured notification fields, or keeps the
 * message read-only. Fails closed: a missing, repeated or malformed ID, a mapping
 * that is not for this kind, or a workspace that is not signed in all mean read-only.
 */
object ApiRouteResolver {
    private val WORKSPACE_ID = Regex("[TE][A-Z0-9]{8,}")
    private val DM_ID = Regex("D[A-Z0-9]{8,}")
    private val GROUP_ID = Regex("[CG][A-Z0-9]{8,}")
    private val TIMESTAMP = Regex("[0-9]{10}\\.[0-9]{6}")

    /**
     * [workspaces] is read only when a mapping gives a workspace ID. It may throw
     * when the signed-in workspaces cannot be read.
     */
    fun resolve(
        conversation: Conversation,
        fields: RouteFields,
        workspaces: () -> List<WorkspaceToken>,
        mappings: List<FieldMapping> = FieldMappings.ALL,
    ): ApiRoute {
        val kind = when (conversation.isGroup) {
            true -> MessageKind.GROUP
            false -> MessageKind.DIRECT
            null -> return readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING)
        }
        if (kind == MessageKind.GROUP && conversation.title.isNullOrBlank()) return readOnly(ApiReadOnlyReason.UNTITLED_GROUP)
        val mapping = mappings.filter { it.kind == kind }.singleOrNull() ?: return readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING)

        val teamId = extract(fields, mapping.workspace)?.takeIf(WORKSPACE_ID::matches)
            ?: return readOnly(ApiReadOnlyReason.WORKSPACE_UNKNOWN)
        val signedIn = try {
            workspaces()
        } catch (_: Exception) {
            return readOnly(ApiReadOnlyReason.ACCOUNTS_UNREADABLE)
        }
        // The ID decides. A workspace with the same name, or the only signed-in one, is never used instead.
        val token = signedIn.firstOrNull { it.teamId == teamId } ?: return readOnly(ApiReadOnlyReason.NOT_SIGNED_IN)
        if (token.needsSignIn) return readOnly(ApiReadOnlyReason.SIGN_IN_EXPIRED)

        val channelShape = if (kind == MessageKind.DIRECT) DM_ID else GROUP_ID
        val channelId = extract(fields, mapping.conversation)?.takeIf(channelShape::matches)
            ?: return readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID)

        // Only an absent field means the conversation itself. A present field that does
        // not give exactly one timestamp leaves the place unknown.
        val threadTs = if (value(fields, mapping.threadTs) == null) {
            null
        } else {
            extract(fields, mapping.threadTs)?.takeIf(TIMESTAMP::matches) ?: return readOnly(ApiReadOnlyReason.NO_PLACE)
        }
        return ApiRoute.Certain(ApiDestination(teamId, channelId, threadTs))
    }

    private fun value(fields: RouteFields, ref: FieldRef): String? = when (ref.field) {
        RouteField.SHORTCUT_ID -> fields.shortcutId
        RouteField.LOCUS_ID -> fields.locusId
        RouteField.TAG -> fields.tag
        RouteField.SENDER_KEY -> fields.senderKey
        RouteField.SENDER_URI -> fields.senderUri
        RouteField.EXTRA -> fields.extras.filter { it.first == ref.extraKey }.singleOrNull()?.second
    }

    /** The pattern's first group, when the field matches exactly once. */
    private fun extract(fields: RouteFields, ref: FieldRef): String? {
        val text = value(fields, ref) ?: return null
        val match = ref.pattern.findAll(text).toList().singleOrNull() ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    private fun readOnly(reason: ApiReadOnlyReason) = ApiRoute.ReadOnly(reason)
}
