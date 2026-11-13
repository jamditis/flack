package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFitTest {
    private val entries = (1..5).map { ReportEntry("entry $it ${"x".repeat(20)}\n", it) }

    @Test fun returnsEverythingWhenItFits() {
        assertEquals("head\n" + entries.joinToString("") { it.text }, fitEntries("head\n", entries, 10_000, "cases"))
    }

    @Test fun leavesOutTheRestFromTheFirstEntryThatDoesNotFit() {
        val report = fitEntries("head\n", entries, 140, "cases")
        assertTrue(report.length <= 140)
        assertTrue(report, report.startsWith("head\nentry 1 "))
        // Entries cover 1 to 5 cases each, 15 in all; every left-out entry's cases are counted.
        val shown = (1..5).filter { report.contains("entry $it ") }.sum()
        assertTrue(report, report.endsWith("Truncated: ${15 - shown} cases not shown (report limit 140 characters).\n"))
    }

    @Test fun cutsAHeadThatDoesNotFitAtALineEnd() {
        val report = fitEntries("line one\n" + "y".repeat(200) + "\n", entries, 90, "cases")
        assertEquals("line one\nTruncated: 15 cases not shown (report limit 90 characters).\n", report)
    }

    @Test fun isEmptyWhenNotEvenTheLastLineFits() {
        assertEquals("", fitEntries("head\n", entries, 20, "cases"))
    }
}
