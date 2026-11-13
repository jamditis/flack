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
