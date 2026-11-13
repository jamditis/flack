package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** #29's Nov. 13 field shapes and coordinator's workspace contract. All values are invented. */
class ApiRouteTest {
    private val dm = Conversation(null, false, listOf(Message("Sam Example", false, "Kites at noon", 1_000L)))
    private val group = dm.copy(title = "Paper room", isGroup = true)
    private fun token(team: String, expired: Boolean = false) = WorkspaceToken(
        team, "Paper", "U0RILEY001", "Riley", "invented", null, Long.MAX_VALUE, needsSignIn = expired,
    )
    private fun fields(id: String? = "D0PAPER001", team: String? = "T0PAPER001") = RouteFields(
        shortcutId = id, extras = listOfNotNull(team?.let { "unknown.key" to it }),
    )
    private fun resolve(
        conversation: Conversation = dm,
        fields: RouteFields = fields(),
        workspaces: () -> List<WorkspaceToken> = { listOf(token("T0PAPER001")) },
        confirm: (String) -> Boolean = { true },
    ) = ApiRouteResolver.resolve(conversation, fields, workspaces, confirm)
    private fun readOnly(reason: ApiReadOnlyReason) = ApiRoute.ReadOnly(reason)

    @Test fun matchingTeamAndExactShortcutGiveATopLevelDestination() {
        // The approved experiment also answers thread notifications at the top level.
        listOf(dm to "D0PAPER001", group to "G0PAPER001", group to "C0PAPER001",
            group.copy(title = "Paper room thread") to "G0PAPER001").forEach { (conversation, id) ->
            val checked = mutableListOf<String>()
            assertEquals(ApiRoute.Certain(ApiDestination("T0PAPER001", id)), resolve(conversation, fields(id),
                confirm = { team -> checked += team; true }))
            assertEquals(listOf("T0PAPER001"), checked)
        }
    }

    @Test fun malformedAndMismatchedConversationShapesStayReadOnlyWithoutLookup() {
        listOf(null, "", "D123", "d0PAPER001", " D0PAPER001", "D0PAPER001 ", "D0PAPER001:G0PAPER001",
            "T0PAPER001", "U0RILEY001", "C0PAPER001", "G0PAPER001").forEach { id ->
            assertEquals(readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID), resolve(fields = fields(id),
                workspaces = { error("must not read accounts") }, confirm = { error("must not look up") }))
        }
        assertEquals(readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID), resolve(group))
    }

    @Test fun missingDifferentOrEmbeddedWorkspaceIdsStayReadOnly() {
        listOf(null, "T0PAPER001:1700000000.000100", " T0PAPER001", "t0paper001", "T123").forEach {
            assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_UNKNOWN), resolve(fields = fields(team = it),
                confirm = { error("must not look up") }))
        }
        assertEquals(readOnly(ApiReadOnlyReason.NOT_SIGNED_IN), resolve(fields = fields(team = "T0OTHER001"),
            confirm = { error("must not use the only signed-in workspace") }))
        assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_UNCONFIRMED), resolve(confirm = { false }))
        assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_UNCONFIRMED), resolve(confirm = { error("lookup failed") }))
    }

    @Test fun extrasAreMatchedByWholeValueAndNeverByPositionOrTimestamp() {
        val values = listOf("sender" to "U0RILEY001", "timestamp" to "1700000000.000100",
            "teamA" to "T0PAPER001", "duplicate" to "T0PAPER001")
        listOf(values, values.reversed()).forEach { extras ->
            assertEquals(ApiRoute.Certain(ApiDestination("T0PAPER001", "D0PAPER001")), resolve(
                fields = RouteFields(shortcutId = "D0PAPER001", extras = extras)))
        }
        assertEquals(readOnly(ApiReadOnlyReason.WORKSPACE_AMBIGUOUS), resolve(
            fields = RouteFields(shortcutId = "D0PAPER001", extras = values + ("teamB" to "T0OTHER001")),
            confirm = { error("ambiguous even with only one signed-in workspace") },
        ))
    }

    @Test fun senderAndUnnamedExtrasNeverChooseAConversation() {
        assertEquals(readOnly(ApiReadOnlyReason.NO_CONVERSATION_ID), resolve(fields = RouteFields(
            senderKey = "U0RILEY001", tag = "D0PAPER001", extras = listOf("unknown" to "D0PAPER001"))))
    }

    @Test fun unknownKindAndUntitledGroupsStayReadOnly() {
        assertEquals(readOnly(ApiReadOnlyReason.NO_FIELD_MAPPING), resolve(dm.copy(isGroup = null)))
        assertEquals(readOnly(ApiReadOnlyReason.UNTITLED_GROUP), resolve(group.copy(title = " ")))
    }

    @Test fun absentExpiredAndUnreadableSignInsStayReadOnly() {
        assertEquals(readOnly(ApiReadOnlyReason.NOT_SIGNED_IN), resolve(workspaces = { emptyList() }))
        assertEquals(readOnly(ApiReadOnlyReason.SIGN_IN_EXPIRED), resolve(workspaces = { listOf(token("T0PAPER001", true)) }))
        assertEquals(readOnly(ApiReadOnlyReason.ACCOUNTS_UNREADABLE), resolve(workspaces = { error("unreadable") }))
    }
}
