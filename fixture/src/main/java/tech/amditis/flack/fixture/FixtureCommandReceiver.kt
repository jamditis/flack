package tech.amditis.flack.fixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets the emulator test post messages from adb. Protected by DUMP, so only the
 * shell and the system can send it.
 *
 * adb shell am broadcast -a tech.amditis.flack.fixture.POST --es conversation dm-riley --es sender "Riley Example" --es text "Lunch at noon?" --es kind DM
 */
class FixtureCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "tech.amditis.flack.fixture.POST" -> FixtureMessages.post(
                context,
                conversation = intent.getStringExtra("conversation") ?: "dm-riley",
                sender = intent.getStringExtra("sender") ?: "Riley Example",
                text = intent.getStringExtra("text") ?: "Lunch at noon?",
                kind = runCatching { FixtureMessages.Kind.valueOf(intent.getStringExtra("kind") ?: "DM") }.getOrDefault(FixtureMessages.Kind.DM),
            )
            "tech.amditis.flack.fixture.CLEAR" -> FixtureMessages.clear(context)
        }
    }
}
