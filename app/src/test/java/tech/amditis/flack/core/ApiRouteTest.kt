package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Invented workspaces, conversations and field layouts only. */
class ApiRouteTest {

    private fun token(teamId: String, needsSignIn: Boolean = false) = WorkspaceToken(
        teamId = teamId, teamName = "Paper $teamId", userId = "U0RILEY001", userName = "riley",
        accessToken = "xoxe.xoxp-invented", refreshToken = "xoxe-1-invented", expiresAtMillis = Long.MAX_VALUE,
        needsSignIn = needsSignIn,
    )

    // An invented layout: tag "<workspace>:<conversation>", thread timestamp in one extra.
    private fun mapping(kind: MessageKind) = FieldMapping(
        kind = kind,
        workspace = FieldRef(RouteField.TAG, Regex("^([A-Z0-9]+):")),
        conversation = FieldRef(RouteField.TAG, Regex(":([A-Z0-9]+)$")),
        threadTs = FieldRef(RouteField.EXTRA, Regex("([0-9.]+)"), extraKey = "test.thread"),
        evidence = "invented for tests",
    )
    private val both = listOf(mapping(MessageKind.DIRECT), mapping(MessageKind.GROUP))

    private val channel = Conversation("#paper-kites", true, listOf(Message("Sam Example", false, "Kites at noon", 1_000L)))
    private val dm = Conversation(null, false, listOf(Message("Sam Example", false, "Kites at noon", 1_000L)))

    private fun fields(tag: String? = "T0PAPER001:C0KITES001", thread: String? = null) =
        RouteFields(tag = tag, extras = listOfNotNull(thread?.let { "test.thread" to it }))

    private fun resolve(
        conversation: Conversation = channel,
        fields: RouteFields = fields(),
        mappings: List<FieldMapping> = both,
        workspaces: () -> List<WorkspaceToken> = { listOf(token("T0PAPER001")) },
    ) = ApiRouteResolver.resolve(conversation, fields, workspaces, mappings)

    private fun readOnly(reason: ApiReadOnlyReason) = ApiRoute.ReadOnly(reason)

    @Test fun theShippedMappingKeepsEveryMessageReadOnlyEvenWithIdShapedFields() {
        var read = 0
        val full = fields(thread = "1700000000.000100")
        listOf(channel, dm).forEach {
            val route = ApiRouteResolver.resolve(it, full, { read++; listOf(token("T0PAPER001")) })
            assertEquals(readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING), route)
        }
        // Nothing on the phone is read for a message that has no mapping.
        assertEquals(0, read)
    }

    @Test fun aMappingForTheOtherKindDoesNotApply() {
        assertEquals(readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING), resolve(conversation = dm, mappings = listOf(mapping(MessageKind.GROUP))))
    }

    @Test fun twoMappingsForOneKindAreNotCertain() {
        assertEquals(readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING), resolve(mappings = both + mapping(MessageKind.GROUP)))
    }

    @Test fun anUnknownConversationTypeNeverGetsARoute() {
        assertEquals(readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING), resolve(conversation = channel.copy(isGroup = null)))
    }

    @Test fun aGroupWithoutATitleStaysReadOnly() {
        assertEquals(readOnly(ApiReadOnlyReason.UNTITLED_GROUP), resolve(conversation = channel.copy(title = " ")))
    }

    @Test fun aChannelMessageWithAllFieldsRoutesToTheChannelItself() {
        assertEquals(ApiRoute.Certain(ApiDestination("T0PAPER001", "C0KITES001", null)), resolve())
    }

    @Test fun aThreadTimestampRoutesToThatThread() {
        assertEquals(
            ApiRoute.Certain(ApiDestination("T0PAPER001", "C0KITES001", "1700000000.000100")),
            resolve(fields = fields(thread = "1700000000.000100")),
        )
    }

    @Test fun aDmNeedsADmConversationId() {
        assertEquals(
            ApiRoute.Certain(ApiDestination("T0PAPER001", "D0RILEY001", null)),
            resolve(conversation = dm, fields = fields(tag = "T0PAPER001:D0RILEY001")),
        )
        // A channel ID on a DM, or a DM ID on a channel, does not fit the message: read-only.
        assertEquals(readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID), resolve(conversation = dm))
        assertEquals(readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID), resolve(fields = fields(tag = "T0PAPER001:D0RILEY001")))
    }

    @Test fun aMissingOrMalformedWorkspaceIdIsUnknown() {
        assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_UNKNOWN), resolve(fields = fields(tag = null)))
        assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_UNKNOWN), resolve(fields = fields(tag = "C0KITES001:C0KITES001")))
        assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_UNKNOWN), resolve(fields = fields(tag = "paper:C0KITES001")))
    }

    @Test fun aWorkspaceThatIsNotSignedInStaysReadOnlyEvenWhenAnotherIs() {
        // Only one workspace is signed in, but the notification names a different one.
        assertEquals(readOnly(ApiReadOnlyReason.NOT_SIGNED_IN), resolve(fields = fields(tag = "T0OTHER0001:C0KITES001")))
        assertEquals(readOnly(ApiReadOnlyReason.NOT_SIGNED_IN), resolve(workspaces = { emptyList() }))
    }

    @Test fun anExpiredSignInStaysReadOnly() {
        assertEquals(readOnly(ApiReadOnlyReason.SIGN_IN_EXPIRED), resolve(workspaces = { listOf(token("T0PAPER001", needsSignIn = true)) }))
    }

    @Test fun unreadableAccountsStayReadOnly() {
        assertEquals(readOnly(ApiReadOnlyReason.ACCOUNTS_UNREADABLE), resolve(workspaces = { throw IllegalStateException("keystore") }))
    }

    @Test fun aMissingConversationIdStaysReadOnly() {
        assertEquals(readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID), resolve(fields = fields(tag = "T0PAPER001:")))
    }

    @Test fun aRepeatedIdIsNotCertain() {
        val twice = FieldMapping(
            MessageKind.GROUP,
            workspace = FieldRef(RouteField.TAG, Regex("(T[A-Z0-9]{8,})")),
            conversation = FieldRef(RouteField.TAG, Regex("(C[A-Z0-9]{8,})")),
            threadTs = FieldRef(RouteField.EXTRA, Regex("([0-9.]+)"), extraKey = "test.thread"),
            evidence = "invented for tests",
        )
        assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_UNKNOWN), resolve(mappings = listOf(twice), fields = fields(tag = "T0PAPER001 T0OTHER0001 C0KITES001")))
        assertEquals(readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID), resolve(mappings = listOf(twice), fields = fields(tag = "T0PAPER001 C0KITES001 C0OTHER0001")))
    }

    @Test fun aThreadFieldWithoutOneTimestampLeavesThePlaceUnknown() {
        assertEquals(readOnly(ApiReadOnlyReason.NO_PLACE), resolve(fields = fields(thread = "")))
        assertEquals(readOnly(ApiReadOnlyReason.NO_PLACE), resolve(fields = fields(thread = "soon")))
        assertEquals(readOnly(ApiReadOnlyReason.NO_PLACE), resolve(fields = fields(thread = "1700000000.000100 1700000001.000200")))
    }
}
