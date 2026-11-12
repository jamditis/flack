package tech.amditis.flack

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the setup screen for the README and docs. Run
 * `./gradlew recordRoborazziDebug` to write images to docs/screenshots/.
 * The screen holds no message content, so these images are safe to publish.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class StatusScreenshotTest {

    @Test
    @Config(qualifiers = "+night")
    fun setupScreenDark() = capture("setup-dark")

    @Test
    @Config(qualifiers = "+notnight")
    fun setupScreenLight() = capture("setup-light")

    private fun capture(name: String) {
        val activity = Robolectric.buildActivity(StatusActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.root).captureRoboImage("../docs/screenshots/$name.png")
    }
}
