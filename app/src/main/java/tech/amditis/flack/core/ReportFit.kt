package tech.amditis.flack.core

/**
 * The longest test report Flack copies. A tester pastes it into one chat message
 * or issue comment, and a longer paste was cut off in the middle of a case (#29).
 */
const val REPORT_LIMIT = 20_000

/**
 * One report entry: its text, ending in a newline, and how many cases it covers.
 */
data class ReportEntry(val text: String, val cases: Int)

/**
 * [head], then each of [sections] that has text, in order and each after a blank
 * line. A section builds its text for the character budget it is given. The room
 * left after the head is shared evenly, and room a shorter section does not use
 * goes to the longer ones, so a full section cannot push out the others (#73: the
 * route field probe was left out of a report). The sections never take the report
 * past [limit]; a head longer than [limit] is kept whole.
 */
fun fitSections(head: String, sections: List<(Int) -> String>, limit: Int = REPORT_LIMIT): String {
    val full = sections.map { it(Int.MAX_VALUE) }
    val shown = full.indices.filter { full[it].isNotEmpty() }
    val texts = full.toMutableList()
    val room = limit - head.length - shown.size
    var left = room
    shown.sortedBy { full[it].length }.forEachIndexed { done, index ->
        val share = (left / (shown.size - done)).coerceAtLeast(0)
        if (full[index].length > share) texts[index] = sections[index](share)
        left -= texts[index].length
    }
    // A cut section can come out well under its share when it merges repeated cases.
    // Offer the unused room to the cut sections again until none of them grows.
    var cut = shown.filter { texts[it] != full[it] }
    while (cut.isNotEmpty()) {
        val offered = cut
        cut = offered.filterIndexed { done, index ->
            val extra = (room - shown.sumOf { texts[it].length }).coerceAtLeast(0) / (offered.size - done)
            val longer = sections[index](texts[index].length + extra)
            (longer.length > texts[index].length).also { grew -> if (grew) texts[index] = longer }
        }
    }
    return (listOf(head) + texts).filter { it.isNotEmpty() }.joinToString("\n")
}

/**
 * [head], then as many [entries] as fit in [maxChars], in order. The first entry
 * that does not fit and every entry after it are left out, and a last line says
 * how many [noun] were left out. A head that does not fit is cut at a line end,
 * and every entry is left out. The result is never longer than [maxChars]; it is
 * empty when not even the last line fits.
 */
fun fitEntries(head: String, entries: List<ReportEntry>, maxChars: Int, noun: String): String {
    if (head.length + entries.sumOf { it.text.length } <= maxChars) return head + entries.joinToString("") { it.text }
    fun note(left: Int) = "Truncated: $left $noun not shown (report limit $maxChars characters).\n"
    val total = entries.sumOf { it.cases }
    val reserve = note(total).length
    if (maxChars < reserve) return ""
    val out = StringBuilder()
    for (line in head.lineSequence().filter { it.isNotEmpty() }.map { it + "\n" }) {
        if (out.length + line.length + reserve > maxChars) return out.append(note(total)).toString()
        out.append(line)
    }
    var shown = 0
    for (entry in entries) {
        if (out.length + entry.text.length + reserve > maxChars) break
        out.append(entry.text)
        shown += entry.cases
    }
    return out.append(note(total - shown)).toString()
}
