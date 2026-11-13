package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented identifiers in Slack's documented shapes, invented names and text. */
class RouteFieldProbeTest {
    private val probe = RouteFieldProbe()

    private fun record(tag: String?, title: String, sender: String, time: Long, extras: List<Pair<String, String>> = emptyList()) =
        probe.record("READ_ONLY(NO_REPLY_ACTION)", true, RouteFields(tag = tag, extras = extras), ProbeContext(title, sender, time))

    private fun line(report: String, case: Int, field: String): String =
        report.substringAfter("Case $case:").lines().first { it.trimStart().startsWith("$field:") }

    @Test fun countsIdShapesWithoutKeepingValues() {
        probe.record(
            "READ_ONLY(NO_REPLY_ACTION)", true,
            RouteFields(shortcutId = "T0INVENTED1:C0INVENTED2", tag = "1700000000.000100", otherExtras = 2, hasSubText = true, senderKey = "U0INVENTED3"),
            ProbeContext("#paper-kites", "Riley Example", 1_700_000_000_100L),
        )
        val report = probe.report()
        assertTrue(report.contains("Case 1: route=READ_ONLY(NO_REPLY_ACTION); isGroup=true; subtext=present"))
        assertTrue(line(report, 1, "shortcutId").contains("present, channel=1 workspace=1 user=0 timestamp=0"))
        assertTrue(line(report, 1, "locusId").endsWith("absent"))
        assertTrue(line(report, 1, "senderKey").contains("user=1"))
        assertTrue(line(report, 1, "tag").contains("timestamp is this message=yes"))
        listOf("INVENTED", "1700000000", "Riley", "paper-kites").forEach { assertFalse(it, report.contains(it)) }
    }

    @Test fun extrasKeysAreNeverShown() {
        record(null, "#paper-kites", "Riley Example", 1L, listOf("channelC0INVENTED2" to "C0INVENTED2", "Riley Example" to "x"))
        val report = probe.report()
        assertFalse(report.contains("channelC0"))
        assertFalse(report.contains("Riley"))
        assertTrue(line(report, 1, "extra key 1").contains("channel=1"))
        assertTrue(report.contains("extra key 2: present"))
    }

    @Test fun showsOnlyExactListedKeyNames() {
        record(
            null, "#paper-kites", "Riley Example", 1L,
            listOf(
                "com.Slack.extra.channel_id" to "C0INVENTED2",
                "team_id" to "no ids here",
                "RileyExample" to "T0INVENTED4",
                "riley.example.team" to "U0INVENTED5",
                "Team_ID" to "T0INVENTED6",
            ),
        )
        val report = probe.report()
        assertTrue(line(report, 1, "extra key 1").contains("key name=com.Slack.extra.channel_id"))
        assertFalse(line(report, 1, "extra key 2").contains("key name"))
        listOf(3, 4, 5).forEach { assertTrue(line(report, 1, "extra key $it").contains("key name=not on the list")) }
        listOf("team_id", "Team_ID", "Riley", "riley", "INVENTED").forEach { assertFalse(it, report.contains(it)) }
    }

    @Test fun showsIdPrefixLetters() {
        record("T0INVENTED1:D0INVENTED2 U0INVENTED3", "#paper-kites", "Riley Example", 1L)
        assertTrue(line(probe.report(), 1, "tag").contains("ID prefixes: D T U"))
    }

    @Test fun workspaceAgreementIsCheckedWithinAConversation() {
        record("T0AAAAAAA1", "#paper-kites", "Riley Example", 1L)
        record("T0AAAAAAA1", "#paper-kites", "Sam Example", 2L)
        record("T0BBBBBBB2", "#kite-repairs", "Riley Example", 3L)
        val second = line(probe.report(), 2, "tag")
        assertTrue(second, second.contains("workspace (group: same conversation label) agrees within group=yes"))
        assertFalse(second, second.contains("workspace (group: same conversation label) differs outside"))
        val split = RouteFieldProbe()
        split.record("ELIGIBLE", true, RouteFields(tag = "T0AAAAAAA1"), ProbeContext("#paper-kites", "Riley Example", 1L))
        split.record("ELIGIBLE", true, RouteFields(tag = "T0BBBBBBB2"), ProbeContext("#paper-kites", "Riley Example", 2L))
        assertTrue(line(split.report(), 2, "tag").contains("workspace (group: same conversation label) agrees within group=no"))
    }

    @Test fun countsIdsWithoutDigitsAndIdsTouchingLowercase() {
        record("CABCDEFGH channelC0INVENTED2", "#paper-kites", "Riley Example", 1L)
        assertTrue(line(probe.report(), 1, "tag").contains("channel=2"))
    }

    @Test fun channelIdsAgreeWithinAConversationAndDifferBetweenThem() {
        record("C0AAAAAAA1", "#paper-kites", "Riley Example", 1_000L)
        record("C0AAAAAAA1", "#paper-kites", "Sam Example", 2_000L)
        record("C0BBBBBBB2", "#kite-repairs", "Riley Example", 3_000L)
        val report = probe.report()
        assertTrue(report.contains("same conversation label as cases: 2"))
        val second = line(report, 2, "tag")
        assertTrue(second, second.contains("channel (group: same conversation label) agrees within group=yes"))
        assertTrue(second, second.contains("channel (group: same conversation label) differs outside group=yes"))
        val swapped = RouteFieldProbe()
        swapped.record("ELIGIBLE", true, RouteFields(tag = "C0AAAAAAA1"), ProbeContext("#paper-kites", "Riley Example", 1L))
        swapped.record("ELIGIBLE", true, RouteFields(tag = "C0BBBBBBB2"), ProbeContext("#paper-kites", "Riley Example", 2L))
        assertTrue(line(swapped.report(), 2, "tag").contains("agrees within group=no"))
    }

    @Test fun aTitleLessDmIsItsSenderAndUnknownConversationsAreSkipped() {
        probe.record("ELIGIBLE", false, RouteFields(tag = "D0AAAAAAA1"), ProbeContext(null, "Riley Example", 1L))
        probe.record("ELIGIBLE", false, RouteFields(tag = "D0AAAAAAA1"), ProbeContext(null, "Riley Example", 2L))
        probe.record("READ_ONLY(UNKNOWN_CONVERSATION)", null, RouteFields(tag = "D0AAAAAAA1"), ProbeContext(null, "Sam Example", 3L))
        val report = probe.report()
        val second = line(report, 2, "tag")
        assertTrue(second, second.contains("agrees within group=yes"))
        assertFalse(second, second.contains("differs outside group"))
        assertTrue(report.substringAfter("Case 3:").contains("same conversation label as cases: unknown"))
    }

    @Test fun blankTitlesFallBackToTheSender() {
        probe.record("ELIGIBLE", false, RouteFields(tag = "D0AAAAAAA1"), ProbeContext("", "Riley Example", 1L))
        probe.record("ELIGIBLE", false, RouteFields(tag = "D0BBBBBBB2"), ProbeContext("  ", "Sam Example", 2L))
        val second = line(probe.report(), 2, "tag")
        assertTrue(second, second.contains("differs outside group=yes"))
        assertFalse(second, second.contains("agrees within group"))
    }

    @Test fun workspaceAgreementSaysHowManyCasesHadOne() {
        probe.record("ELIGIBLE", true, RouteFields(tag = "T0INVENTED1"), ProbeContext("#paper-kites", "Riley Example", 1L))
        probe.record("ELIGIBLE", true, RouteFields(), ProbeContext("#paper-kites", "Riley Example", 2L))
        assertTrue(line(probe.report(), 1, "tag").contains("workspace same wherever seen=yes, seen in 1 of 2 cases"))
    }

    @Test fun aThreadParentTimestampPointsAtTheEarlierMessage() {
        record(null, "#paper-kites", "Riley Example", 1_700_000_000_000L)
        record("C0AAAAAAA1-1700000000.000100", "#paper-kites", "Sam Example", 1_700_000_050_000L)
        val tag = line(probe.report(), 2, "tag")
        assertTrue(tag, tag.contains("timestamp is this message=no"))
        assertTrue(tag, tag.contains("timestamp is message of cases: 1"))
    }

    @Test fun retainsOnlyTheLastTwentyCases() {
        record("C0FIRSTONE1", "#first", "Riley Example", 1L)
        repeat(20) { record(null, "#paper-kites", "Riley Example", 1L) }
        val report = probe.report()
        assertEquals(20, Regex("Case \\d+:").findAll(report).count())
        assertFalse(report.contains("channel=1"))
    }

    @Test fun aFullTwentyCaseReportFitsTheLimitWithTheSummaryFirst() {
        // Every case holds timestamps, so none collapses and the cap has to cut cases.
        repeat(20) { n ->
            val extras = (1..30).map { key -> "kite_field_$key" to "C0AAAAAAA$n 1700000000.0001${10 + n}" }
            record(null, "#paper-kites", "Riley Example", 1_700_000_000_000L + n, extras)
        }
        val report = probe.report(REPORT_LIMIT)
        assertTrue(probe.report().length > REPORT_LIMIT)
        assertTrue(report.length <= REPORT_LIMIT)
        assertTrue(report.indexOf("Field summary (20 cases") in 0 until report.indexOf("Case 1:"))
        assertTrue(report.contains("  extra key 30: present in 20 of 20 cases; ID prefixes: C"))
        val shown = Regex("Case \\d+:").findAll(report).count()
        assertTrue(report, report.trimEnd().endsWith("Truncated: ${20 - shown} cases not shown (report limit $REPORT_LIMIT characters)."))
    }

    @Test fun repeatedRoutesCollapseWhenTheFullReportDoesNotFit() {
        repeat(20) { n -> record("C0AAAAAAA${n % 4}", "#kite-${n % 4}", "Riley Example", n.toLong()) }
        val full = probe.report()
        val report = probe.report(full.length - 1)
        assertTrue(report.length < full.length)
        assertTrue(report.contains("Case 1: route=READ_ONLY(NO_REPLY_ACTION)"))
        assertTrue(report.contains("Cases ${(2..20).joinToString(", ")} (19 cases): route=READ_ONLY(NO_REPLY_ACTION), fields as in the summary"))
        assertTrue(report.contains("  Case 5: isGroup=true; same conversation label as cases: 1, 9, 13, 17; same sender name as cases: "))
        assertTrue(report.contains("tag: present in 20 of 20 cases; ID prefixes: C; channel (group: same conversation label) agrees within group: yes 20, no 0"))
        assertFalse(report.contains("Truncated"))
    }

    @Test fun parsesSlackTimestamps() {
        assertEquals(1_700_000_000_123L, RouteFieldProbe.timestampMillis("1700000000.123456"))
        assertEquals(null, RouteFieldProbe.timestampMillis("not a time"))
    }
}
