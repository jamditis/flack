package tech.amditis.flack

import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.amditis.flack.core.ApiReadOnlyReason
import tech.amditis.flack.core.WorkspaceToken

/** Invented accounts only. This exercises the same renderer as the asynchronous store callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignInStatusTest {
    private fun activity() = Robolectric.buildActivity(StatusActivity::class.java).create().get()

    private val workspace = WorkspaceToken(
        "T0PAPER001", "Paper Example", "U0PAPER001", "Riley Example",
        "invented-test-token", null, Long.MAX_VALUE,
    )

    private fun body(activity: StatusActivity) = activity.findViewById<TextView>(R.id.signin_body).text.toString()

    @Test fun emptyStoreShowsTheCueAndKeepsTheSignInActionAndPermissionExplanation() {
        val activity = activity()
        activity.renderSignInState(true, Result.success(emptyList()))
        assertTrue(body(activity).startsWith(activity.getString(R.string.signin_required)))
        assertTrue(body(activity).contains(activity.getString(R.string.signin_body)))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.signin_add).visibility)
        assertTrue(activity.findViewById<View>(R.id.signin_add).hasOnClickListeners())
        assertEquals(activity.getString(R.string.signin_add), activity.findViewById<TextView>(R.id.signin_add).text)
    }

    @Test fun liveAccountsClearTheCueDespiteHistoricalNotSignedInCountsAndSignOutRestoresIt() {
        repeat(5) { BridgeState.countApiReadOnly(ApiReadOnlyReason.NOT_SIGNED_IN) }
        val activity = activity()
        activity.renderSignInState(true, Result.success(emptyList()))
        activity.renderSignInState(true, Result.success(listOf(workspace)))
        assertEquals(activity.getString(R.string.signin_body), body(activity))
        assertFalse(body(activity).contains("T0PAPER001"))
        assertFalse(body(activity).contains("Riley Example"))
        activity.renderSignInState(true, Result.success(emptyList()))
        assertTrue(body(activity).startsWith(activity.getString(R.string.signin_required)))
    }

    @Test fun loadingAndStoreErrorsDoNotClaimThereAreNoAccounts() {
        val activity = activity()
        activity.renderSignInState(true, null)
        assertEquals(activity.getString(R.string.signin_body), body(activity))
        activity.renderSignInState(true, Result.failure(SlackCallException(SlackError.STORAGE)))
        assertEquals(activity.getString(R.string.signin_body), body(activity))
        val message = activity.findViewById<TextView>(R.id.signin_message)
        assertEquals(View.VISIBLE, message.visibility)
        assertEquals(activity.getString(R.string.signin_read_error), message.text)
        activity.renderSignInState(true, Result.success(listOf(workspace)))
        assertFalse(message.text.toString().contains(activity.getString(R.string.signin_read_error)))
    }

    @Test fun anUnconfiguredBuildExplainsWhySignInIsUnavailable() {
        val activity = activity()
        activity.renderSignInState(false, Result.success(emptyList()))
        assertEquals(activity.getString(R.string.signin_not_configured), body(activity))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.signin_add).visibility)
    }
}
