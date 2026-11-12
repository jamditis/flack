package tech.amditis.flack.fixture

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Buttons for testing by hand, for example with the Desktop Head Unit. */
class FixtureActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply { setText(R.string.intro) })
        column.addView(button(R.string.post_dm) { post("dm-riley", "Riley Example", "Lunch at noon?", FixtureMessages.Kind.DM) })
        column.addView(button(R.string.post_second_dm) { post("dm-sam", "Sam Example", "Running ten minutes late.", FixtureMessages.Kind.DM) })
        column.addView(button(R.string.post_group) { post("group-project", "Riley Example", "Notes are in the shared folder.", FixtureMessages.Kind.GROUP) })
        column.addView(button(R.string.post_channel) { post("channel-kites", "Riley Example", "Paper launch at noon.", FixtureMessages.Kind.CHANNEL) })
        column.addView(button(R.string.post_private_channel) { post("private-paper", "Sam Example", "The paper samples arrived.", FixtureMessages.Kind.PRIVATE_CHANNEL) })
        column.addView(button(R.string.post_thread) { post("thread-launch", "Riley Example", "Blue paper works.", FixtureMessages.Kind.THREAD) })
        column.addView(button(R.string.post_second_thread) { post("thread-folding", "Sam Example", "Fold twice.", FixtureMessages.Kind.THREAD) })
        column.addView(button(R.string.post_unknown) { post("unknown-paper", "Sam Example", "Read without replying.", FixtureMessages.Kind.UNKNOWN) })
        column.addView(button(R.string.post_two_actions) { post("dm-thread", "Sam Example", "See the thread above.", FixtureMessages.Kind.TWO_ACTIONS) })
        column.addView(button(R.string.clear) { FixtureMessages.clear(this) })
        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        column.addView(status)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        val reply = FixtureMessages.lastReply
        status.text = if (reply == null) getString(R.string.no_reply) else getString(R.string.last_reply, reply)
    }

    private fun post(conversation: String, sender: String, text: String, kind: FixtureMessages.Kind) =
        FixtureMessages.post(this, conversation, sender, text, kind)

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    }
}
