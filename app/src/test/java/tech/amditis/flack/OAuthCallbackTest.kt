package tech.amditis.flack

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OAuthCallbackTest {
    private fun open(uri: String) =
        Robolectric.buildActivity(OAuthCallbackActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(uri))).setup().get()

    @Test fun malformedRedirectsAreRefusedWithoutCrashing() {
        // Any app can start this activity. None of these may throw.
        listOf("tech.amditis.flack:broken", "other://oauth?code=x&state=y", "tech.amditis.flack://elsewhere?code=x").forEach {
            assertTrue(it, open(it).isFinishing)
        }
    }

    @Test fun aRedirectFlackDidNotStartIsRefused() {
        assertTrue(open("tech.amditis.flack://oauth?code=invented&state=forged").isFinishing)
        assertTrue(SlackSession.lastMessage.orEmpty().contains("interrupted"))
    }
}
