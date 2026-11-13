package tech.amditis.flack

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import tech.amditis.flack.core.SignInAttempts
import tech.amditis.flack.core.SignInCallback
import tech.amditis.flack.core.SignInFailure
import tech.amditis.flack.core.SlackOAuth
import tech.amditis.flack.core.WorkspaceToken
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Process-wide Slack sign-in state. Network and storage work runs on one background thread. */
object SlackSession {
    private val random = SecureRandom()
    val attempts = SignInAttempts { size -> ByteArray(size).also(random::nextBytes) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Phone-screen text about the last sign-in or sign-out. Memory only. */
    @Volatile var lastMessage: String? = null
        private set

    /** Called on the main thread after a sign-in or sign-out finishes. */
    @Volatile var onChange: (() -> Unit)? = null

    @Volatile private var accounts: SlackAccounts? = null

    /** False in builds without a Slack client ID. */
    val isConfigured: Boolean get() = BuildConfig.SLACK_CLIENT_ID.isNotEmpty()

    /** Saved accounts stay listed and can sign out even in a build without a client ID. */
    fun accounts(context: Context): SlackAccounts {
        accounts?.let { return it }
        synchronized(this) {
            return accounts ?: SlackAccounts(
                AccountStore(File(context.applicationContext.noBackupFilesDir, "slack-accounts.bin"), KeystoreSealer()),
                SlackHttp,
                BuildConfig.SLACK_CLIENT_ID,
            ).also { accounts = it }
        }
    }

    /** Loads the signed-in workspaces off the main thread. */
    fun loadWorkspaces(context: Context, result: (List<WorkspaceToken>) -> Unit) {
        val app = context.applicationContext
        val accounts = accounts(app)
        worker.execute {
            val list = try {
                accounts.workspaces()
            } catch (_: SlackCallException) {
                lastMessage = app.getString(R.string.signin_read_error)
                emptyList()
            }
            main.post { result(list) }
        }
    }

    /** Opens Slack's sign-in page in the browser. Returns false when no browser can open it. */
    fun startSignIn(activity: Activity): Boolean {
        if (!isConfigured) return false
        val start = attempts.start()
        val url = SlackOAuth.authorizeUrl(BuildConfig.SLACK_CLIENT_ID, start.state, start.challenge)
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun finishSignIn(context: Context, callback: SignInCallback) {
        val app = context.applicationContext
        when (callback) {
            is SignInCallback.Failed -> report(
                app.getString(if (callback.reason == SignInFailure.SLACK_ERROR) R.string.signin_slack_error else R.string.signin_interrupted),
            )
            is SignInCallback.Exchange -> {
                val accounts = accounts(app)
                worker.execute {
                    val message = try {
                        val result = accounts.complete(callback.code, callback.verifier)
                        app.getString(if (result.previousRevoked) R.string.signin_done else R.string.signin_done_previous_unconfirmed, result.token.teamName)
                    } catch (e: SlackCallException) {
                        app.getString(failureText(e))
                    }
                    report(message)
                }
            }
        }
    }

    fun signOut(context: Context, teamId: String, teamName: String) {
        val app = context.applicationContext
        val accounts = accounts(app)
        worker.execute {
            val message = try {
                app.getString(if (accounts.signOut(teamId)) R.string.signout_done else R.string.signout_unconfirmed, teamName)
            } catch (_: SlackCallException) {
                app.getString(R.string.signout_storage, teamName)
            }
            report(message)
        }
    }

    private fun failureText(e: SlackCallException) = when {
        e.slackCode == SlackAccounts.NOT_REVOKED -> R.string.signin_not_revoked
        e.error == SlackError.STORAGE -> R.string.signin_storage
        e.error == SlackError.REFUSED && e.slackCode !in SlackAccounts.UNCERTAIN_ERRORS -> R.string.signin_refused
        // A lost or unreadable answer, or a failure inside Slack: the exchange may have succeeded.
        else -> R.string.signin_uncertain
    }

    private fun report(message: String) {
        lastMessage = message
        main.post { onChange?.invoke() }
    }
}

/**
 * Receives Slack's sign-in redirect, tech.amditis.flack://oauth. Any app can open
 * this activity, so the result counts only if its state matches the sign-in Flack
 * started, and the code is useless without the verifier.
 */
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        // Any app can start this activity with any URI. Only Slack's redirect shape is read;
        // getQueryParameter throws on an opaque URI.
        val callback = if (uri == null || !uri.isHierarchical || uri.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST) {
            SignInCallback.Failed(SignInFailure.NO_CODE)
        } else {
            SlackSession.attempts.complete(uri.getQueryParameter("state"), uri.getQueryParameter("code"), uri.getQueryParameter("error"))
        }
        SlackSession.finishSignIn(this, callback)
        startActivity(Intent(this, StatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private companion object {
        const val CALLBACK_SCHEME = "tech.amditis.flack"
        const val CALLBACK_HOST = "oauth"
    }
}
